package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.util.*;

/** Read-only diagnostics. Never reserves capacity, grants access or returns task text. */
@Service
public class ExecutionPreview {
    public record Request(@NotBlank String repositoryId,@NotNull @Pattern(regexp="DEVELOP|VERIFY|COMPILE") String action,
        @NotNull @Pattern(regexp="macos|windows|linux") String platform,@NotNull @Pattern(regexp="[A-Za-z0-9_-]{0,64}") String after,
        @NotNull @Size(max=120) String query,@Pattern(regexp="SYNC_CHECK") String purpose) {
        public Request(String repositoryId,String action,String platform,String after,String query){this(repositoryId,action,platform,after,query,null);}
    }
    private final JdbcTemplate db;private final AuthService auth;private final Json json;private final Clock clock;private final Environment env;private final ExecutionResources resources;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceSyncChecks syncChecks;
    public ExecutionPreview(JdbcTemplate db,AuthService auth,Json json,Clock clock,Environment env,ExecutionResources resources){this.db=db;this.auth=auth;this.json=json;this.clock=clock;this.env=env;this.resources=resources;}
    private Identity reader(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind())||!(actor.roles().contains("VIEWER")||actor.roles().contains("OPERATOR")))throw ApiException.forbidden();return actor;}
    private String pattern(String value){return "%"+value.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private List<Map<String,Object>> page(Identity actor,String after,String query){
        var rows=db.queryForList("select d.id,d.name,d.platform,d.revoked,d.runtime_epoch,d.state_seen,d.model_choices_json,p.zones as owner_zones,p.enabled as owner_enabled,b.node_id,b.enabled as binding_enabled,n.status,n.admin_paused,n.last_seen,n.capacity,n.capability,np.enabled as node_enabled,mp.platform as executor_platform,mp.executor_version,coalesce(o.busy,0) as busy from sync_devices d join principals p on p.id=d.principal_id and p.tenant_id=d.tenant_id left join maintenance_desktop_bindings b on b.device_id=d.id and b.tenant_id=d.tenant_id left join nodes n on n.id=b.node_id and n.tenant_id=b.tenant_id left join principals np on np.id=n.id and np.tenant_id=n.tenant_id left join maintenance_node_profiles mp on mp.node_id=n.id and mp.tenant_id=n.tenant_id left join (select node_id,count(*) as busy from attempts where state in ('RESERVED','RUNNING') group by node_id) o on o.node_id=n.id where d.tenant_id=? and d.id>? and (lower(d.name) like ? escape '!' or d.id like ? escape '!') order by d.id limit 51",actor.tenant(),after,pattern(query),pattern(query));
        return rows;
    }
    private boolean visible(Identity actor,Map<String,Object> row){return actor.zones().containsAll(json.strings((String)row.get("owner_zones")));}
    private Map<String,Object> device(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(String key:List.of("id","name","platform","revoked","node_id"))out.put(key,row.get(key));out.put("bound",row.get("node_id")!=null&&Boolean.TRUE.equals(row.get("binding_enabled")));out.put("online",Boolean.TRUE.equals(row.get("owner_enabled"))&&!Boolean.TRUE.equals(row.get("revoked"))&&row.get("runtime_epoch")!=null&&number(row,"state_seen")>=clock.millis()-30000);return out;}
    private long number(Map<String,Object> row,String key){return row.get(key) instanceof Number n?n.longValue():0;}
    @Transactional(readOnly=true) public Object devices(Identity actor,String after,String query){
        actor=reader(actor);if(after==null||!after.matches("[A-Za-z0-9_-]{0,64}")||query==null||query.length()>120)throw new ApiException(400,"INVALID_EXECUTION_QUERY","Invalid resource query");
        var rows=page(actor,after,query);var items=new ArrayList<Map<String,Object>>();String cursor=after;
        for(var row:rows.subList(0,Math.min(50,rows.size()))){cursor=(String)row.get("id");if(visible(actor,row))items.add(device(row));}
        return Map.of("items",items,"after",cursor,"hasMore",rows.size()>50);
    }
    @Transactional(readOnly=true) public Object preview(Identity actor,Request body){
        boolean syncCheck="SYNC_CHECK".equals(body.purpose());
        if(body.purpose()!=null&&(!syncCheck||!MaintenanceSyncChecks.AUTHORIZATION_ACTION.equals(body.action())))throw new ApiException(400,"INVALID_EXECUTION_QUERY","Sync checks require VERIFY");
        actor=reader(actor);var repos=db.queryForList("select * from maintenance_repositories where id=? and tenant_id=?",body.repositoryId(),actor.tenant());if(repos.isEmpty())repos=db.queryForList("select * from annual_datasets where id=? and tenant_id=?",body.repositoryId(),actor.tenant());if(repos.isEmpty())throw ApiException.missing();var repo=repos.get(0);actor.requireZone((String)repo.get("zone"));
        var routes=db.queryForList("select r.*,p.enabled as project_enabled,q.enabled as pool_enabled,f.enabled as profile_enabled,f.platforms_json from execution_all_routes r join execution_projects p on p.id=r.project_id and p.tenant_id=r.tenant_id join execution_pools q on q.id=r.pool_id and q.tenant_id=r.tenant_id join execution_profiles f on f.id=r.profile_id and f.tenant_id=r.tenant_id where r.repository_id=? and r.action=? and r.tenant_id=?",body.repositoryId(),body.action(),actor.tenant());
        Map<String,Object> route=routes.isEmpty()?Map.of():routes.get(0);var rows=page(actor,body.after(),body.query());var items=new ArrayList<Map<String,Object>>();String cursor=body.after();int authorized=0,available=0;
        long active=db.queryForObject("select count(*) from attempts where tenant_id=? and state in ('RESERVED','RUNNING')",Long.class,actor.tenant());
        boolean tenantFull=active>=db.queryForObject("select max_concurrency from tenant_guards where tenant_id=?",Long.class,actor.tenant());
        var limits=db.queryForList("select max_concurrency from agent_scheduling_limits where tenant_id=? and agent_id=? and zone=?",actor.tenant(),repo.get("agent_id"),repo.get("zone"));
        boolean agentFull=!limits.isEmpty()&&db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id join tasks t on t.id=u.task_id where t.tenant_id=? and t.agent_id=? and t.zone=? and a.state in ('RESERVED','RUNNING')",Long.class,actor.tenant(),repo.get("agent_id"),repo.get("zone"))>=((Number)limits.get(0).get("max_concurrency")).longValue();
        boolean recoveryBlocked=db.queryForObject("select count(*) from attempts where tenant_id=? and state in ('RESERVED','RUNNING') and recovery_generation<>(select generation from recovery_generation where id=1)",Integer.class,actor.tenant())>0;
        boolean writerBusy="DEVELOP".equals(body.action())&&db.queryForObject("select count(*) from execution_writers where repository_id=? and tenant_id=? and state in ('HELD','UNKNOWN')",Integer.class,body.repositoryId(),actor.tenant())>0;
        for(var row:rows.subList(0,Math.min(50,rows.size()))){cursor=(String)row.get("id");if(!visible(actor,row))continue;var reasons=new ArrayList<String>();
            if(!resources.enforced())reasons.add("ENFORCEMENT_DISABLED");
            if(!Boolean.TRUE.equals(repo.get("enabled")))reasons.add("REPOSITORY_DISABLED");
            if(route.isEmpty())reasons.add("POLICY_REQUIRED");else{
                if(!Boolean.TRUE.equals(route.get("enabled")))reasons.add("ROUTE_DISABLED");
                if(!Boolean.TRUE.equals(route.get("project_enabled")))reasons.add("PROJECT_DISABLED");
                if(!Boolean.TRUE.equals(route.get("pool_enabled")))reasons.add("POOL_DISABLED");
                if(!Boolean.TRUE.equals(route.get("profile_enabled")))reasons.add("PROFILE_DISABLED");
                if(!json.strings((String)route.get("platforms_json")).contains(body.platform()))reasons.add("PROFILE_PLATFORM_MISMATCH");
                if(db.queryForObject("select count(*) from execution_pool_memberships where pool_id=? and device_id=? and tenant_id=? and enabled=true",Integer.class,route.get("pool_id"),row.get("id"),actor.tenant())==0)reasons.add("NOT_MEMBER");
                var grants=db.queryForList("select effect from execution_all_grants where tenant_id=? and project_id=? and repository_id=? and pool_id=? and profile_id=? and enabled=true and expires_at>? and (device_id='' or device_id=?)",actor.tenant(),route.get("project_id"),body.repositoryId(),route.get("pool_id"),route.get("profile_id"),clock.millis(),row.get("id"));
                if(grants.stream().anyMatch(g->"DENY".equals(g.get("effect"))))reasons.add("EXPLICIT_DENY");else if(grants.stream().noneMatch(g->"ALLOW".equals(g.get("effect"))))reasons.add("NO_VALID_GRANT");
            }
            if(Boolean.TRUE.equals(row.get("revoked")))reasons.add("DEVICE_REVOKED");
            if(!Boolean.TRUE.equals(row.get("owner_enabled")))reasons.add("OWNER_DISABLED");
            if(!json.strings((String)row.get("owner_zones")).contains(repo.get("zone")))reasons.add("OWNER_SCOPE_MISMATCH");
            if(row.get("node_id")==null||!Boolean.TRUE.equals(row.get("binding_enabled")))reasons.add("NOT_BOUND");
            else{
                if(!Boolean.TRUE.equals(row.get("node_enabled")))reasons.add("NODE_DISABLED");
                if(Boolean.TRUE.equals(row.get("admin_paused")))reasons.add("NODE_PAUSED");
                if(!body.platform().equals(row.get("executor_platform")))reasons.add("PLATFORM_MISMATCH");
                if(syncCheck){
                    if(!syncChecks.executorAvailable(row))reasons.add("EXECUTOR_MISMATCH");
                }else if(("COMPILE".equals(body.action())?db.queryForObject("select count(*) from node_capabilities where node_id=? and capability=?",Integer.class,row.get("node_id"),Capabilities.ANNUAL)==0:!Capabilities.REPOSITORY.equals(row.get("capability")))||row.get("executor_version")==null||("VERIFY".equals(body.action())&&!"funnywork-native-v2".equals(row.get("executor_version"))))reasons.add("EXECUTOR_MISMATCH");
            }
            if(reasons.isEmpty())try{resources.authorization(actor.tenant(),new ExecutionResources.Scope(body.repositoryId(),body.action(),body.platform()),(String)row.get("node_id"));}catch(ApiException denied){reasons.add("AUTHORIZATION_CHANGED");}
            boolean allowed=reasons.isEmpty();if(allowed)authorized++;
            if(syncCheck){
                if(!Boolean.TRUE.equals(device(row).get("online")))reasons.add("OFFLINE");
                if(syncChecks.busy(actor.tenant(),(String)row.get("id")))reasons.add("AT_CAPACITY");
            }else if(!"READY".equals(row.get("status"))||number(row,"last_seen")<=clock.millis()-env.getProperty("funnywork.node-fresh-ms",Long.class,30000L)||row.get("runtime_epoch")==null)reasons.add("OFFLINE");
            if(!syncCheck){
                if(number(row,"busy")>=number(row,"capacity")&&row.get("node_id")!=null)reasons.add("AT_CAPACITY");
                if(writerBusy)reasons.add("WRITER_BUSY");
                if(tenantFull)reasons.add("TENANT_AT_CAPACITY");
                if(agentFull)reasons.add("AGENT_AT_CAPACITY");
                if(recoveryBlocked)reasons.add("RECOVERY_REQUIRED");
            }
            boolean ready=reasons.isEmpty();if(ready)available++;var item=device(row);item.put("authorized",allowed);item.put("available",ready);item.put("reasons",reasons);item.put("busy",number(row,"busy"));item.put("capacity",number(row,"capacity"));items.add(item);
        }
        return Map.of("items",items,"after",cursor,"hasMore",rows.size()>50,"authorizedOnPage",authorized,"availableOnPage",available,"observedAt",clock.millis(),"reserved",false,"route",route);
    }
}
