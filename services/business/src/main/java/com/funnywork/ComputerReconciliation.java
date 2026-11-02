package com.funnywork;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

@Service
public class ComputerReconciliation {
    public record Authorization(@NotNull @Valid ComputerGrants.Grant grant,@NotNull @Valid Bind verifier) {}
    public record Bind(@NotBlank @Size(max=120) String verifierId,@NotBlank @Size(max=200) String businessKey) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    private final ComputerGrants computer;private final ComputerVerifiers verifiers;private final ControlService control;private final Json json;private final Clock clock;private final TransactionTemplate tx;
    public ComputerReconciliation(JdbcTemplate db,AuthService auth,TenantLocks locks,ComputerVerifiers verifiers,ControlService control,ComputerGrants computer,Json json,Clock clock,PlatformTransactionManager manager) {
        this.db=db;this.auth=auth;this.locks=locks;this.verifiers=verifiers;this.control=control;this.computer=computer;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public void authorize(Identity admin,String unit,Authorization request) {
        tx.executeWithoutResult(status->{
            locks.lock(admin.tenant());auth.current(admin).requireUser("ADMIN");
            var rows=db.queryForList("select task_id from work_units where tenant_id=? and id=?",admin.tenant(),unit);
            if(rows.isEmpty())throw ApiException.missing();
            computer.grant(admin,(String)rows.get(0).get("task_id"),unit,request.grant());
            bind(admin,unit,request.verifier());
        });
    }
    private Map<String,Object> unit(Identity actor,String id) {
        var rows=db.queryForList("select u.*,t.zone,t.state as task_state,g.policy_json from work_units u join tasks t on t.id=u.task_id join computer_grants g on g.unit_id=u.id where u.id=? and u.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));return row;
    }
    private String zone(Map<String,Object> unit){return (String)unit.get("zone");}
    public void bind(Identity actor,String id,Bind request) {
        if(request==null || request.verifierId()==null || request.verifierId().isBlank() || request.verifierId().length()>120
            || request.businessKey()==null || request.businessKey().isBlank() || request.businessKey().length()>200)
            throw new ApiException(400,"INVALID_VERIFIER_BINDING","Invalid desktop verifier binding");
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());var admin=auth.current(actor);admin.requireUser("ADMIN");var row=unit(admin,id);
            verifiers.resolve(admin.tenant(),request.verifierId(),zone(row));
            var prior=db.queryForList("select verifier_id,business_key from computer_verification_bindings where unit_id=?",id);
            if(!prior.isEmpty()) {
                if(request.verifierId().equals(prior.get(0).get("verifier_id")) && request.businessKey().equals(prior.get(0).get("business_key")))return;
                throw ApiException.conflict("VERIFIER_BINDING_IMMUTABLE");
            }
            if(!"READY".equals(row.get("state")) || ((Number)row.get("attempts")).intValue()!=0 || !"RUNNING".equals(row.get("task_state")))throw ApiException.conflict("UNIT_ALREADY_STARTED");
            String proof=AuthService.hash(json.write(List.of(id,request.verifierId(),request.businessKey(),row.get("policy_json"))));
            if(db.queryForObject("select count(*) from computer_verification_bindings where tenant_id=? and verifier_id=? and key_hash=?",Integer.class,admin.tenant(),request.verifierId(),AuthService.hash(request.businessKey()))>0)throw ApiException.conflict("BUSINESS_KEY_ALREADY_BOUND");
            db.update("insert into computer_verification_bindings values(?,?,?,?,?,?)",admin.tenant(),id,request.verifierId(),request.businessKey(),AuthService.hash(request.businessKey()),proof);
            auth.audit(admin,"COMPUTER_VERIFIER_BOUND",Map.of("unitId",id,"verifierId",request.verifierId()));
        });
    }
    public void grant(Identity actor,String unit,String principal,boolean enabled) {
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());var admin=auth.current(actor);admin.requireUser("ADMIN");var row=unit(admin,unit);
            if(enabled) {
                var people=db.queryForList("select roles,zones from principals where tenant_id=? and id=? and enabled=true and kind='USER'",admin.tenant(),principal);
                if(people.isEmpty() || !json.strings((String)people.get(0).get("roles")).contains("RECONCILER") || !json.strings((String)people.get(0).get("zones")).contains(row.get("zone")))throw ApiException.forbidden();
                if(db.queryForObject("select count(*) from computer_reviewers where unit_id=? and principal_id=?",Integer.class,unit,principal)==0)db.update("insert into computer_reviewers values(?,?,?)",admin.tenant(),unit,principal);
            } else db.update("delete from computer_reviewers where unit_id=? and principal_id=?",unit,principal);
            auth.audit(admin,enabled?"COMPUTER_REVIEW_GRANTED":"COMPUTER_REVIEW_REVOKED",Map.of("unitId",unit,"principalId",principal));
        });
    }
    private Map<String,Object> authorized(Identity actor,String id) {
        actor=auth.current(actor);actor.requireUser("RECONCILER");var row=unit(actor,id);
        if(db.queryForObject("select count(*) from computer_reviewers where unit_id=? and principal_id=?",Integer.class,id,actor.id())!=1)throw ApiException.forbidden();
        return row;
    }
    private Map<String,Object> saved(String unit) {
        var rows=db.queryForList("select result_json from computer_reconciliations where unit_id=?",unit);
        return rows.isEmpty()?null:(Map<String,Object>)json.read((String)rows.get(0).get("result_json"));
    }
    private void stopped(Map<String,Object> row,String id) {
        if(!Set.of("RECONCILING","BLOCKED_AUTH","CANCELLED","FAILED").contains(row.get("task_state")) || Set.of("READY","RESERVED","RUNNING").contains(row.get("state")))throw ApiException.conflict("COMPUTER_UNIT_STILL_ACTIVE");
        if(db.queryForObject("select count(*) from computer_controls c join attempts a on a.id=c.attempt_id where a.unit_id=? and c.state='ACTIVE'",Integer.class,id)>0)
            throw ApiException.conflict("DRIVER_STOP_REQUIRED");
    }
    private record Pending(Map<String,Object> saved,ComputerVerifiers.Verifier verifier,ComputerVerifiers.Query query) {}
    public Map<String,Object> reconcile(Identity reviewer,String id) {
        Pending pending=tx.execute(status->{
            locks.lock(reviewer.tenant());var row=authorized(reviewer,id);var prior=saved(id);
            if(prior!=null)return new Pending(prior,null,null);
            stopped(row,id);
            if(db.queryForObject("select count(*) from computer_actions where unit_id=?",Integer.class,id)==0)throw ApiException.conflict("NO_COMPUTER_ACTIONS");
            var bindings=db.queryForList("select * from computer_verification_bindings where unit_id=?",id);
            if(bindings.isEmpty())throw ApiException.missing();var b=bindings.get(0);
            return new Pending(null,verifiers.resolve(reviewer.tenant(),(String)b.get("verifier_id"),zone(row)),new ComputerVerifiers.Query(id,(String)b.get("business_key"),(String)b.get("proof_hash"),(String)row.get("policy_json")));
        });
        if(pending.saved()!=null)return pending.saved();
        // Trusted lookup may perform IO; never hold a database transaction during the call.
        var evidence=pending.verifier().lookup(pending.query());
        return tx.execute(status->{
            locks.lock(reviewer.tenant());var row=authorized(reviewer,id);var prior=saved(id);if(prior!=null)return prior;
            stopped(row,id);
            if(evidence==null || evidence.outcome()==null || evidence.outcome()==ComputerVerifiers.Outcome.UNKNOWN || !pending.query().proofHash().equals(evidence.proofHash()) || evidence.receipt()==null || evidence.receipt().isBlank() || evidence.receipt().length()>200)
                return Map.of("unitId",id,"state","UNKNOWN","businessVerified",false);
            var result=Map.<String,Object>of("unitId",id,"state",evidence.outcome().name(),"receipt",evidence.receipt(),"businessVerified",true);
            db.update("insert into computer_reconciliations values(?,?,?,?,?)",reviewer.tenant(),id,reviewer.id(),json.write(result),clock.millis());
            db.update("update work_units set state=? where id=?",evidence.outcome()==ComputerVerifiers.Outcome.CONFIRMED?"SUCCEEDED":"FAILED",id);
            String task=(String)row.get("task_id");
            if(db.queryForObject("select count(*) from work_units where task_id=? and state in ('READY','RESERVED','RUNNING')",Integer.class,task)==0 && !control.hasUnresolvedOperation(task))db.update("update tasks set state='RECONCILED' where id=?",task);
            auth.audit(reviewer,"COMPUTER_RESULT_RECONCILED",Map.of("unitId",id,"state",evidence.outcome().name()));return result;
        });
    }
}
