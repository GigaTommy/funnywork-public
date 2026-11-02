package com.funnywork;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Generated desktop evidence, never a source-verified business completion. */
@Service
public class ComputerReports {
    public record Report(@Min(1) long epoch,@NotNull @Size(max=100000) String text) {}
    private final JdbcTemplate db;private final TenantLocks locks;private final AuthService auth;
    private final ControlService control;private final ArtifactService artifacts;private final Json json;
    private final Clock clock;private final Validator validator;
    public ComputerReports(JdbcTemplate db,TenantLocks locks,AuthService auth,ControlService control,
            ArtifactService artifacts,Json json,Clock clock,Validator validator){
        this.db=db;this.locks=locks;this.auth=auth;this.control=control;this.artifacts=artifacts;
        this.json=json;this.clock=clock;this.validator=validator;
    }
    @Transactional public Map<String,Object> report(Identity node,String attempt,Report request){
        locks.lock(node.tenant());node=auth.current(node);node.requireNode();
        if(request==null || !validator.validate(request).isEmpty())throw new ApiException(400,"INVALID_COMPUTER_REPORT","Invalid desktop report");
        var lease=control.computerReportLease(node,attempt,request.epoch(),true);
        String digest=AuthService.hash(request.text());
        var prior=db.queryForList("select attempt_id,result_json from unit_results where unit_id=?",lease.unitId());
        if(!prior.isEmpty()){
            var saved=(Map<?,?>)json.read((String)prior.get(0).get("result_json"));
            if(!attempt.equals(prior.get(0).get("attempt_id")) || !digest.equals(saved.get("requestHash")))throw ApiException.conflict("RESULT_CONFLICT");
            return Map.of("accepted",true,"duplicate",true,"artifactId",saved.get("artifactId"),"businessVerified",false);
        }
        control.computerReportLease(node,attempt,request.epoch(),false);
        if(db.queryForObject("select count(*) from computer_controls where tenant_id=? and attempt_id=? and node_id=? and epoch=? and state='RELEASED'",Integer.class,node.tenant(),attempt,node.id(),request.epoch())!=1
            || db.queryForObject("select count(*) from computer_sessions where tenant_id=? and current_attempt=?",Integer.class,node.tenant(),attempt)!=0)
            throw ApiException.conflict("DRIVER_STOP_REQUIRED");
        String policy=db.queryForObject("select policy_json from computer_grants where tenant_id=? and unit_id=?",String.class,node.tenant(),lease.unitId());
        var actions=db.queryForList("select action_id,attempt_id,generation,action_type,signature,state,created_at from computer_actions where tenant_id=? and unit_id=? order by created_at,action_id",node.tenant(),lease.unitId());
        String review=actions.stream().anyMatch(a->"UNKNOWN".equals(a.get("state")))?"RECONCILIATION_REQUIRED":"UNREVIEWED";
        String content=json.write(Map.of("kind","COMPUTER_EXECUTION_REPORT","nodeReport",request.text(),"scope",json.read(policy),
            "actions",actions,"reviewState",review,"businessVerified",false));
        String zone=db.queryForObject("select zone from tasks where id=?",String.class,lease.taskId());
        var created=artifacts.create(lease.owner(),new ArtifactService.Create("computer-execution-report.json",zone,
            Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        String artifact=(String)created.get("artifactId");
        artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifact,1));
        var result=Map.of("kind","COMPUTER_EXECUTION_REPORT","artifactId",artifact,"requestHash",digest,"reviewState",review,"businessVerified",false);
        db.update("insert into unit_results(unit_id,attempt_id,result_json,accepted_at) values(?,?,?,?)",lease.unitId(),attempt,json.write(result),clock.millis());
        db.update("update attempts set state='SUCCEEDED' where id=?",attempt);
        db.update("update work_units set state='GENERATED' where id=?",lease.unitId());
        control.refreshTask(lease.taskId());
        auth.audit(node,"COMPUTER_REPORT_STORED",Map.of("taskId",lease.taskId(),"attemptId",attempt,"artifactId",artifact,"reviewState",review));
        return Map.of("accepted",true,"duplicate",false,"artifactId",artifact,"businessVerified",false);
    }
}
