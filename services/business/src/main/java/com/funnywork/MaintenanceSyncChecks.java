package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Admin-requested bounded observations. No code-writing prompt, credentials, publication or merge authority. */
@Service
public class MaintenanceSyncChecks {
    /** Fixed observation checks consume verification authority, never repository-writing authority. */
    static final String AUTHORIZATION_ACTION = "VERIFY";
    public record Target(@NotBlank @Size(max=64) String deviceId,@NotNull @Pattern(regexp="macos|windows|linux") String platform) {}
    public record Create(@NotBlank String repositoryId,@NotNull @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String baseSha,
        @NotNull @Size(max=80) @Pattern(regexp="[0-9]+[.][0-9]+[.][0-9]+(?:[-+][A-Za-z0-9.-]+)?") String expectedVersion,
        @NotNull @Pattern(regexp="funnywork-codex-v1") String executor,@Min(1) @Max(1) int protocolVersion,
        @NotNull @Size(min=3,max=3) List<@Valid Target> targets) {}
    public record Receipt(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String runtimeEpoch,@NotNull @Pattern(regexp="[a-f0-9]{64}") String specHash,
        @NotNull @Pattern(regexp="CHECKED|UNAVAILABLE|FAILED|UNKNOWN") String outcome,
        @Pattern(regexp="[A-Za-z0-9._+-]{1,80}") String clientVersion,
        @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String observedSha,
        @NotNull @Size(max=6) Set<@Size(max=32) String> capabilities,@NotNull @Size(max=2000) String summary,
        @NotNull @Pattern(regexp="[A-Za-z0-9._+-]{1,80}") String executorVersion,
        @Pattern(regexp="[A-Za-z0-9._+-]{1,80}") String pluginVersion,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") String evidenceDigest,
        @Min(0) @Max(10000) int eventCount,@NotNull @Pattern(regexp="completed|failed|unknown") String terminalState,
        boolean processStopped,@Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String buildTree,Boolean buildDirty) {}
    public record Renewal(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String runtimeEpoch,@NotNull @Pattern(regexp="[a-f0-9]{64}") String specHash) {}
    public record Stopped(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String runtimeEpoch,@NotNull @Pattern(regexp="[a-f0-9]{64}") String specHash,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") String evidenceDigest) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;private final Clock clock;private final DesktopPolicies policies;private final ExecutionResources resources;
    public MaintenanceSyncChecks(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,Clock clock,DesktopPolicies policies,ExecutionResources resources,com.fasterxml.jackson.databind.ObjectMapper mapper){this.mapper=mapper;this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.clock=clock;this.policies=policies;this.resources=resources;}
    private Identity admin(Identity actor){actor=auth.current(actor);actor.requireUser("ADMIN");return actor;}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private Map<String,Object> repository(Identity actor,String id){var repo=one("select id,zone,slug from maintenance_repositories where id=? and tenant_id=? and enabled=true",id,actor.tenant());actor.requireZone((String)repo.get("zone"));return repo;}
    private Map<String,Object> device(Identity actor,String id){
        var row=one("select d.id,d.name,d.platform,d.runtime_epoch,d.state_seen,d.model_choices_json,p.zones as owner_zones,b.node_id from sync_devices d join principals p on p.id=d.principal_id and p.tenant_id=d.tenant_id join maintenance_desktop_bindings b on b.device_id=d.id and b.tenant_id=d.tenant_id where d.id=? and d.tenant_id=? and d.revoked=false and p.enabled=true and b.enabled=true",id,actor.tenant());
        if(!actor.zones().containsAll(json.strings((String)row.get("owner_zones"))))throw ApiException.forbidden();return row;
    }
    static String platform(Object value){return switch(String.valueOf(value)){case "darwin","macos"->"macos";case "win32","windows"->"windows";case "linux"->"linux";default->"unknown";};}
    private boolean online(Map<String,Object> device){return device.get("runtime_epoch")!=null&&device.get("state_seen") instanceof Number seen&&seen.longValue()>=clock.millis()-30000;}
    private com.fasterxml.jackson.databind.JsonNode choices(Map<String,Object> device){return mapper.valueToTree(json.read(device.get("model_choices_json")==null?"{}":(String)device.get("model_choices_json")));}
    boolean executorAvailable(Map<String,Object> device){return MaintenanceExecutorProtocol.available(choices(device));}
    @Transactional(readOnly=true) public Object capabilities(Identity actor,String deviceId){
        actor=admin(actor);var device=device(actor,deviceId);boolean online=online(device);var choices=choices(device);
        return Map.of("deviceId",deviceId,"platform",platform(device.get("platform")),"online",online,"available",online&&MaintenanceExecutorProtocol.available(choices),"executors",online&&choices.has("maintenanceExecutors")?choices.get("maintenanceExecutors"):mapper.createArrayNode());
    }
    @Transactional public Object create(Identity actor,String key,Create body){
        locks.lock(actor.tenant());actor=admin(actor);requireEnforcement();var repo=repository(actor,body.repositoryId());
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,120}"))throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required");
        if(!MaintenanceExecutorProtocol.CODEX.equals(body.executor())||body.protocolVersion()!=1||body.targets()==null||body.targets().size()!=3
            ||body.targets().stream().map(Target::platform).distinct().count()!=3||body.targets().stream().map(Target::deviceId).distinct().count()!=3
            ||!body.targets().stream().map(Target::platform).collect(java.util.stream.Collectors.toSet()).equals(Set.of("macos","windows","linux")))throw ApiException.conflict("SYNC_CHECK_TARGETS_REQUIRED");
        String hash=AuthService.hash(json.canonical(body));var old=db.queryForList("select id,request_hash from maintenance_sync_checks where tenant_id=? and owner_id=? and request_key=?",actor.tenant(),actor.id(),key);
        if(!old.isEmpty()){if(!hash.equals(old.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return detail(actor,(String)old.get(0).get("id"));}
        String id=AuthService.id();var targetRows=new ArrayList<Map<String,Object>>();
        for(var target:body.targets()){
            var device=device(actor,target.deviceId());if(!online(device)||!target.platform().equals(platform(device.get("platform")))||!MaintenanceExecutorProtocol.available(choices(device)))throw ApiException.conflict("SYNC_CHECK_EXECUTOR_UNAVAILABLE");
            resources.authorization(actor.tenant(),new ExecutionResources.Scope(body.repositoryId(),AUTHORIZATION_ACTION,target.platform()),(String)device.get("node_id"));
            targetRows.add(device);
        }
        db.update("insert into maintenance_sync_checks values(?,?,?,?,?,?,?,?,?,?)",id,actor.tenant(),actor.id(),actor.authVersion(),body.repositoryId(),key,hash,body.baseSha(),body.expectedVersion(),clock.millis());
        for(var device:targetRows){
            String target=AuthService.id();var spec=new LinkedHashMap<String,Object>();
            spec.put("taskId",id);spec.put("executor",body.executor());spec.put("protocolVersion",1);spec.put("runtimeEpoch",device.get("runtime_epoch"));spec.put("repository",repo.get("slug"));spec.put("baseSha",body.baseSha());spec.put("expectedVersion",body.expectedVersion());spec.put("requiredCapabilities",List.of("sync-check"));spec.put("platform",platform(device.get("platform")));spec.put("timeoutMs",60000);spec.put("maxOutputBytes",16384);spec.put("leaseDurationMs",15000);
            String encoded=json.canonical(spec);
            db.update("insert into maintenance_sync_check_targets(id,task_id,tenant_id,device_id,runtime_epoch,platform,state,spec_json,spec_hash,expires_at) values(?,?,?,?,?,?,'QUEUED',?,?,?)",target,id,actor.tenant(),device.get("id"),device.get("runtime_epoch"),platform(device.get("platform")),encoded,AuthService.hash(encoded),clock.millis()+300000);
        }
        auth.audit(actor,"MAINTENANCE_SYNC_CHECK_CREATED",Map.of("taskId",id,"repositoryId",body.repositoryId()));return detail(actor,id);
    }
    @Transactional(readOnly=true) public Object list(Identity actor){
        actor=admin(actor);var result=new ArrayList<Object>();
        for(String id:db.queryForList("select id from maintenance_sync_checks where tenant_id=? order by created_at desc,id desc limit 100",String.class,actor.tenant())){
            try{result.add(detail(actor,id));}catch(ApiException denied){if(denied.status!=403&&denied.status!=404)throw denied;}
            if(result.size()==25)break;
        }
        return result;
    }
    private void visibleDevice(Identity actor,String id){var owner=one("select p.zones from sync_devices d join principals p on p.id=d.principal_id and p.tenant_id=d.tenant_id where d.id=? and d.tenant_id=?",id,actor.tenant());if(!actor.zones().containsAll(json.strings((String)owner.get("zones"))))throw ApiException.forbidden();}
    @Transactional(readOnly=true) public Object detail(Identity actor,String id){
        actor=admin(actor);var job=one("select id,repository_id,base_sha,expected_version,created_at from maintenance_sync_checks where id=? and tenant_id=?",id,actor.tenant());var repo=one("select zone from maintenance_repositories where id=? and tenant_id=?",job.get("repository_id"),actor.tenant());actor.requireZone((String)repo.get("zone"));
        var result=new LinkedHashMap<String,Object>(job);var targets=new ArrayList<Map<String,Object>>();
        for(var row:db.queryForList("select id,device_id,platform,state,expires_at,receipt_json from maintenance_sync_check_targets where task_id=? and tenant_id=? order by platform",id,actor.tenant())){
            visibleDevice(actor,(String)row.get("device_id"));var view=new LinkedHashMap<String,Object>(row);view.remove("receipt_json");
            if(Set.of("QUEUED","CLAIMED").contains(row.get("state"))&&((Number)row.get("expires_at")).longValue()<clock.millis())view.put("state","CLAIMED".equals(row.get("state"))?"UNKNOWN":"EXPIRED");
            if(row.get("receipt_json")!=null){var receipt=mapper.valueToTree(json.read((String)row.get("receipt_json")));view.put("receipt",receipt);view.put("aligned","CHECKED".equals(receipt.path("outcome").asText())&&job.get("base_sha").equals(receipt.path("observedSha").asText())&&job.get("expected_version").equals(receipt.path("clientVersion").asText()));}targets.add(view);
        }
        result.put("targets",targets);return result;
    }
    boolean occupied(String tenant,String deviceId){return db.queryForObject("select count(*) from maintenance_sync_check_targets where device_id=? and tenant_id=? and (state='CLAIMED' or (state='UNKNOWN' and stopped_evidence_digest is null))",Integer.class,deviceId,tenant)>0;}
    boolean busy(String tenant,String deviceId){return occupied(tenant,deviceId)||nativeOccupied(tenant,deviceId);}
    private boolean nativeOccupied(String tenant,String deviceId){
        return db.queryForObject("select count(*) from maintenance_desktop_bindings b where b.device_id=? and b.tenant_id=? and (exists(select 1 from attempts a where a.node_id=b.node_id and a.tenant_id=b.tenant_id and a.state in ('RESERVED','RUNNING')) or exists(select 1 from maintenance_intake_attempts i where i.node_id=b.node_id and i.tenant_id=b.tenant_id and i.state='RUNNING') or exists(select 1 from execution_writers w join attempts a on a.id=w.attempt_id where a.node_id=b.node_id and a.tenant_id=b.tenant_id and w.state in ('HELD','UNKNOWN')))",Integer.class,deviceId,tenant)>0;
    }
    @Transactional public List<Map<String,Object>> dispatch(Identity actor,String deviceId,DesktopInteractions.State state){
        actor=auth.current(actor);if(!"USER".equals(actor.kind())||!policies.canControl(actor)||!MaintenanceExecutorProtocol.available(state.choices()))return List.of();
        locks.lock(actor.tenant());owner(actor,deviceId,state.epoch());
        db.update("update maintenance_sync_check_targets set state=case when state='CLAIMED' then 'UNKNOWN' else 'BLOCKED' end where device_id=? and tenant_id=? and state in ('QUEUED','CLAIMED') and runtime_epoch<>?",deviceId,actor.tenant(),state.epoch());
        if(occupied(actor.tenant(),deviceId)||nativeOccupied(actor.tenant(),deviceId))return List.of();
        var rows=db.queryForList("select t.*,j.repository_id,j.owner_id,j.owner_auth_version from maintenance_sync_check_targets t join maintenance_sync_checks j on j.id=t.task_id and j.tenant_id=t.tenant_id where t.device_id=? and t.tenant_id=? and t.state='QUEUED' and t.runtime_epoch=? and t.expires_at>? order by t.id",deviceId,actor.tenant(),state.epoch(),clock.millis());
        if(rows.isEmpty())return List.of();var target=rows.get(0);try{authorize(actor,target);}catch(ApiException denied){db.update("update maintenance_sync_check_targets set state='BLOCKED' where id=?",target.get("id"));return List.of();}
        var payload=new LinkedHashMap<String,Object>((Map<String,Object>)json.read((String)target.get("spec_json")));payload.put("specHash",target.get("spec_hash"));
        db.update("update maintenance_sync_check_targets set state='CLAIMED',expires_at=?,lease_expires_at=? where id=?",clock.millis()+90000,clock.millis()+15000,target.get("id"));
        return List.of(Map.of("id",target.get("id"),"kind","maintenance-sync-check","nativeId","","payload",payload));
    }
    private void owner(Identity actor,String id,String epoch){
        var device=one("select runtime_epoch from sync_devices where id=? and tenant_id=? and principal_id=? and revoked=false",id,actor.tenant(),actor.id());if(!Objects.equals(epoch,device.get("runtime_epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");
    }
    private void requireEnforcement(){if(!resources.enforced())throw ApiException.conflict("SYNC_CHECK_ENFORCEMENT_REQUIRED");}
    private void authorize(Identity actor,Map<String,Object> target){
        requireEnforcement();
        var creator=admin(new Identity((String)target.get("owner_id"),actor.tenant(),"USER",Set.of(),Set.of(),((Number)target.get("owner_auth_version")).longValue()));
        repository(creator,(String)target.get("repository_id"));device(creator,(String)target.get("device_id"));
        var binding=one("select b.node_id from maintenance_desktop_bindings b join nodes n on n.id=b.node_id and n.tenant_id=b.tenant_id join principals p on p.id=n.id where b.device_id=? and b.tenant_id=? and b.enabled=true and n.admin_paused=false and p.enabled=true",target.get("device_id"),actor.tenant());
        resources.authorization(actor.tenant(),new ExecutionResources.Scope((String)target.get("repository_id"),AUTHORIZATION_ACTION,(String)target.get("platform")),(String)binding.get("node_id"));
    }
    @Transactional public Object renew(Identity actor,String deviceId,String targetId,Renewal body){
        locks.lock(actor.tenant());actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();policies.requireControl(actor);owner(actor,deviceId,body.runtimeEpoch());
        var target=one("select t.*,j.repository_id,j.owner_id,j.owner_auth_version from maintenance_sync_check_targets t join maintenance_sync_checks j on j.id=t.task_id and j.tenant_id=t.tenant_id where t.id=? and t.device_id=? and t.tenant_id=?",targetId,deviceId,actor.tenant());
        if(!body.runtimeEpoch().equals(target.get("runtime_epoch"))||!body.specHash().equals(target.get("spec_hash")))throw ApiException.conflict("SYNC_CHECK_SPEC_CHANGED");
        authorize(actor,target);long now=clock.millis(),remaining=((Number)target.get("expires_at")).longValue()-now;
        if(!"CLAIMED".equals(target.get("state"))||remaining<=0||((Number)target.get("lease_expires_at")).longValue()<now)throw ApiException.conflict("SYNC_CHECK_EXPIRED");
        long duration=Math.min(15000,remaining);db.update("update maintenance_sync_check_targets set lease_expires_at=? where id=?",now+duration,targetId);
        return Map.of("leaseDurationMs",duration,"expiresAt",now+duration);
    }
    /** Cancellation evidence is not a success receipt and does not need the revoked execution grant. */
    @Transactional public Object stopped(Identity actor,String deviceId,String targetId,Stopped body){
        locks.lock(actor.tenant());actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();
        var device=one("select runtime_epoch from sync_devices where id=? and tenant_id=? and principal_id=?",deviceId,actor.tenant(),actor.id());
        if(!body.runtimeEpoch().equals(device.get("runtime_epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");
        var target=one("select * from maintenance_sync_check_targets where id=? and device_id=? and tenant_id=?",targetId,deviceId,actor.tenant());
        if(!body.runtimeEpoch().equals(target.get("runtime_epoch"))||!body.specHash().equals(target.get("spec_hash")))throw ApiException.conflict("SYNC_CHECK_SPEC_CHANGED");
        if(target.get("stopped_evidence_digest")!=null){if(!body.evidenceDigest().equals(target.get("stopped_evidence_digest")))throw ApiException.conflict("RESULT_CONFLICT");return Map.of("accepted",true,"duplicate",true);}
        if(!Set.of("CLAIMED","UNKNOWN").contains(target.get("state")))throw ApiException.conflict("SYNC_CHECK_NOT_RUNNING");
        db.update("update maintenance_sync_check_targets set state='STOPPED',stopped_evidence_digest=? where id=?",body.evidenceDigest(),targetId);
        auth.audit(actor,"MAINTENANCE_SYNC_CHECK_STOPPED",Map.of("targetId",targetId,"evidenceDigest",body.evidenceDigest()));
        return Map.of("accepted",true,"duplicate",false);
    }
    @Transactional public Object receipt(Identity actor,String deviceId,String targetId,Receipt body){
        locks.lock(actor.tenant());actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();policies.requireControl(actor);owner(actor,deviceId,body.runtimeEpoch());
        var target=one("select t.*,j.repository_id,j.owner_id,j.owner_auth_version from maintenance_sync_check_targets t join maintenance_sync_checks j on j.id=t.task_id and j.tenant_id=t.tenant_id where t.id=? and t.device_id=? and t.tenant_id=?",targetId,deviceId,actor.tenant());
        if(!body.runtimeEpoch().equals(target.get("runtime_epoch"))||!body.specHash().equals(target.get("spec_hash")))throw ApiException.conflict("SYNC_CHECK_SPEC_CHANGED");authorize(actor,target);
        if(!MaintenanceExecutorProtocol.CAPABILITIES.containsAll(body.capabilities())||("CHECKED".equals(body.outcome())&&(!body.processStopped()||!"completed".equals(body.terminalState())||body.clientVersion()==null||body.observedSha()==null||body.buildTree()==null||!Boolean.FALSE.equals(body.buildDirty())||!body.capabilities().contains("sync-check")))||(!body.processStopped()&&!"UNKNOWN".equals(body.outcome())))throw ApiException.conflict("INVALID_SYNC_CHECK_EVIDENCE");
        String encoded=json.canonical(body),hash=AuthService.hash(encoded);
        if(target.get("receipt_hash")!=null){if(!hash.equals(target.get("receipt_hash")))throw ApiException.conflict("RESULT_CONFLICT");return Map.of("accepted",true,"duplicate",true);}
        if(!"CLAIMED".equals(target.get("state"))||((Number)target.get("expires_at")).longValue()<clock.millis()||((Number)target.get("lease_expires_at")).longValue()<clock.millis())throw ApiException.conflict("SYNC_CHECK_EXPIRED");
        db.update("update maintenance_sync_check_targets set state=?,receipt_json=?,receipt_hash=?,stopped_evidence_digest=? where id=?",body.outcome(),encoded,hash,body.processStopped()?body.evidenceDigest():null,targetId);
        return Map.of("accepted",true,"duplicate",false);
    }
}
