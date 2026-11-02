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
public class BrowserReconciliation {
    public record Authorization(@NotNull @Valid BrowserExecution.Grant grant,@NotNull @Valid Bind verifier) {}
    public record Bind(@NotBlank @Size(max=120) String verifierId,@NotBlank @Size(max=200) String businessKey) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    private final BrowserExecution browser;private final BrowserVerifiers verifiers;private final ControlService control;private final Json json;private final Clock clock;private final TransactionTemplate tx;
    public BrowserReconciliation(JdbcTemplate db,AuthService auth,TenantLocks locks,BrowserVerifiers verifiers,ControlService control,BrowserExecution browser,Json json,Clock clock,PlatformTransactionManager manager) {
        this.db=db;this.auth=auth;this.locks=locks;this.verifiers=verifiers;this.control=control;this.browser=browser;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public void authorize(Identity admin,String unit,Authorization request) {
        tx.executeWithoutResult(status->{
            locks.lock(admin.tenant());auth.current(admin).requireUser("ADMIN");
            var rows=db.queryForList("select task_id from work_units where tenant_id=? and id=?",admin.tenant(),unit);
            if(rows.isEmpty())throw ApiException.missing();
            browser.grant(admin,(String)rows.get(0).get("task_id"),unit,request.grant());
            bind(admin,unit,request.verifier());
        });
    }
    private Map<String,Object> unit(Identity actor,String id) {
        var rows=db.queryForList("select u.*,t.zone,t.state as task_state,g.policy_json from work_units u join tasks t on t.id=u.task_id join browser_grants g on g.unit_id=u.id where u.id=? and u.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));return row;
    }
    private String origin(Map<String,Object> unit){return (String)((Map<?,?>)json.read((String)unit.get("policy_json"))).get("origin");}
    public void bind(Identity actor,String id,Bind request) {
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());var admin=auth.current(actor);admin.requireUser("ADMIN");var row=unit(admin,id);
            verifiers.resolve(admin.tenant(),request.verifierId(),origin(row));
            var prior=db.queryForList("select verifier_id,business_key from browser_verification_bindings where unit_id=?",id);
            if(!prior.isEmpty()) {
                if(request.verifierId().equals(prior.get(0).get("verifier_id")) && request.businessKey().equals(prior.get(0).get("business_key")))return;
                throw ApiException.conflict("VERIFIER_BINDING_IMMUTABLE");
            }
            if(!"READY".equals(row.get("state")) || ((Number)row.get("attempts")).intValue()!=0 || !"RUNNING".equals(row.get("task_state")))throw ApiException.conflict("UNIT_ALREADY_STARTED");
            String proof=AuthService.hash(json.write(List.of(id,request.verifierId(),request.businessKey(),row.get("policy_json"))));
            if(db.queryForObject("select count(*) from browser_verification_bindings where tenant_id=? and verifier_id=? and key_hash=?",Integer.class,admin.tenant(),request.verifierId(),AuthService.hash(request.businessKey()))>0)throw ApiException.conflict("BUSINESS_KEY_ALREADY_BOUND");
            db.update("insert into browser_verification_bindings values(?,?,?,?,?,?)",admin.tenant(),id,request.verifierId(),request.businessKey(),AuthService.hash(request.businessKey()),proof);
            auth.audit(admin,"BROWSER_VERIFIER_BOUND",Map.of("unitId",id,"verifierId",request.verifierId()));
        });
    }
    public void grant(Identity actor,String unit,String principal,boolean enabled) {
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());var admin=auth.current(actor);admin.requireUser("ADMIN");var row=unit(admin,unit);
            if(enabled) {
                var people=db.queryForList("select roles,zones from principals where tenant_id=? and id=? and enabled=true and kind='USER'",admin.tenant(),principal);
                if(people.isEmpty() || !json.strings((String)people.get(0).get("roles")).contains("RECONCILER") || !json.strings((String)people.get(0).get("zones")).contains(row.get("zone")))throw ApiException.forbidden();
                if(db.queryForObject("select count(*) from browser_reviewers where unit_id=? and principal_id=?",Integer.class,unit,principal)==0)db.update("insert into browser_reviewers values(?,?,?)",admin.tenant(),unit,principal);
            } else db.update("delete from browser_reviewers where unit_id=? and principal_id=?",unit,principal);
            auth.audit(admin,enabled?"BROWSER_REVIEW_GRANTED":"BROWSER_REVIEW_REVOKED",Map.of("unitId",unit,"principalId",principal));
        });
    }
    private Map<String,Object> authorized(Identity actor,String id) {
        actor=auth.current(actor);actor.requireUser("RECONCILER");var row=unit(actor,id);
        if(db.queryForObject("select count(*) from browser_reviewers where unit_id=? and principal_id=?",Integer.class,id,actor.id())!=1)throw ApiException.forbidden();
        return row;
    }
    private Map<String,Object> saved(String unit) {
        var rows=db.queryForList("select result_json from browser_reconciliations where unit_id=?",unit);
        return rows.isEmpty()?null:(Map<String,Object>)json.read((String)rows.get(0).get("result_json"));
    }
    private record Pending(Map<String,Object> saved,BrowserVerifiers.Verifier verifier,BrowserVerifiers.Query query) {}
    public Map<String,Object> reconcile(Identity reviewer,String id) {
        Pending pending=tx.execute(status->{
            locks.lock(reviewer.tenant());var row=authorized(reviewer,id);var prior=saved(id);
            if(prior!=null)return new Pending(prior,null,null);
            if(!Set.of("RECONCILING","BLOCKED_AUTH","CANCELLED","FAILED").contains(row.get("task_state")) || Set.of("READY","RESERVED","RUNNING").contains(row.get("state")))throw ApiException.conflict("BROWSER_UNIT_STILL_ACTIVE");
            if(db.queryForObject("select count(*) from browser_actions where unit_id=?",Integer.class,id)==0)throw ApiException.conflict("NO_BROWSER_ACTIONS");
            var bindings=db.queryForList("select * from browser_verification_bindings where unit_id=?",id);
            if(bindings.isEmpty())throw ApiException.missing();var b=bindings.get(0);
            return new Pending(null,verifiers.resolve(reviewer.tenant(),(String)b.get("verifier_id"),origin(row)),new BrowserVerifiers.Query(id,(String)b.get("business_key"),(String)b.get("proof_hash"),(String)row.get("policy_json")));
        });
        if(pending.saved()!=null)return pending.saved();
        // Trusted lookup may perform IO; never hold a database transaction during the call.
        var evidence=pending.verifier().lookup(pending.query());
        return tx.execute(status->{
            locks.lock(reviewer.tenant());var row=authorized(reviewer,id);var prior=saved(id);if(prior!=null)return prior;
            if(evidence==null || evidence.outcome()==null || evidence.outcome()==BrowserVerifiers.Outcome.UNKNOWN || !pending.query().proofHash().equals(evidence.proofHash()) || evidence.receipt()==null || evidence.receipt().isBlank() || evidence.receipt().length()>200)
                return Map.of("unitId",id,"state","UNKNOWN","businessVerified",false);
            var result=Map.<String,Object>of("unitId",id,"state",evidence.outcome().name(),"receipt",evidence.receipt(),"businessVerified",true);
            db.update("insert into browser_reconciliations values(?,?,?,?,?)",reviewer.tenant(),id,reviewer.id(),json.write(result),clock.millis());
            db.update("update work_units set state=? where id=?",evidence.outcome()==BrowserVerifiers.Outcome.CONFIRMED?"SUCCEEDED":"FAILED",id);
            String task=(String)row.get("task_id");
            if(db.queryForObject("select count(*) from work_units where task_id=? and state in ('READY','RESERVED','RUNNING')",Integer.class,task)==0 && !control.hasUnresolvedOperation(task))db.update("update tasks set state='RECONCILED' where id=?",task);
            auth.audit(reviewer,"BROWSER_RESULT_RECONCILED",Map.of("unitId",id,"state",evidence.outcome().name()));return result;
        });
    }
}
