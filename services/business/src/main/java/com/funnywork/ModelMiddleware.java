package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Durable, pool-serialized permits. Network IO never runs inside these transactions. */
@Service
public class ModelMiddleware {
    public record Pool(@NotBlank @Size(max=120) String name,
                       @Pattern(regexp="[a-z0-9-]{1,64}") @NotNull String zone,
                       @Min(1) @Max(1024) int concurrencyLimit,@Min(1) @Max(10000) int queueLimit,
                       @Min(1) @Max(1024) int userConcurrency,@Min(1) @Max(1000) int userQueue,
                       @Min(1000) @Max(3600000) long waitMs,boolean paused,@Min(0) int expectedVersion) {}
    public record Deployment(@NotBlank @Size(max=64) String poolId,@NotBlank @Size(max=120) String name,
                             @NotBlank @Size(max=120) String model,@Pattern(regexp="MESSAGES|CHAT_COMPLETIONS") @NotNull String protocol,
                             @NotBlank @Size(max=1000) String baseUrl,
                             @NotNull @Pattern(regexp="(?:FUNNYWORK_MODEL_[A-Z0-9_]{1,80})?") String credentialRef,
                             @Min(128) @Max(2000000) int contextTokens,@Min(1) @Max(262144) int maxOutputTokens,
                             @NotNull @Size(max=16) Map<String,Boolean> capabilities,boolean enabled) {}
    public record Ticket(@NotBlank @Size(max=64) String deploymentId,@NotNull @Pattern(regexp="[A-Za-z0-9_.:-]{1,128}") String callId,
                         @NotNull @Pattern(regexp="[A-Za-z0-9_.:-]{1,128}") String logicalCallId,
                         @NotNull @Size(max=128) String deviceId,@NotNull @Size(max=128) String sessionId,
                         @NotNull @Size(max=128) String taskId,@NotNull @Size(max=128) String parentTaskId,
                         @NotNull @Pattern(regexp="[a-z-]{1,40}") String purpose,
                         @NotNull @Pattern(regexp="[0-9a-f]{64}") String requestDigest,@Min(1) @Max(262144) int maxOutputTokens) {}
    public record Dispatch(Map<String,Object> ticket,Map<String,Object> deployment,String credential) {}
    private static final String OCCUPIED="('GRANTED','RUNNING','STOPPING','UNKNOWN')";
    private final JdbcTemplate db;
    private final AuthService auth;
    private final Json json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Environment environment;
    private final RecoveryGate recovery;
    private final Set<String> allowed;
    @org.springframework.beans.factory.annotation.Autowired private DesktopModelReleases desktopReleases;
    @org.springframework.beans.factory.annotation.Autowired private TenantLocks tenantLocks;
    private final ModelBudgets budgets;
    private final String executionOwner=AuthService.id();
    public ModelMiddleware(JdbcTemplate db,AuthService auth,Json json,ObjectMapper mapper,Clock clock,
                           PlatformTransactionManager transactions,Environment environment,RecoveryGate recovery,
                           @Value("${funnywork.model-inference-origins:}") String origins,ModelBudgets budgets) {
        this.db=db;this.auth=auth;this.json=json;this.mapper=mapper;this.clock=clock;this.budgets=budgets;
        tx=new TransactionTemplate(transactions);this.environment=environment;this.recovery=recovery;
        allowed=new HashSet<>();for(String origin:origins.split(","))if(!origin.isBlank())allowed.add(origin.trim());
    }
    public Map<String,Object> bootstrap(Identity actor){user(actor);return Map.of("enabled",environment.getProperty("funnywork.model-inference-enabled",Boolean.class,true));}
    public Map<String,Object> credential(Identity actor){return credential(actor,"");}
    public Map<String,Object> credential(Identity actor,String device){return tx.execute(status->{recovery.lockTransaction(false);
        Identity live=user(actor);if(!Boolean.TRUE.equals(bootstrap(live).get("enabled")))throw ApiException.conflict("MODEL_ENTRY_DISABLED");
        db.queryForList("select id from principals where tenant_id=? and id=? for update",live.tenant(),live.id());live=user(live);
        long expires=clock.millis()+600000;String token="mwi."+auth.newToken();
        db.update("delete from model_entry_credentials where expires_at<=?",clock.millis());
        db.update("insert into model_entry_credentials(token_hash,tenant_id,owner_id,auth_version,expires_at,device_id) values(?,?,?,?,?,?)",AuthService.hash(token),live.tenant(),live.id(),live.authVersion(),expires,device);
        return Map.of("token",token,"expires",expires);
    });}
    public void revokeEntry(Identity actor,String device){tx.executeWithoutResult(status->{recovery.lockTransaction(false);Identity live=user(actor);
        db.queryForList("select id from principals where tenant_id=? and id=? for update",live.tenant(),live.id());live=user(live);
        db.update("delete from model_entry_credentials where tenant_id=? and owner_id=? and device_id=?",live.tenant(),live.id(),device);
        auth.audit(live,"MODEL_ENTRY_REVOKED",Map.of("deviceId",device));
    });}
    public Identity authenticateEntry(String token,String path){
        if(!path.startsWith("/api/account/inference/")||path.endsWith("/credential")||path.equals("/api/account/inference/credentials/revoke"))throw ApiException.forbidden();
        if(!environment.getProperty("funnywork.model-inference-enabled",Boolean.class,true))throw ApiException.conflict("MODEL_ENTRY_DISABLED");
        var rows=db.queryForList("select owner_id,tenant_id,auth_version from model_entry_credentials where token_hash=? and expires_at>?",AuthService.hash(token),clock.millis());
        if(rows.isEmpty())throw new ApiException(401,"ENTRY_CREDENTIAL_EXPIRED","Refresh the entry credential");var row=rows.get(0);
        return user(new Identity((String)row.get("owner_id"),(String)row.get("tenant_id"),"USER",Set.of(),Set.of(),number(row,"auth_version")));
    }
    private Identity user(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();return actor;}
    private Map<String,Object> pool(Identity actor,String id,boolean lock){
        var rows=db.queryForList("select * from model_capacity_pools where tenant_id=? and id=?"+(lock?" for update":""),actor.tenant(),id);
        if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);
    }
    private Map<String,Object> deployment(Identity actor,String id){
        var rows=db.queryForList("select * from model_deployments where tenant_id=? and id=?",actor.tenant(),id);
        if(rows.isEmpty())throw ApiException.missing();pool(actor,(String)rows.get(0).get("pool_id"),false);return rows.get(0);
    }
    private void validateEndpoint(String url){
        try{
            URI u=URI.create(url);String origin=u.getScheme()+"://"+u.getRawAuthority();
            if(u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||
               !("https".equals(u.getScheme())||"http".equals(u.getScheme()))||!allowed.contains(origin)||
               u.getPath().contains("..")||u.getPath().contains("%")||u.getPort()>65535)throw new IllegalArgumentException();
        }catch(IllegalArgumentException e){throw new ApiException(400,"MODEL_ORIGIN_DENIED","Model origin must be explicitly deployment-allowed, without credentials or query");}
    }
    public Map<String,Object> savePool(Identity actor,String id,Pool body){return tx.execute(status->{recovery.lockTransaction(false);
        Identity live=user(actor);live.requireUser("ADMIN");live.requireZone(body.zone());
        if(body.userConcurrency()>body.concurrencyLimit()||body.expectedVersion()==Integer.MAX_VALUE)throw ApiException.conflict("INVALID_POOL_LIMIT");
        String key=id==null?AuthService.id():id;
        if(id==null){if(body.expectedVersion()!=0)throw ApiException.conflict("POOL_VERSION_CONFLICT");
            db.update("insert into model_capacity_pools(id,tenant_id,zone,name,concurrency_limit,queue_limit,user_concurrency,user_queue,wait_ms,paused,version) values(?,?,?,?,?,?,?,?,?,?,1)",key,live.tenant(),body.zone(),body.name(),body.concurrencyLimit(),body.queueLimit(),body.userConcurrency(),body.userQueue(),body.waitMs(),body.paused());
        }else{
            var old=pool(live,key,true);if(!body.zone().equals(old.get("zone")))throw ApiException.conflict("POOL_ZONE_IMMUTABLE");
            if(db.update("update model_capacity_pools set name=?,concurrency_limit=?,queue_limit=?,user_concurrency=?,user_queue=?,wait_ms=?,paused=?,version=version+1 where tenant_id=? and id=? and version=?",body.name(),body.concurrencyLimit(),body.queueLimit(),body.userConcurrency(),body.userQueue(),body.waitMs(),body.paused(),live.tenant(),key,body.expectedVersion())!=1)throw ApiException.conflict("POOL_VERSION_CONFLICT");
        }
        auth.audit(live,"MODEL_POOL_SAVED",Map.of("poolId",key,"version",body.expectedVersion()+1));return pool(live,key,false);
    });}
    public Map<String,Object> createDeployment(Identity actor,Deployment body){return tx.execute(status->{recovery.lockTransaction(false);
        Identity live=user(actor);live.requireUser("ADMIN");pool(live,body.poolId(),true);validateEndpoint(body.baseUrl());
        if(body.maxOutputTokens()>body.contextTokens()||body.capabilities().values().stream().anyMatch(Objects::isNull)||
           !Set.of("tools","reasoning","images","streamUsage").containsAll(body.capabilities().keySet()))throw new ApiException(400,"INVALID_MODEL_CAPABILITIES","Invalid model capabilities");
        String id=AuthService.id();db.update("insert into model_deployments values(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,live.tenant(),body.poolId(),body.name(),body.model(),body.protocol(),body.baseUrl(),body.credentialRef(),body.contextTokens(),body.maxOutputTokens(),json.write(body.capabilities()),body.enabled(),clock.millis());
        auth.audit(live,"MODEL_DEPLOYMENT_CREATED",Map.of("deploymentId",id,"poolId",body.poolId()));return Map.of("id",id);
    });}
    public void enableDeployment(Identity actor,String id,boolean enabled){tx.executeWithoutResult(status->{recovery.lockTransaction(false);
        Identity live=user(actor);live.requireUser("ADMIN");var d=deployment(live,id);pool(live,(String)d.get("pool_id"),true);
        db.update("update model_deployments set enabled=? where tenant_id=? and id=?",enabled,live.tenant(),id);
        if(!enabled)db.update("update model_call_tickets set state='CANCELLED',ended_at=?,outcome='MODEL_DISABLED' where tenant_id=? and deployment_id=? and state in ('WAITING','GRANTED')",clock.millis(),live.tenant(),id);
        auth.audit(live,"MODEL_DEPLOYMENT_ENABLED",Map.of("deploymentId",id,"enabled",enabled));
    });}
    public List<Map<String,Object>> pools(Identity actor){Identity live=user(actor);live.requireUser("ADMIN");return scoped("select * from model_capacity_pools where tenant_id=?",live,"zone");}
    public List<Map<String,Object>> deployments(Identity actor){Identity live=user(actor);live.requireUser("ADMIN");
        return scoped("select d.* from model_deployments d join model_capacity_pools p on p.id=d.pool_id and p.tenant_id=d.tenant_id where d.tenant_id=?",live,"p.zone");
    }
    /** Tenant-published public model joins the same admission ledger without enabling private inference. */
    public List<Map<String,Object>> chatCatalog(Identity actor){
        Identity live=user(actor);var items=new ArrayList<Map<String,Object>>();
        if(environment.getProperty("funnywork.model-inference-enabled",Boolean.class,true))
            for(var row:catalog(live))if(!((String)row.get("id")).startsWith("chat-public-"))items.add(row);
        try{var source=desktopReleases.resolve(live);if(live.zones().isEmpty())return items;
            String zone=live.zones().stream().sorted().findFirst().orElseThrow();
            String poolId="chat-pool-"+requestDigest((live.tenant()+"\n"+zone).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,40);
            String routeId="chat-public-"+requestDigest((poolId+"\n"+source.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,40);
            tx.executeWithoutResult(status->{recovery.lockTransaction(false);tenantLocks.lock(live.tenant());
                if(db.queryForObject("select count(*) from model_capacity_pools where id=?",Integer.class,poolId)==0)
                    db.update("insert into model_capacity_pools(id,tenant_id,zone,name,concurrency_limit,queue_limit,user_concurrency,user_queue,wait_ms,paused,version) values(?,?,?,?,2,1000,1,3,120000,false,1)",poolId,live.tenant(),zone,"手机聊天 · DeepSeek");
                if(db.queryForObject("select count(*) from model_deployments where id=?",Integer.class,routeId)==0)
                    db.update("insert into model_deployments values(?,?,?,?,?,?,?,?,?,?,?,?,?)",routeId,live.tenant(),poolId,"DeepSeek",source.model(),"CHAT_COMPLETIONS",source.endpoint(),"DESKTOP_PUBLICATION",ModelDefaults.contextTokens(source.model()),source.maxTokens(),"{\"streamUsage\":true}",true,clock.millis());
                db.update("update model_deployments set enabled=false where tenant_id=? and pool_id=? and id<>?",live.tenant(),poolId,routeId);
            });
            var row=deployment(live,routeId);if(Boolean.TRUE.equals(row.get("enabled"))){var p=pool(live,poolId,false);row.put("paused",p.get("paused"));row.put("capabilities",json.read((String)row.get("capabilities_json")));items.add(row);}
        }catch(ApiException failure){if(!Set.of("MODEL_NOT_CONFIGURED","MODEL_CREDENTIAL_UNAVAILABLE","INVALID_MODEL_RELEASE","POLICY_DENIED").contains(failure.code))throw failure;}
        return outputLimits(live,items);
    }

    public List<Map<String,Object>> catalog(Identity actor){Identity live=user(actor);
        var rows=scoped("select d.id,d.name,d.model,d.protocol,d.context_tokens,d.max_output_tokens,d.capabilities_json,p.zone,p.paused from model_deployments d join model_capacity_pools p on p.id=d.pool_id and p.tenant_id=d.tenant_id where d.tenant_id=? and d.enabled=true and d.id not like 'chat-public-%'",live,"p.zone");
        for(var row:rows){
            row.put("capabilities",json.read((String)row.remove("capabilities_json")));
            String maker=modelMaker((String)row.get("model"));
            row.put("provider_id","enterprise-"+maker);
            row.put("provider_name",switch(maker){case "openai"->"OpenAI";case "deepseek"->"DeepSeek";case "anthropic"->"Anthropic";case "google"->"Google";case "alibaba"->"Alibaba";case "moonshot"->"Moonshot";case "xai"->"xAI";default->"Custom";});
        }return outputLimits(live,rows);
    }
    private static String modelMaker(String model){
        String name=model.toLowerCase(Locale.ROOT);name=name.substring(name.lastIndexOf('/')+1);
        if(name.startsWith("gpt-")||name.matches("o[1-9](?:-.*)?")||name.startsWith("chatgpt-"))return "openai";
        if(name.startsWith("deepseek"))return "deepseek";
        if(name.startsWith("claude"))return "anthropic";
        if(name.startsWith("gemini"))return "google";
        if(name.startsWith("qwen"))return "alibaba";
        if(name.startsWith("kimi")||name.startsWith("moonshot"))return "moonshot";
        if(name.startsWith("grok"))return "xai";
        return "custom";
    }
    private List<Map<String,Object>> outputLimits(Identity actor,List<Map<String,Object>> rows){int limit=budgets.outputLimit(actor);if(limit>0)for(var row:rows)row.put("max_output_tokens",Math.min(limit,number(row,"max_output_tokens")));return rows;}
    private List<Map<String,Object>> scoped(String sql,Identity actor,String zone){if(actor.zones().isEmpty())return List.of();var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(actor.zones());return db.queryForList(sql+" and "+zone+" in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")",args.toArray());}
    private Map<String,Object> ticket(Identity actor,String id,boolean admin){
        var rows=db.queryForList("select * from model_call_tickets where tenant_id=? and id=?",actor.tenant(),id);
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));
        if(!actor.id().equals(row.get("owner_id"))){if(!admin)throw ApiException.missing();actor.requireUser("ADMIN");}return row;
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public Map<String,Object> admit(Identity actor,Ticket body){
        try{return admitOnce(actor,body);}catch(org.springframework.dao.DuplicateKeyException conflict){throw ApiException.conflict("CALL_ID_REUSED");}
    }
    private Map<String,Object> admitOnce(Identity actor,Ticket body){return tx.execute(status->{recovery.lockTransaction(false);
        Identity live=user(actor);var d=deployment(live,body.deploymentId());var p=pool(live,(String)d.get("pool_id"),true);
        var prior=db.queryForList("select * from model_call_tickets where tenant_id=? and owner_id=? and call_id=?",live.tenant(),live.id(),body.callId());
        if(!prior.isEmpty()){
            var row=prior.get(0);live.requireZone((String)row.get("zone"));
            if(!body.deploymentId().equals(row.get("deployment_id"))||!body.requestDigest().equals(row.get("request_digest"))||body.maxOutputTokens()!=number(row,"max_output_tokens")||
               !body.logicalCallId().equals(row.get("logical_call_id"))||!body.deviceId().equals(row.get("device_id"))||!body.sessionId().equals(row.get("session_id"))||!body.taskId().equals(row.get("task_id"))||!body.parentTaskId().equals(row.get("parent_task_id"))||!body.purpose().equals(row.get("purpose")))throw ApiException.conflict("CALL_ID_REUSED");
            schedule(live.tenant(),p);return publicTicket(ticket(live,(String)row.get("id"),false));
        }
        if(!Boolean.TRUE.equals(d.get("enabled")))throw ApiException.conflict("MODEL_DISABLED");
        if(Boolean.TRUE.equals(p.get("paused")))throw new ApiException(503,"MODEL_ADMISSION_PAUSED","Model admission is paused");
        if(body.maxOutputTokens()>number(d,"max_output_tokens"))throw new ApiException(400,"OUTPUT_LIMIT_EXCEEDED","Output budget exceeds model limit");
        String id=AuthService.id();long now=clock.millis();
        budgets.admit(live,body.deploymentId(),body.maxOutputTokens(),()->{
            schedule(live.tenant(),p);
            int waiting=db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and pool_id=? and state='WAITING'",Integer.class,live.tenant(),p.get("id"));
            int own=db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and pool_id=? and owner_id=? and state='WAITING'",Integer.class,live.tenant(),p.get("id"),live.id());
            if(waiting>=number(p,"queue_limit")||own>=number(p,"user_queue"))throw new ApiException(429,"MODEL_QUEUE_FULL","Model queue is full; submit later");
        db.update("insert into model_call_tickets(id,tenant_id,pool_id,deployment_id,owner_id,auth_version,zone,call_id,logical_call_id,device_id,session_id,task_id,parent_task_id,purpose,request_digest,max_output_tokens,state,created_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'WAITING',?,?)",id,live.tenant(),p.get("id"),d.get("id"),live.id(),live.authVersion(),p.get("zone"),body.callId(),body.logicalCallId(),body.deviceId(),body.sessionId(),body.taskId(),body.parentTaskId(),body.purpose(),body.requestDigest(),body.maxOutputTokens(),now,now+number(p,"wait_ms"));
            schedule(live.tenant(),p);
        });
        return publicTicket(ticket(live,id,false));
    });}
    /** Caller holds this pool's row lock; every replica observes the same occupied permits. */
    private void schedule(String tenant,Map<String,Object> p){
        long now=clock.millis();Object pool=p.get("id");
        db.update("update model_call_tickets set state='EXPIRED',ended_at=?,outcome='WAIT_EXPIRED' where tenant_id=? and pool_id=? and state='WAITING' and expires_at<=?",now,tenant,pool,now);
        db.update("update model_call_tickets set state='EXPIRED',ended_at=?,outcome='GRANT_EXPIRED' where tenant_id=? and pool_id=? and state='GRANTED' and grant_expires_at<=?",now,tenant,pool,now);
        // An interrupted process or network timeout is not evidence that upstream has stopped.
        db.update("update model_call_tickets set state='UNKNOWN',outcome='EXECUTION_DEADLINE' where tenant_id=? and pool_id=? and state in ('RUNNING','STOPPING') and execution_deadline<=?",tenant,pool,now);
        for(var granted:db.queryForList("select * from model_call_tickets where tenant_id=? and pool_id=? and state='GRANTED'",tenant,pool)){
            String denied=budgets.grantFailure(granted);
            if(ModelBudgets.fundsReserved(denied))db.update("update model_call_tickets set state='WAITING',granted_at=null,grant_expires_at=null where id=?",granted.get("id"));
            else if(denied!=null)db.update("update model_call_tickets set state='CANCELLED',ended_at=?,outcome=? where id=?",now,denied,granted.get("id"));
        }
        if(Boolean.TRUE.equals(p.get("paused")))return;
        int occupied=db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and pool_id=? and state in "+OCCUPIED,Integer.class,tenant,pool);
        var deferred=new ArrayList<String>();
        for(int slots=(int)number(p,"concurrency_limit")-occupied;slots>0;){
            var arguments=new ArrayList<Object>(List.of(tenant,pool,now,p.get("user_concurrency"),now-60000));arguments.addAll(deferred);
            var candidates=db.queryForList("select t.* from model_call_tickets t join model_user_budgets a on a.tenant_id=t.tenant_id and a.owner_id=t.owner_id left join model_pool_user_turns u on u.tenant_id=t.tenant_id and u.pool_id=t.pool_id and u.owner_id=t.owner_id where t.tenant_id=? and t.pool_id=? and t.state='WAITING' and a.enabled=true and a.expires_at>? and (select count(*) from model_call_tickets x where x.tenant_id=t.tenant_id and x.pool_id=t.pool_id and x.owner_id=t.owner_id and x.state in "+OCCUPIED+")<? and (select count(*) from model_call_tickets x where x.tenant_id=t.tenant_id and x.owner_id=t.owner_id and x.state in "+OCCUPIED+")<a.concurrency_limit and (select count(*) from model_budget_charges c where c.tenant_id=t.tenant_id and c.owner_id=t.owner_id and c.created_at>?)<a.calls_per_minute "+(deferred.isEmpty()?"":" and t.id not in ("+String.join(",",Collections.nCopies(deferred.size(),"?"))+")")+" order by coalesce(u.last_turn,0),t.created_at,t.id limit 1",arguments.toArray());
            if(candidates.isEmpty())break;var c=candidates.get(0);
            var principals=db.queryForList("select enabled,auth_version,zones from principals where tenant_id=? and id=?",tenant,c.get("owner_id"));
            boolean authorized=!principals.isEmpty()&&Boolean.TRUE.equals(principals.get(0).get("enabled"))&&number(principals.get(0),"auth_version")==number(c,"auth_version")&&json.strings((String)principals.get(0).get("zones")).contains(c.get("zone"));
            if(!authorized){db.update("update model_call_tickets set state='CANCELLED',ended_at=?,outcome='AUTH_REVOKED' where id=?",now,c.get("id"));continue;}
            String denied=budgets.grantFailure(c);if(ModelBudgets.fundsReserved(denied)){deferred.add((String)c.get("id"));continue;}if(denied!=null){db.update("update model_call_tickets set state='CANCELLED',ended_at=?,outcome=? where id=?",now,denied,c.get("id"));continue;}
            db.update("update model_capacity_pools set turn_counter=turn_counter+1 where id=?",pool);
            long turn=db.queryForObject("select turn_counter from model_capacity_pools where id=?",Long.class,pool);
            if(db.update("update model_pool_user_turns set last_turn=? where tenant_id=? and pool_id=? and owner_id=?",turn,tenant,pool,c.get("owner_id"))==0)db.update("insert into model_pool_user_turns values(?,?,?,?)",tenant,pool,c.get("owner_id"),turn);
            db.update("update model_call_tickets set state='GRANTED',granted_at=?,grant_expires_at=?,fair_turn=? where id=?",now,now+30000,turn,c.get("id"));slots--;
        }
    }
    private Map<String,Object> publicTicket(Map<String,Object> row){var result=new LinkedHashMap<String,Object>(row);result.remove("request_digest");result.remove("auth_version");result.remove("execution_owner");return result;}
    public Map<String,Object> readTicket(Identity actor,String id){return tx.execute(status->{recovery.lockTransaction(false);Identity live=user(actor);var t=ticket(live,id,false);var p=pool(live,(String)t.get("pool_id"),true);schedule(live.tenant(),p);return publicTicket(ticket(live,id,false));});}
    public void cancel(Identity actor,String id,boolean admin){tx.executeWithoutResult(status->{recovery.lockTransaction(false);Identity live=user(actor);if(admin)live.requireUser("ADMIN");var t=ticket(live,id,admin);pool(live,(String)t.get("pool_id"),true);t=ticket(live,id,admin);String state=(String)t.get("state");
        if(Set.of("WAITING","GRANTED").contains(state))db.update("update model_call_tickets set state='CANCELLED',ended_at=?,outcome='USER_CANCELLED' where id=?",clock.millis(),id);
        else if("RUNNING".equals(state))db.update("update model_call_tickets set state='STOPPING',outcome='CANCEL_REQUESTED' where id=?",id);
        if(admin)auth.audit(live,"MODEL_TICKET_CANCELLED",Map.of("ticketId",id));
    });}
    public Dispatch begin(Identity actor,String id,byte[] request){return tx.execute(status->{recovery.lockTransaction(false);
        Identity live=user(actor);var t=ticket(live,id,false);var p=pool(live,(String)t.get("pool_id"),true);
        // Serialize the final authorization check against principal revocation, without tenant scheduling locks.
        db.queryForList("select id from principals where tenant_id=? and id=? for update",live.tenant(),live.id());live=user(live);
        if(number(t,"auth_version")!=live.authVersion())throw ApiException.forbidden();
        if(Boolean.TRUE.equals(p.get("paused")))throw new ApiException(503,"MODEL_ADMISSION_PAUSED","Model admission is paused");
        if(!"GRANTED".equals(t.get("state")))throw ApiException.conflict("MODEL_PERMIT_NOT_GRANTED");
        budgets.requireFunds(live,t);schedule(live.tenant(),p);t=ticket(live,id,false);
        if(!"GRANTED".equals(t.get("state")))throw ApiException.conflict("MODEL_PERMIT_NOT_GRANTED");
        var d=deployment(live,(String)t.get("deployment_id"));if(!Boolean.TRUE.equals(d.get("enabled")))throw ApiException.conflict("MODEL_DISABLED");
        validateRequest(t,d,request);
        String ref=(String)d.get("credential_ref"),credential;
        if("DESKTOP_PUBLICATION".equals(ref)){
            var source=desktopReleases.resolve(live);
            if(!source.endpoint().equals(d.get("base_url"))||!source.model().equals(d.get("model")))throw ApiException.conflict("MODEL_RELEASE_CHANGED");
            credential=source.apiKey();
        }else{validateEndpoint((String)d.get("base_url"));credential=ref.isEmpty()?null:environment.getProperty(ref);}
        if(!ref.isEmpty()&&(credential==null||credential.isBlank()||credential.length()>8192||credential.chars().anyMatch(c->c<33||c>126)))throw new ApiException(503,"MODEL_CREDENTIAL_UNAVAILABLE","Model credential is unavailable");
        budgets.reserve(live,t,d);
        long now=clock.millis();db.update("update model_call_tickets set state='RUNNING',started_at=?,execution_deadline=?,execution_owner=? where id=?",now,now+600000,executionOwner,id);
        return new Dispatch(ticket(live,id,false),d,credential);
    });}
    static String requestDigest(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private void validateRequest(Map<String,Object> t,Map<String,Object> d,byte[] bytes){
        if(bytes.length>ModelDefaults.MAX_REQUEST_BYTES||!requestDigest(bytes).equals(t.get("request_digest")))throw new ApiException(400,"REQUEST_DIGEST_MISMATCH","Request does not match its permit");
        try{
            JsonNode body=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if(body==null||!body.isObject()||!d.get("model").equals(body.path("model").asText())||!body.path("messages").isArray())throw new IllegalArgumentException();
            // Provider-side tools and media can incur charges outside text-token pricing.
            Set<String> fields=Set.of("model","messages","system","max_tokens","max_completion_tokens","n","stream","stream_options","temperature","top_p","top_k","stop","stop_sequences","presence_penalty","frequency_penalty","response_format","tools","tool_choice","parallel_tool_calls","thinking","reasoning_effort","enable_thinking","output_config","user","user_id","metadata","logprobs","top_logprobs","seed");
            for(var names=body.fieldNames();names.hasNext();)if(!fields.contains(names.next()))throw new IllegalArgumentException();
            JsonNode capabilities=mapper.readTree((String)d.get("capabilities_json"));
            boolean images=capabilities.path("images").asBoolean();String protocol=(String)d.get("protocol");
            if(body.has("system"))validateContent(body.path("system"),0,false,protocol);
            if(body.has("tools")){
                if(!body.path("tools").isArray())throw new IllegalArgumentException();
                for(JsonNode tool:body.path("tools")){
                    String type=tool.path("type").asText();
                    if("CHAT_COMPLETIONS".equals(d.get("protocol"))?!"function".equals(type):!type.isEmpty()&&!"custom".equals(type))throw new IllegalArgumentException();
                }
            }
            JsonNode limit=body.get("max_tokens");if(limit==null&&"CHAT_COMPLETIONS".equals(d.get("protocol")))limit=body.get("max_completion_tokens");
            if(limit==null||!limit.isIntegralNumber()||!limit.canConvertToLong()||limit.asLong()!=number(t,"max_output_tokens"))throw new IllegalArgumentException();
            if(body.has("max_completion_tokens")&&(!body.path("max_completion_tokens").isIntegralNumber()||!body.path("max_completion_tokens").canConvertToLong()||body.path("max_completion_tokens").asLong()!=number(t,"max_output_tokens")))throw new IllegalArgumentException();
            if(body.has("n")&&(!body.path("n").isIntegralNumber()||!body.path("n").canConvertToInt()||body.path("n").asInt()!=1))throw new IllegalArgumentException();
            if(body.has("stream")&&!body.get("stream").isBoolean())throw new IllegalArgumentException();
            for(JsonNode message:body.path("messages"))validateContent(message.path("content"),0,images,protocol);
            if(body.path("tools").size()>0&&!capabilities.path("tools").asBoolean())throw new IllegalArgumentException();
            if(("enabled".equals(body.path("thinking").path("type").asText())||body.path("enable_thinking").asBoolean(false))&&!capabilities.path("reasoning").asBoolean())throw new IllegalArgumentException();
        }catch(Exception e){throw new ApiException(400,"INVALID_MODEL_REQUEST","Request requires the pinned model, messages and declared output budget");}
    }
    private void validateContent(JsonNode content,int depth,boolean images,String protocol){
        if(depth>8||!(content.isMissingNode()||content.isNull()||content.isTextual()||content.isArray()))throw new IllegalArgumentException();
        if(content.isArray())for(JsonNode block:content){
            String type=block.path("type").asText();
            if("image_url".equals(type)){
                if(!images||!"CHAT_COMPLETIONS".equals(protocol))throw new IllegalArgumentException();
                JsonNode image=block.path("image_url");String url=image.path("url").asText();int comma=url.indexOf(',');
                if(!image.isObject()||comma<0||!Set.of("data:image/png;base64","data:image/jpeg;base64","data:image/webp;base64","data:image/gif;base64").contains(url.substring(0,comma)))throw new IllegalArgumentException();
                if(image.has("detail")&&!Set.of("auto","low","high").contains(image.path("detail").asText()))throw new IllegalArgumentException();
                validateImageData(url.substring(comma+1));continue;
            }
            if("image".equals(type)){
                if(!images||!"MESSAGES".equals(protocol))throw new IllegalArgumentException();
                JsonNode source=block.path("source");
                if(!"base64".equals(source.path("type").asText())||!Set.of("image/png","image/jpeg","image/webp","image/gif").contains(source.path("media_type").asText())||!source.path("data").isTextual())throw new IllegalArgumentException();
                validateImageData(source.path("data").asText());continue;
            }
            if(!Set.of("text","tool_use","tool_result","tool_addition","tool_removal","thinking","redacted_thinking").contains(type))throw new IllegalArgumentException();
            if("text".equals(type)&&!block.path("text").isTextual())throw new IllegalArgumentException();
            if("tool_result".equals(type))validateContent(block.path("content"),depth+1,images,protocol);
        }
    }
    private void validateImageData(String data){
        // Images must be inline and body-bound by admission, never remotely fetched by the provider.
        if(data.isEmpty()||data.length()>ModelDefaults.MAX_REQUEST_BYTES||Base64.getDecoder().decode(data).length==0)throw new IllegalArgumentException();
    }
    public boolean stopRequested(String id){
        if(recovery.failure()!=null)return true;
        var rows=db.queryForList("select t.tenant_id,t.owner_id,t.state,t.auth_version,t.zone,p.enabled,p.auth_version as current_auth_version,p.zones from model_call_tickets t left join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id where t.id=? and t.execution_owner=?",id,executionOwner);
        if(rows.isEmpty())return true;var row=rows.get(0);
        boolean stop=budgets.stopRequested(row)||!"RUNNING".equals(row.get("state"))||!Boolean.TRUE.equals(row.get("enabled"))||row.get("current_auth_version")==null||number(row,"auth_version")!=number(row,"current_auth_version")||!json.strings((String)row.get("zones")).contains(row.get("zone"));
        if(stop)db.update("update model_call_tickets set state='STOPPING',outcome='CANCEL_OR_AUTH_REVOKED' where id=? and execution_owner=? and state='RUNNING'",id,executionOwner);
        return stop;
    }
    public void finish(String id,String state,String outcome,ModelUsage usage,Long firstByte){tx.executeWithoutResult(status->{recovery.lockTransaction(false);
        var rows=db.queryForList("select * from model_call_tickets where id=? and execution_owner=?",id,executionOwner);if(rows.isEmpty())return;var t=rows.get(0);
        db.queryForList("select id from model_capacity_pools where tenant_id=? and id=? for update",t.get("tenant_id"),t.get("pool_id"));
        int changed=db.update("update model_call_tickets set state=?,outcome=?,ended_at=?,first_byte_at=?,first_output_at=?,input_tokens=?,output_tokens=?,cached_tokens=?,usage_json=?,usage_quality=? where id=? and execution_owner=? and state in ('RUNNING','STOPPING','UNKNOWN')",state,outcome,clock.millis(),firstByte,usage.firstOutputAt(),usage.input(),usage.output(),usage.cached(),usage.raw(),usage.quality(),id,executionOwner);
        if(changed==1)budgets.finish(id,state,outcome,usage);
    });}
    public void reconcile(Identity actor,String id,String evidence){tx.executeWithoutResult(status->{recovery.lockTransaction(false);Identity live=user(actor);live.requireUser("ADMIN");var t=ticket(live,id,true);pool(live,(String)t.get("pool_id"),true);t=ticket(live,id,true);
        boolean unknown="UNKNOWN".equals(t.get("state"));
        boolean pending=Set.of("SUCCEEDED","FAILED","CANCELLED").contains((String)t.get("state"))&&db.queryForObject("select count(*) from model_budget_charges where ticket_id=? and state='HELD'",Integer.class,id)>0;
        if(!unknown&&!pending)throw ApiException.conflict("TICKET_NOT_UNKNOWN");
        if(unknown)db.update("update model_call_tickets set state='FAILED',ended_at=?,outcome='ADMIN_CONFIRMED_STOPPED' where id=?",clock.millis(),id);
        budgets.reconcile(id);
        auth.audit(live,"MODEL_EXECUTION_RECONCILED",Map.of("ticketId",id,"evidence",evidence));
    });}
    public List<Map<String,Object>> queue(Identity actor,String poolId,int limit){if(limit<1||limit>100)throw new ApiException(400,"INVALID_PAGE","Limit must be 1..100");return tx.execute(status->{recovery.lockTransaction(false);Identity live=user(actor);live.requireUser("ADMIN");var p=pool(live,poolId,true);schedule(live.tenant(),p);return db.queryForList("select id,owner_id,deployment_id,call_id,task_id,purpose,state,created_at,granted_at,started_at,outcome from model_call_tickets where tenant_id=? and pool_id=? and state in ('WAITING','GRANTED','RUNNING','STOPPING','UNKNOWN') order by created_at,id limit ?",live.tenant(),poolId,limit);});}
    public Map<String,Object> queuePage(Identity actor,String poolId,String state,String owner,String deployment,long afterCreated,String afterId,int limit){
        if(limit<1||limit>100||afterCreated<0||afterId.length()>64||owner.length()>64||deployment.length()>64||(!state.isEmpty()&&!Set.of("WAITING","GRANTED","RUNNING","STOPPING","UNKNOWN").contains(state)))throw new ApiException(400,"INVALID_QUEUE_FILTER","Invalid queue filters");
        return tx.execute(status->{recovery.lockTransaction(false);Identity live=user(actor);live.requireUser("ADMIN");var p=pool(live,poolId,true);schedule(live.tenant(),p);
            var counts=db.queryForList("select state,count(*) as calls,min(created_at) as oldest_at from model_call_tickets where tenant_id=? and pool_id=? and state in ('WAITING','GRANTED','RUNNING','STOPPING','UNKNOWN') group by state",live.tenant(),poolId);
            long occupied=counts.stream().filter(row->!"WAITING".equals(row.get("state"))).mapToLong(row->number(row,"calls")).sum();
            var args=new ArrayList<Object>(List.of(live.tenant(),poolId));String where=" where t.tenant_id=? and t.pool_id=? and t.state in ('WAITING','GRANTED','RUNNING','STOPPING','UNKNOWN')";
            if(!state.isEmpty()){where+=" and t.state=?";args.add(state);}if(!owner.isEmpty()){where+=" and t.owner_id=?";args.add(owner);}if(!deployment.isEmpty()){where+=" and t.deployment_id=?";args.add(deployment);}
            long total=db.queryForObject("select count(*) from model_call_tickets t"+where,Long.class,args.toArray());
            if(afterCreated>0){where+=" and (t.created_at>? or (t.created_at=? and t.id>?))";args.add(afterCreated);args.add(afterCreated);args.add(afterId);}args.add(limit+1);
            var rows=db.queryForList("select t.id,t.owner_id,u.display_name,t.deployment_id,d.name as deployment_name,t.call_id,t.session_id,t.task_id,t.purpose,t.state,t.created_at,t.granted_at,t.started_at,t.outcome from model_call_tickets t left join principals u on u.id=t.owner_id and u.tenant_id=t.tenant_id left join model_deployments d on d.id=t.deployment_id and d.tenant_id=t.tenant_id"+where+" order by t.created_at,t.id limit ?",args.toArray());
            var result=new LinkedHashMap<String,Object>();result.put("counts",counts);result.put("occupied",occupied);result.put("available",Boolean.TRUE.equals(p.get("paused"))?0:Math.max(0,number(p,"concurrency_limit")-occupied));result.put("limit",p.get("concurrency_limit"));result.put("paused",p.get("paused"));result.put("pool",p);result.put("total",total);result.put("observedAt",clock.millis());
            if(rows.size()>limit){rows=rows.subList(0,limit);var last=rows.get(rows.size()-1);result.put("nextCreatedAt",last.get("created_at"));result.put("nextId",last.get("id"));}result.put("items",rows);return result;
        });
    }
    public Map<String,Object> usage(Identity actor,long from,long until,String afterOwner,String afterDeployment,int limit){
        Identity live=user(actor);live.requireUser("ADMIN");
        if(from<0||until<=from||until-from>93L*86400000||limit<1||limit>100||afterOwner.length()>64||afterDeployment.length()>64)throw new ApiException(400,"INVALID_USAGE_FILTER","Choose a period of at most 93 days and a page of 1..100");
        if(live.zones().isEmpty())return Map.of("items",List.of(),"coverage","ENTERPRISE_ENTRY_ONLY");
        var args=new ArrayList<Object>(List.of(live.tenant(),from,until));args.addAll(live.zones());args.add(afterOwner);args.add(afterOwner);args.add(afterDeployment);args.add(limit+1);
        var rows=db.queryForList("select owner_id,zone,deployment_id,count(*) as calls,sum(case when started_at is not null then 1 else 0 end) as attempts,sum(input_tokens) as input_tokens,sum(output_tokens) as output_tokens,sum(cached_tokens) as cached_tokens,sum(case when started_at is not null and usage_quality<>'COMPLETE' then 1 else 0 end) as incomplete_usage_calls,sum(case when granted_at is not null then granted_at-created_at else 0 end) as queue_ms from model_call_tickets where tenant_id=? and created_at>=? and created_at<? and zone in ("+String.join(",",Collections.nCopies(live.zones().size(),"?"))+") and (owner_id>? or (owner_id=? and deployment_id>?)) group by owner_id,zone,deployment_id order by owner_id,deployment_id limit ?",args.toArray());
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("coverage","ENTERPRISE_ENTRY_ONLY");result.put("observedAt",clock.millis());result.put("zones",live.zones().stream().sorted().toList());
        if(more){var last=rows.get(rows.size()-1);result.put("nextOwner",last.get("owner_id"));result.put("nextDeployment",last.get("deployment_id"));}return result;
    }
}
