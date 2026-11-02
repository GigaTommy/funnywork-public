package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Administrator-scoped browser units and durable pre-dispatch claims; receipts are not business verification. */
@Service
public class BrowserExecution {
    public record Report(@Min(1) long epoch,@NotNull @Size(max=100000) String text) {}
    public record Action(@Min(1) long epoch,@NotBlank @Size(max=100) String actionId,
        @NotNull @Pattern(regexp="click|fill|upload") String action,@NotNull @Pattern(regexp="[a-f0-9]{64}") String signature) {}
    public record Grant(@NotBlank @Size(max=300) String origin,
        @NotEmpty Set<@Pattern(regexp="click|fill|upload") String> actions,
        @Min(1) @Max(128) int maxActions,@Size(max=64) String artifactId,@Min(1) Integer artifactVersion) {}
    private final JdbcTemplate db;private final TenantLocks locks;private final AuthService auth;
    private final ControlService control;private final ArtifactService artifacts;private final Json json;private final Clock clock;
    public BrowserExecution(JdbcTemplate db,TenantLocks locks,AuthService auth,ControlService control,ArtifactService artifacts,Json json,Clock clock){this.db=db;this.locks=locks;this.auth=auth;this.control=control;this.artifacts=artifacts;this.json=json;this.clock=clock;}
    static String origin(String value) {
        try {
            var uri=URI.create(value);
            if(uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) || uri.getPort()>65535 || uri.getPort()==0
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("127.0.0.1","[::1]").contains(uri.getHost()))))throw new IllegalArgumentException();
            int port=uri.getPort();if(port==("https".equals(uri.getScheme())?443:80))port=-1;
            return new URI(uri.getScheme(),null,uri.getHost().toLowerCase(Locale.ROOT),port,null,null,null).toString();
        } catch(Exception invalid){throw new ApiException(400,"INVALID_ORIGIN","A fixed HTTPS origin is required");}
    }
    private Map<String,Object> unit(Identity admin,String task,String unit) {
        var rows=db.queryForList("select u.*,t.zone,t.state as task_state,v.capability from work_units u join tasks t on t.id=u.task_id join agent_versions v on v.id=t.agent_id where u.id=? and u.task_id=? and u.tenant_id=?",unit,task,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);admin.requireZone((String)row.get("zone"));
        if(!"browser.write.v1".equals(row.get("capability")))throw ApiException.conflict("CAPABILITY_MISMATCH");return row;
    }
    @Transactional public void grant(Identity admin,String task,String unit,Grant request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");var row=unit(admin,task,unit);
        if(request.actions()==null || request.actions().isEmpty() || !Set.of("click","fill","upload").containsAll(request.actions()) || request.maxActions()<1 || request.maxActions()>128)throw new ApiException(400,"INVALID_BROWSER_POLICY","Invalid action scope");
        Map<String,Object> policy=new TreeMap<>();policy.put("origin",origin(request.origin()));policy.put("actions",new TreeSet<>(request.actions()));policy.put("maxActions",request.maxActions());
        if(request.actions().contains("upload")) {
            if(request.artifactId()==null || request.artifactVersion()==null || request.artifactVersion()<1 || db.queryForObject("select count(*) from task_artifacts where tenant_id=? and task_id=? and artifact_id=? and version=?",Integer.class,admin.tenant(),task,request.artifactId(),request.artifactVersion())!=1)throw ApiException.missing();
            policy.put("artifactId",request.artifactId());policy.put("artifactVersion",request.artifactVersion());
        } else if(request.artifactId()!=null || request.artifactVersion()!=null)throw new ApiException(400,"INVALID_BROWSER_POLICY","File scope requires upload permission");
        String encoded=json.write(policy);var prior=db.queryForList("select policy_json,enabled from browser_grants where unit_id=?",unit);
        if(!prior.isEmpty()) {
            if(encoded.equals(prior.get(0).get("policy_json")) && Boolean.TRUE.equals(prior.get(0).get("enabled")))return;
            throw ApiException.conflict("BROWSER_POLICY_IMMUTABLE");
        }
        if(!"RUNNING".equals(row.get("task_state")) || !"READY".equals(row.get("state")) || ((Number)row.get("attempts")).intValue()!=0)throw ApiException.conflict("UNIT_ALREADY_STARTED");
        db.update("insert into browser_grants values(?,?,?,true)",admin.tenant(),unit,encoded);
        db.update("update work_units set next_dispatch_at=? where id=?",clock.millis(),unit);
        auth.audit(admin,"BROWSER_UNIT_GRANTED",Map.of("taskId",task,"unitId",unit,"policy",policy));
    }
    @Transactional public void revoke(Identity admin,String task,String unit) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");unit(admin,task,unit);
        if(db.update("update browser_grants set enabled=false where unit_id=? and enabled=true",unit)>0)
            auth.audit(admin,"BROWSER_UNIT_REVOKED",Map.of("taskId",task,"unitId",unit));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> policy(ControlService.ModelLease lease,String tenant) {
        var rows=db.queryForList("select policy_json from browser_grants where unit_id=? and tenant_id=? and enabled=true",lease.unitId(),tenant);
        if(rows.isEmpty())throw ApiException.forbidden();return (Map<String,Object>)json.read((String)rows.get(0).get("policy_json"));
    }
    @Transactional public Map<String,Object> scope(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,epoch);
        return policy(lease,node.tenant());
    }
    private Map<String,Object> receipt(String action,String state,boolean execute) {
        return Map.of("actionId",action,"status",state,"shouldExecute",execute,"businessVerified",false);
    }
    @Transactional public Map<String,Object> lookup(Identity node,String attempt,Action action) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,action.epoch());var scope=policy(lease,node.tenant());
        if(!((List<?>)scope.get("actions")).contains(action.action()))throw ApiException.forbidden();
        var rows=db.queryForList("select action_type,signature,state from browser_actions where unit_id=? and action_id=?",lease.unitId(),action.actionId());
        if(rows.isEmpty())return receipt(action.actionId(),"NOT_FOUND",false);
        var row=rows.get(0);
        if(!action.action().equals(row.get("action_type")) || !action.signature().equals(row.get("signature")))throw ApiException.conflict("ACTION_ID_CONFLICT");
        return receipt(action.actionId(),(String)row.get("state"),false);
    }
    @Transactional public Map<String,Object> claim(Identity node,String attempt,Action action) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,action.epoch());var scope=policy(lease,node.tenant());
        if(!((List<?>)scope.get("actions")).contains(action.action()))throw ApiException.forbidden();
        var prior=db.queryForList("select * from browser_actions where unit_id=? and action_id=?",lease.unitId(),action.actionId());
        if(!prior.isEmpty()) {
            var row=prior.get(0);
            if(!action.signature().equals(row.get("signature")) || !action.action().equals(row.get("action_type")))throw ApiException.conflict("ACTION_ID_CONFLICT");
            return receipt(action.actionId(),(String)row.get("state"),false);
        }
        if(db.queryForObject("select count(*) from browser_actions where unit_id=? and (attempt_id<>? or state='UNKNOWN')",Integer.class,lease.unitId(),attempt)>0)
            throw ApiException.conflict("BROWSER_RECOVERY_REQUIRED");
        if(db.queryForObject("select count(*) from browser_actions where unit_id=?",Integer.class,lease.unitId())>=((Number)scope.get("maxActions")).intValue())throw ApiException.conflict("ACTION_BUDGET_EXCEEDED");
        db.update("insert into browser_actions values(?,?,?,?,?,?,'UNKNOWN',?)",node.tenant(),lease.unitId(),action.actionId(),attempt,action.action(),action.signature(),clock.millis());
        auth.audit(node,"BROWSER_ACTION_CLAIMED",Map.of("unitId",lease.unitId(),"actionId",action.actionId(),"action",action.action()));
        return receipt(action.actionId(),"UNKNOWN",true);
    }
    @Transactional public Map<String,Object> dispatched(Identity node,String attempt,Action action) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,action.epoch());policy(lease,node.tenant());
        var rows=db.queryForList("select * from browser_actions where unit_id=? and action_id=?",lease.unitId(),action.actionId());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        if(!attempt.equals(row.get("attempt_id")) || !action.action().equals(row.get("action_type")) || !action.signature().equals(row.get("signature")))throw ApiException.conflict("ACTION_ID_CONFLICT");
        if(db.update("update browser_actions set state='DISPATCHED' where unit_id=? and action_id=? and state='UNKNOWN'",lease.unitId(),action.actionId())>0)
            auth.audit(node,"BROWSER_ACTION_DISPATCHED",Map.of("unitId",lease.unitId(),"actionId",action.actionId()));
        return receipt(action.actionId(),"DISPATCHED",false);
    }
    @Transactional public Map<String,Object> attachment(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,epoch);var scope=policy(lease,node.tenant());
        if(!scope.containsKey("artifactId"))throw ApiException.forbidden();
        String artifact=(String)scope.get("artifactId");int version=((Number)scope.get("artifactVersion")).intValue();
        if(db.queryForObject("select count(*) from task_artifacts where tenant_id=? and task_id=? and artifact_id=? and version=?",Integer.class,node.tenant(),lease.taskId(),artifact,version)!=1)throw ApiException.missing();
        return artifacts.read(lease.owner(),artifact,version);
    }
    @Transactional public Map<String,Object> report(Identity node,String attempt,Report request) {
        locks.lock(node.tenant());var lease=control.browserLease(node,attempt,request.epoch(),true);var scope=policy(lease,node.tenant());
        String digest=AuthService.hash(request.text());
        var previous=db.queryForList("select attempt_id,result_json from unit_results where unit_id=?",lease.unitId());
        if(!previous.isEmpty()) {
            var saved=(Map<?,?>)json.read((String)previous.get(0).get("result_json"));
            if(!attempt.equals(previous.get(0).get("attempt_id")) || !digest.equals(saved.get("requestHash")))throw ApiException.conflict("RESULT_CONFLICT");
            return Map.of("accepted",true,"duplicate",true,"artifactId",saved.get("artifactId"),"businessVerified",false);
        }
        control.browserLease(node,attempt,request.epoch());
        var actions=db.queryForList("select action_id,attempt_id,action_type,signature,state,created_at from browser_actions where unit_id=? order by created_at,action_id",lease.unitId());
        var content=json.write(Map.of("kind","BROWSER_EXECUTION_REPORT","nodeReport",request.text(),"scope",scope,"actions",actions,"businessVerified",false));
        String zone=db.queryForObject("select zone from tasks where id=?",String.class,lease.taskId());
        var created=artifacts.create(lease.owner(),new ArtifactService.Create("browser-execution-report.json",zone,Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        String artifact=(String)created.get("artifactId");
        artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifact,1));
        var result=Map.of("kind","BROWSER_EXECUTION_REPORT","artifactId",artifact,"requestHash",digest,"reviewState","UNREVIEWED","businessVerified",false);
        db.update("insert into unit_results(unit_id,attempt_id,result_json,accepted_at) values(?,?,?,?)",lease.unitId(),attempt,json.write(result),clock.millis());
        db.update("update attempts set state='SUCCEEDED' where id=?",attempt);
        db.update("update work_units set state='GENERATED' where id=?",lease.unitId());
        control.refreshTask(lease.taskId());
        db.update("update work_units set next_dispatch_at=? where id in (select u.id from work_units u join tasks t on t.id=u.task_id where t.tenant_id=? and t.zone=? and t.state='RUNNING' and u.state='READY' and u.next_dispatch_at>? order by t.created_at,t.id,u.ordinal limit 64)",clock.millis(),node.tenant(),zone,clock.millis());
        auth.audit(node,"BROWSER_REPORT_STORED",Map.of("taskId",lease.taskId(),"attemptId",attempt,"artifactId",artifact));
        return Map.of("accepted",true,"duplicate",false,"artifactId",artifact,"businessVerified",false);
    }
}
