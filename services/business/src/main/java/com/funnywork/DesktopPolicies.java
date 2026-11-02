package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Server-enforced remote access constraints and user-overridable client defaults. */
@Service
public class DesktopPolicies {
    public record Constraints(@NotNull Boolean crossDeviceAccess,@NotNull Boolean remoteControl) {}
    public record Defaults(@NotNull @Pattern(regexp="system|light|dark") String theme,@NotNull @Pattern(regexp="system|zh-CN|en-US") String locale) {}
    public record Policy(@NotNull @Valid Constraints constraints,@NotNull @Valid Defaults defaults) {}
    public record Release(@Min(0) long expectedVersion,@NotNull @Valid Policy policy) {}
    public record Restore(@Min(0) long expectedVersion,@Min(0) long targetVersion) {}
    public record Preferences(@Pattern(regexp="system|light|dark") String theme,@Pattern(regexp="system|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*") @Size(max=80) String locale) {}
    public record Preview(@NotNull @Valid Policy policy,@NotNull @Valid Preferences preferences,@Min(0) int supportedSchema) {}
    public record Receipt(@NotNull @Pattern(regexp="[a-f0-9]{32}") String device,@Min(0) long version,@NotNull @Pattern(regexp="[a-f0-9]{64}") String digest,@Min(1) int schema,
        @NotNull @Pattern(regexp="[A-Za-z0-9.+_-]{1,80}") String clientVersion,@NotNull @Valid Preferences preferences) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ObjectMapper mapper;private final Clock clock;
    public DesktopPolicies(JdbcTemplate db,AuthService auth,TenantLocks locks,ObjectMapper mapper,Clock clock){this.db=db;this.auth=auth;this.locks=locks;this.mapper=mapper;this.clock=clock;}
    private static Policy defaults(){return new Policy(new Constraints(true,true),new Defaults("system","system"));}
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot serialize desktop policy");}}
    private Policy decode(String value){try{return mapper.readValue(value,Policy.class);}catch(Exception e){throw new IllegalStateException("Invalid stored desktop policy");}}
    private String digest(Policy policy){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(policy).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> latest(String tenant){var rows=db.queryForList("select * from desktop_policy_releases where tenant_id=? order by version desc limit 1",tenant);return rows.isEmpty()?Map.of("version",0L,"policy_json",encode(defaults()),"digest",digest(defaults())):rows.get(0);}
    private Identity user(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();return actor;}
    private Identity administrator(Identity actor){actor=user(actor);actor.requireUser("ADMIN");for(String scope:db.queryForList("select zones from principals where tenant_id=? and kind='USER'",String.class,actor.tenant()))try{if(!actor.zones().containsAll(Arrays.asList(mapper.readValue(scope,String[].class))))throw ApiException.forbidden();}catch(ApiException e){throw e;}catch(Exception e){throw ApiException.forbidden();}return actor;}
    private void validate(Policy policy){if(policy==null||policy.constraints()==null||policy.defaults()==null||policy.constraints().crossDeviceAccess()==null||policy.constraints().remoteControl()==null||!Set.of("system","light","dark").contains(Objects.toString(policy.defaults().theme(),""))||!Set.of("system","zh-CN","en-US").contains(Objects.toString(policy.defaults().locale(),"")))throw new ApiException(400,"INVALID_DESKTOP_POLICY","Invalid desktop policy");}
    private Map<String,Object> document(Map<String,Object> row){return Map.of("schema",1,"version",row.get("version"),"digest",row.get("digest"),"policy",decode((String)row.get("policy_json")));}
    public boolean canControl(Identity actor){actor=user(actor);var c=decode((String)latest(actor.tenant()).get("policy_json")).constraints();return c.crossDeviceAccess()&&c.remoteControl();}
    public void requireCrossDevice(Identity actor){actor=user(actor);if(!decode((String)latest(actor.tenant()).get("policy_json")).constraints().crossDeviceAccess())throw new ApiException(403,"CROSS_DEVICE_DISABLED","Cross-device access is disabled by organization policy");}
    /** Call before principal/device locks in a write transaction. */
    public void lock(Identity actor){locks.lock(actor.tenant());user(actor);}
    public void requireControl(Identity actor){if(!canControl(actor))throw new ApiException(403,"REMOTE_CONTROL_DISABLED","Remote control is disabled by organization policy");}
    public void checkRequest(Identity actor,String method,String path){
        if(!path.startsWith("/api/account/sync/"))return;
        // Read-side access is denied regardless of claimed client type, including legacy clients.
        if(method.equals("GET")||path.matches("/api/account/sync/sessions/[^/]+/(title|archive)"))requireCrossDevice(actor);
        if(path.matches("/api/account/sync/(devices/[^/]+/link|device-links/[^/]+/(inspect|confirm))"))requireCrossDevice(actor);
        if(method.equals("POST")&&path.matches("/api/account/sync/(devices|sessions)/[^/]+/commands"))requireControl(actor);
    }
    @Transactional(readOnly=true) public Map<String,Object> read(Identity actor){actor=administrator(actor);var row=latest(actor.tenant());return Map.of("current",document(row),"scope","TENANT","history",db.queryForList("select version,policy_json,created_at,actor_id,restored_from from desktop_policy_releases where tenant_id=? order by version desc limit 100",actor.tenant()),"receipts",db.queryForList("select principal_id,device_id,issued_version,issued_at,applied_version,applied_at,client_version,schema_version,effective_json from desktop_policy_receipts where tenant_id=? order by issued_at desc limit 100",actor.tenant()));}
    public Map<String,Object> preview(Identity actor,Preview request){administrator(actor);validate(request.policy());return Map.of("schema",1,"compatible",request.supportedSchema()==1,"effective",effective(request.policy(),request.preferences()));}
    private Map<String,Object> effective(Policy policy,Preferences preferences){
        String theme=preferences.theme()==null?policy.defaults().theme():preferences.theme(),locale=preferences.locale()==null?policy.defaults().locale():preferences.locale();
        if(!Set.of("system","light","dark").contains(theme)||(locale.length()>80||!locale.matches("system|[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")))throw new ApiException(400,"INVALID_PREFERENCES","Invalid client preferences");
        return Map.of("crossDeviceAccess",policy.constraints().crossDeviceAccess(),"remoteControl",policy.constraints().crossDeviceAccess()&&policy.constraints().remoteControl(),"theme",theme,"locale",locale,"themeSource",preferences.theme()==null?"ORGANIZATION_DEFAULT":"USER","localeSource",preferences.locale()==null?"ORGANIZATION_DEFAULT":"USER");
    }
    @Transactional public Map<String,Object> publish(Identity actor,Release request){lock(actor);actor=administrator(actor);return append(actor,request,null);}
    @Transactional public Map<String,Object> restore(Identity actor,Restore request){lock(actor);actor=administrator(actor);var rows=db.queryForList("select policy_json from desktop_policy_releases where tenant_id=? and version=?",actor.tenant(),request.targetVersion());if(rows.isEmpty())throw ApiException.missing();return append(actor,new Release(request.expectedVersion(),decode((String)rows.get(0).get("policy_json"))),request.targetVersion());}
    private Map<String,Object> append(Identity actor,Release request,Long restored){
        validate(request.policy());var current=latest(actor.tenant());long version=((Number)current.get("version")).longValue();if(version!=request.expectedVersion())throw ApiException.conflict("DESKTOP_POLICY_VERSION_CONFLICT");if(version>=9007199254740991L)throw ApiException.conflict("DESKTOP_POLICY_VERSION_EXHAUSTED");
        if(version==0&&db.queryForObject("select count(*) from desktop_policy_releases where tenant_id=?",Integer.class,actor.tenant())==0)insert(actor,0,defaults(),null);
        insert(actor,version+1,request.policy(),restored);
        if(!request.policy().constraints().crossDeviceAccess()||!request.policy().constraints().remoteControl())db.update("update sync_commands set status='expired' where tenant_id=? and status='queued'",actor.tenant());
        if(!request.policy().constraints().crossDeviceAccess())db.update("delete from sync_device_links where tenant_id=?",actor.tenant());
        auth.audit(actor,"DESKTOP_POLICY_PUBLISHED",Map.of("version",version+1,"restoredFrom",restored==null?-1:restored,"policy",request.policy()));return document(latest(actor.tenant()));
    }
    private void insert(Identity actor,long version,Policy policy,Long restored){db.update("insert into desktop_policy_releases values(?,?,?,?,?,?,?)",actor.tenant(),version,encode(policy),digest(policy),actor.id(),clock.millis(),restored);}
    @Transactional public Map<String,Object> issue(Identity actor,String device){actor=user(actor);if(device==null||!device.matches("[a-f0-9]{32}"))throw new ApiException(400,"INVALID_DEVICE","Invalid client device");lock(actor);var row=latest(actor.tenant());
        int updated=db.update("update desktop_policy_receipts set issued_version=?,issued_digest=?,issued_at=? where tenant_id=? and principal_id=? and device_id=?",row.get("version"),row.get("digest"),clock.millis(),actor.tenant(),actor.id(),device);
        if(updated==0)db.update("insert into desktop_policy_receipts(tenant_id,principal_id,device_id,issued_version,issued_digest,issued_at) values(?,?,?,?,?,?)",actor.tenant(),actor.id(),device,row.get("version"),row.get("digest"),clock.millis());return document(row);
    }
    @Transactional public Map<String,Object> acknowledge(Identity actor,Receipt receipt){actor=user(actor);lock(actor);if(receipt.schema()!=1)throw ApiException.conflict("DESKTOP_POLICY_UNSUPPORTED");var row=latest(actor.tenant());
        if(((Number)row.get("version")).longValue()!=receipt.version()||!row.get("digest").equals(receipt.digest()))throw ApiException.conflict("DESKTOP_POLICY_VERSION_CONFLICT");
        var effective=effective(decode((String)row.get("policy_json")),receipt.preferences());
        if(db.update("update desktop_policy_receipts set applied_version=?,applied_digest=?,applied_at=?,client_version=?,schema_version=?,preferences_json=?,effective_json=? where tenant_id=? and principal_id=? and device_id=? and issued_version=? and issued_digest=?",receipt.version(),receipt.digest(),clock.millis(),receipt.clientVersion(),receipt.schema(),encode(receipt.preferences()),encode(effective),actor.tenant(),actor.id(),receipt.device(),receipt.version(),receipt.digest())!=1)throw ApiException.conflict("DESKTOP_POLICY_NOT_ISSUED");
        return Map.of("version",receipt.version(),"state","APPLIED","effective",effective);
    }
}
