package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Managed adapters use deployment-owned environment secrets, never browser-provided tokens. */
@Service
public class ManagedGateways {
    public record Config(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9.-]{0,119}") String id,
        @NotBlank @Size(max=120) String name,@NotBlank @Pattern(regexp="[a-z0-9-]{1,64}") String zone,
        @NotBlank @Size(max=1000) String baseUrl,@NotBlank @Pattern(regexp="FUNNYWORK_GATEWAY_[A-Z0-9_]{1,80}") String credentialRef,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String contractHash,@NotNull Boolean enabled,@NotNull @Min(0) Integer expectedVersion) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Clock clock;
    private final Json json;private final ObjectMapper mapper;private final Set<String> staticIds;private final Set<String> hosts;private final org.springframework.core.env.Environment environment;
    public ManagedGateways(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock,Json json,ObjectMapper mapper,
        @Value("${funnywork.gateway-hosts:}") String allowedHosts,org.springframework.core.env.Environment environment,List<GatewayRegistry.Binding> configured){this.environment=environment;this.staticIds=new HashSet<>();for(var binding:configured)staticIds.add(binding.id());
        this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;this.json=json;this.mapper=mapper;
        hosts=new HashSet<>();for(String host:allowedHosts.split(","))if(!host.isBlank())hosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    private URI endpoint(String value){
        try {URI uri=URI.create(value);if(uri.getHost()==null || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getPort()>65535)throw new IllegalArgumentException();
            new HttpReceiptGateway(uri,()->"validation",json,mapper,5000);return uri;
        } catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_GATEWAY_ENDPOINT","Gateway requires a deployment-allowed host and a valid HTTPS base URL ending in / ");}
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(Identity actor,String zone,String after,int limit){
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        if(after==null || after.length()>120 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid page");
        var rows=db.queryForList("select * from managed_gateways where tenant_id=? and zone=? and id>? order by id limit ?",actor.tenant(),zone,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);var result=new LinkedHashMap<String,Object>();result.put("items",rows);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    private boolean writeContract(String raw){try{return Set.of(ToolContract.Effect.IDEMPOTENT_WRITE,ToolContract.Effect.NON_IDEMPOTENT_WRITE).contains(mapper.readValue(raw,ToolContract.class).effect());}catch(Exception e){throw new IllegalStateException("Invalid stored contract");}}
    @Transactional(readOnly=true)
    public Map<String,Object> contracts(Identity actor,String zone,String after,int limit){
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        if(after==null || !after.matches("[A-Za-z0-9-]{0,64}") || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid tool page");
        var rows=db.queryForList("select id,name,version,contract_hash,contract_json from tool_versions where tenant_id=? and zone=? and enabled=true and id>? order by id limit ?",actor.tenant(),zone,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows)if(writeContract((String)row.get("contract_json")))items.add(Map.of("id",row.get("id"),"name",row.get("name"),"version",row.get("version"),"contractHash",row.get("contract_hash")));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    /** Configuration readiness does not establish remote connectivity or receipt correctness. */
    @Transactional
    public Map<String,Object> check(Identity actor,String id,int version){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var rows=db.queryForList("select * from managed_gateways where tenant_id=? and id=?",actor.tenant(),id);
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));
        if(version<1 || ((Number)row.get("version")).intValue()!=version)throw ApiException.conflict("GATEWAY_VERSION_CONFLICT");
        boolean endpointAllowed=true;try{endpoint((String)row.get("base_url"));}catch(ApiException e){endpointAllowed=false;}
        String token=environment.getProperty((String)row.get("credential_ref"));
        boolean credentialPresent=token!=null&&!token.isBlank()&&token.length()<=8192&&token.chars().noneMatch(c->c<33||c>126);
        var matches=db.queryForList("select contract_json from tool_versions where tenant_id=? and zone=? and contract_hash=? and enabled=true limit 1",actor.tenant(),row.get("zone"),row.get("contract_hash"));
        boolean contractAvailable=!matches.isEmpty()&&writeContract((String)matches.get(0).get("contract_json"));
        boolean enabled=Boolean.TRUE.equals(row.get("enabled")),ready=enabled&&endpointAllowed&&credentialPresent&&contractAvailable;long now=clock.millis();
        auth.audit(actor,"GATEWAY_CONFIGURATION_CHECKED",Map.of("gatewayId",id,"version",version,"configurationReady",ready,"checkedAt",now));
        return Map.of("gatewayId",id,"version",version,"checkedAt",now,"enabled",enabled,"endpointAllowed",endpointAllowed,"credentialPresent",credentialPresent,"contractAvailable",contractAvailable,"configurationReady",ready,"remoteStatus","UNVERIFIED");
    }
    @Transactional
    public Map<String,Object> save(Identity actor,Config body){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(body.zone());if(staticIds.contains(body.id()))throw ApiException.conflict("STATIC_GATEWAY_ID_RESERVED");endpoint(body.baseUrl());
        var rows=db.queryForList("select zone,base_url,credential_ref,contract_hash from managed_gateways where tenant_id=? and id=?",actor.tenant(),body.id());
        if(rows.isEmpty()){
            if(body.expectedVersion()!=0)throw ApiException.conflict("GATEWAY_VERSION_CONFLICT");
            db.update("insert into managed_gateways values(?,?,?,?,?,?,?,?,1,?)",body.id(),actor.tenant(),body.zone(),body.name(),body.baseUrl(),body.credentialRef(),body.contractHash(),body.enabled(),clock.millis());
        }else{
            var old=rows.get(0);actor.requireZone((String)old.get("zone"));
            // The operation ledger freezes this identity. Changing its destination could dispatch an old operation to a different source.
            if(!body.zone().equals(old.get("zone")) || !body.baseUrl().equals(old.get("base_url")) || !body.credentialRef().equals(old.get("credential_ref")) || !body.contractHash().equals(old.get("contract_hash")))
                throw ApiException.conflict("GATEWAY_BINDING_IMMUTABLE");
            if(db.update("update managed_gateways set name=?,enabled=?,version=version+1,updated_at=? where tenant_id=? and id=? and version=?",body.name(),body.enabled(),clock.millis(),actor.tenant(),body.id(),body.expectedVersion())!=1)throw ApiException.conflict("GATEWAY_VERSION_CONFLICT");
        }
        auth.audit(actor,"GATEWAY_SAVED",Map.of("gatewayId",body.id(),"enabled",body.enabled()));return Map.of("id",body.id(),"version",body.expectedVersion()+1);
    }
    ExternalOperations.Gateway resolve(String tenant,String id,ToolContract contract,String hash,String zone){
        var rows=db.queryForList("select * from managed_gateways where tenant_id=? and id=? and enabled=true and contract_hash=?",tenant,id,hash);
        if(rows.isEmpty())throw new ApiException(403,"GATEWAY_BINDING_DENIED","No matching enabled gateway");
        var row=rows.get(0);if(!zone.equals(row.get("zone")))throw ApiException.forbidden();
        URI uri=endpoint((String)row.get("base_url"));String ref=(String)row.get("credential_ref");
        // Re-resolve each request so disable/revocation and deployment policy changes affect ledger retries too.
        return new ExternalOperations.Gateway(){
            private HttpReceiptGateway current(){
                var active=db.queryForList("select enabled from managed_gateways where tenant_id=? and id=?",tenant,id);
                if(active.isEmpty() || !Boolean.TRUE.equals(active.get(0).get("enabled")))throw ApiException.forbidden();
                return new HttpReceiptGateway(uri,()->environment.getProperty(ref),json,mapper,5000);
            }
            public void dispatch(String op,Map<String,Object> input)throws Exception{current().dispatch(op,input);}
            public ExternalOperations.Evidence lookup(String op,String inputHash)throws Exception{return current().lookup(op,inputHash);}
        };
    }
}
