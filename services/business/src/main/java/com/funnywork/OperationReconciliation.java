package com.funnywork;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

/** Explicit organization lookup authority, never source dispatch authority. */
@Service
public class OperationReconciliation {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final ToolRegistry tools;
    private final GatewayRegistry gateways;
    private final ControlService control;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate tx;
    public OperationReconciliation(JdbcTemplate db,AuthService auth,TenantLocks locks,ToolRegistry tools,GatewayRegistry gateways,
                                   ControlService control,Json json,Clock clock,PlatformTransactionManager manager) {
        this.db=db;this.auth=auth;this.locks=locks;this.tools=tools;this.gateways=gateways;
        this.control=control;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    private Map<String,Object> operation(Identity actor,String id) {
        var rows=db.queryForList("select o.*,t.zone,t.state as task_state from external_operations o join tasks t on t.id=o.task_id where o.id=? and o.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();
        actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);
    }
    public void grant(Identity admin,String id,String reviewer,boolean enable) {
        tx.executeWithoutResult(status->{
            locks.lock(admin.tenant());Identity live=auth.current(admin);live.requireUser("ADMIN");
            var operation=operation(live,id);
            var users=db.queryForList("select * from principals where id=? and tenant_id=?",reviewer,live.tenant());
            if(users.isEmpty())throw ApiException.missing();
            var user=users.get(0);
            if(enable && (!Boolean.TRUE.equals(user.get("enabled")) || !"USER".equals(user.get("kind")) ||
                !json.strings((String)user.get("roles")).contains("RECONCILER") ||
                !json.strings((String)user.get("zones")).contains(operation.get("zone"))))throw ApiException.forbidden();
            db.update("delete from operation_reconcile_grants where operation_id=? and principal_id=?",id,reviewer);
            if(enable)db.update("insert into operation_reconcile_grants values(?,?,?)",live.tenant(),id,reviewer);
            auth.audit(live,enable?"RECONCILE_GRANTED":"RECONCILE_REVOKED",Map.of("operationId",id,"reviewerId",reviewer));
        });
    }
    private Map<String,Object> authorized(Identity actor,String id) {
        actor=auth.current(actor);actor.requireUser("RECONCILER");var operation=operation(actor,id);
        if(db.queryForObject("select count(*) from operation_reconcile_grants where tenant_id=? and operation_id=? and principal_id=?",Integer.class,actor.tenant(),id,actor.id())!=1)
            throw ApiException.forbidden();
        return operation;
    }
    private Map<String,Object> summary(Map<String,Object> operation) {
        return Map.of("operationId",operation.get("id"),"taskId",operation.get("task_id"),
            "state",operation.get("state"),"zone",operation.get("zone"));
    }
    public Map<String,Object> list(Identity actor,String after,int limit,boolean includeResolved) {
        return list(actor,after,limit,includeResolved,false);
    }
    private Map<String,Object> list(Identity actor,String after,int limit,boolean includeResolved,boolean dueOnly) {
        if(limit<1 || limit>100 || after==null || !after.matches("[a-zA-Z0-9-]{0,64}"))
            throw new ApiException(400,"INVALID_CURSOR","Invalid reconciliation page");
        return tx.execute(status->{
            locks.lock(actor.tenant());Identity live=auth.current(actor);live.requireUser("RECONCILER");
            if(live.zones().isEmpty())return Map.of("items",List.of(),"nextCursor","");
            List<Object> arguments=new ArrayList<>(List.of(live.tenant(),live.id(),after));
            arguments.addAll(live.zones());
            if(dueOnly){arguments.add(clock.millis());arguments.add(clock.millis());}
            arguments.add(limit+1);
            String zones=String.join(",",Collections.nCopies(live.zones().size(),"?"));
            String pending=includeResolved?"":" and (o.state in ('DISPATCHED','UNKNOWN') or (o.state in ('CONFIRMED','REJECTED') and exists(select 1 from unit_operations b where b.operation_id=o.id and not exists(select 1 from unit_results r where r.unit_id=b.unit_id) and not exists(select 1 from unit_reconciliations c where c.unit_id=b.unit_id))))";
            String due=dueOnly?" and (o.state in ('DISPATCHED','UNKNOWN') or t.state in ('RECONCILING','BLOCKED_AUTH','CANCELLED','FAILED')) and not exists(select 1 from reconciliation_runs rr where rr.operation_id=o.id and (rr.next_at>? or rr.expires_at>?))":"";
            var rows=db.queryForList("select o.id,o.task_id,o.state,t.zone from operation_reconcile_grants g join external_operations o on o.id=g.operation_id join tasks t on t.id=o.task_id where g.tenant_id=? and g.principal_id=? and o.id>? and t.zone in ("+zones+")"+pending+due+" order by o.id limit ?",arguments.toArray());
            boolean more=rows.size()>limit;
            var page=rows.subList(0,Math.min(rows.size(),limit));
            return Map.of("items",page.stream().map(this::summary).toList(),"nextCursor",more?(String)page.get(page.size()-1).get("id"):"");
        });
    }
    public Map<String,Object> read(Identity actor,String id) {return summary(authorized(actor,id));}
    @SuppressWarnings("unchecked")
    public Map<String,Object> scan(Identity actor,int limit) {
        if(limit<1 || limit>10)throw new ApiException(400,"INVALID_SCAN_LIMIT","Scan must contain 1 to 10 operations");
        var page=list(actor,"",limit,false,true);
        List<Map<String,Object>> results=new ArrayList<>();
        for(var item:(List<Map<String,Object>>)page.get("items")) {
            String id=(String)item.get("operationId");
            try {results.add(reconcile(actor,id));}
            catch(ApiException error) {
                if(error.status==401 || error.status==403){auth.current(actor).requireUser("RECONCILER");}
                results.add(Map.of("operationId",id,"code",error.code));
            }
        }
        return Map.of("items",results);
    }
    private long claim(Identity actor,String id) {
        return tx.execute(status->{
            locks.lock(actor.tenant());authorized(actor,id);
            var rows=db.queryForList("select * from reconciliation_runs where operation_id=?",id);
            long generation=1;
            if(rows.isEmpty())db.update("insert into reconciliation_runs values(?,?,1,?,0,0)",actor.tenant(),id,clock.millis()+90000);
            else {
                var row=rows.get(0);
                if(((Number)row.get("expires_at")).longValue()>clock.millis() || ((Number)row.get("next_at")).longValue()>clock.millis())
                    throw new ApiException(429,"RECONCILE_BACKOFF","Operation is already being queried or waiting to retry");
                generation=((Number)row.get("generation")).longValue()+1;
                db.update("update reconciliation_runs set generation=?,expires_at=? where operation_id=?",generation,clock.millis()+90000,id);
            }
            return generation;
        });
    }
    private void release(Identity actor,String id,long generation,boolean resolved) {
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());
            var rows=db.queryForList("select failures from reconciliation_runs where operation_id=? and generation=?",id,generation);
            if(rows.isEmpty())return;
            int failures=resolved?0:Math.min(10,((Number)rows.get(0).get("failures")).intValue()+1);
            long delay=resolved?2000:Math.min(300000,1000L*(1L<<failures));
            db.update("update reconciliation_runs set expires_at=0,next_at=?,failures=? where operation_id=? and generation=?",clock.millis()+delay,failures,id,generation);
        });
    }
    public Map<String,Object> reconcile(Identity actor,String id) {
        long generation=0;
        boolean resolved=false;
        try {
            // Cache-only terminal receipts need no external-query lease.
            var current=authorized(actor,id);
            if(Set.of("DISPATCHED","UNKNOWN").contains(current.get("state")))generation=claim(actor,id);
            var result=reconcileClaimed(actor,id,generation);
            resolved=Set.of("CONFIRMED","REJECTED").contains(result.get("state"));
            return result;
        } finally {
            if(generation>0)release(actor,id,generation,resolved);
        }
    }
    private Map<String,Object> reconcileClaimed(Identity actor,String id,long generation) {
        var before=tx.execute(status->{locks.lock(actor.tenant());return authorized(actor,id);});
        String state=(String)before.get("state");
        if(!Set.of("DISPATCHED","UNKNOWN","CONFIRMED","REJECTED").contains(state))throw ApiException.conflict("NOT_RECONCILABLE");
        var contract=tools.reconciliationVersion(actor,(String)before.get("tool_version_id"),(String)before.get("zone")).contract();
        ExternalOperations.Evidence evidence;
        if(Set.of("CONFIRMED","REJECTED").contains(state))evidence=new ExternalOperations.Evidence(ExternalOperations.Outcome.valueOf(state),(String)before.get("receipt_ref"));
        else {
            var gateway=gateways.resolve(actor.tenant(),(String)before.get("gateway_binding_id"),contract,(String)before.get("zone"));
            try {evidence=gateway.lookup(id,(String)before.get("input_hash"));if(evidence==null)evidence=new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);}
            catch(Exception failure){evidence=new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);}
        }
        var verified=evidence;
        return tx.execute(status->{
            locks.lock(actor.tenant());var current=authorized(actor,id);
            if(generation>0 && db.queryForObject("select count(*) from reconciliation_runs where operation_id=? and generation=? and expires_at>?",Integer.class,id,generation,clock.millis())!=1)
                throw ApiException.conflict("STALE_RECONCILIATION");
            tools.reconciliationVersion(actor,(String)current.get("tool_version_id"),(String)current.get("zone"));
            if(Set.of("DISPATCHED","UNKNOWN").contains(current.get("state"))) {
                db.update("update external_operations set state=?,receipt_ref=?,updated_at=? where id=?",verified.outcome().name(),verified.receipt(),clock.millis(),id);
                current=operation(actor,id);
            }
            settle(actor,current);
            auth.audit(actor,"ORGANIZATION_RECONCILED",Map.of("operationId",id,"state",current.get("state")));
            return summary(current);
        });
    }
    private void settle(Identity actor,Map<String,Object> operation) {
        String outcome=(String)operation.get("state"),task=(String)operation.get("task_id");
        if(!Set.of("CONFIRMED","REJECTED").contains(outcome) || !Set.of("RECONCILING","BLOCKED_AUTH","CANCELLED","FAILED").contains(operation.get("task_state")))return;
        var units=db.queryForList("select u.id,u.state from unit_operations b join work_units u on u.id=b.unit_id where b.operation_id=?",operation.get("id"));
        for(var unit:units) {
            String id=(String)unit.get("id");
            if(Set.of("READY","RESERVED","RUNNING").contains(unit.get("state")))continue;
            if(db.queryForObject("select count(*) from unit_results where unit_id=?",Integer.class,id)>0)continue;
            if(db.queryForObject("select count(*) from unit_reconciliations where unit_id=?",Integer.class,id)==0) {
                var result=Map.of("operationId",operation.get("id"),"state",outcome,"reconciled",true);
                db.update("insert into unit_reconciliations values(?,?,?,?,?,?)",actor.tenant(),id,operation.get("id"),actor.id(),json.write(result),clock.millis());
                db.update("update work_units set state=? where id=?",outcome.equals("CONFIRMED")?"SUCCEEDED":"FAILED",id);
            }
        }
        int active=db.queryForObject("select count(*) from work_units where task_id=? and state in ('READY','RESERVED','RUNNING')",Integer.class,task);
        if(active==0 && !control.hasUnresolvedOperation(task))db.update("update tasks set state='RECONCILED' where id=?",task);
    }
}
