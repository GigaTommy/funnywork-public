package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

/** Node triggers only a prebound server-side gateway; credentials never leave the server. */
@Service
public class ConnectorExecution {
    public record Prepare(@NotBlank String toolVersionId,@NotBlank String gatewayId,
                          @NotBlank @Size(max=200) String businessKey,@NotNull @Size(max=64) Map<String,Object> input) {}
    private final ControlService control;
    private final ExternalOperations operations;
    private final ToolRegistry tools;
    private final TenantLocks locks;
    private final JdbcTemplate db;
    private final Json json;
    private final AuthService auth;
    private final Clock clock;
    private final TransactionTemplate tx;
    public ConnectorExecution(ControlService control,ExternalOperations operations,ToolRegistry tools,TenantLocks locks,
                              JdbcTemplate db,Json json,AuthService auth,Clock clock,PlatformTransactionManager manager) {
        this.control=control;this.operations=operations;this.tools=tools;this.locks=locks;
        this.db=db;this.json=json;this.auth=auth;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> prepare(Identity owner,String task,String unit,Prepare request) {
        return tx.execute(status->{
            locks.lock(owner.tenant());auth.current(owner).requireUser("OPERATOR");control.getTask(owner,task);
            var rows=db.queryForList("select u.*,v.capability from work_units u join tasks t on t.id=u.task_id join agent_versions v on v.id=t.agent_id where u.id=? and u.task_id=? and u.tenant_id=?",unit,task,owner.tenant());
            if(rows.isEmpty())throw ApiException.missing();
            var row=rows.get(0);
            if(!"connector.write.v1".equals(row.get("capability")))throw ApiException.conflict("CAPABILITY_MISMATCH");
            var contract=tools.authorizedVersion(owner,task,request.toolVersionId()).contract();
            var input=contract.validate(request.input());
            var prior=db.queryForList("select * from unit_operations where unit_id=?",unit);
            if(prior.isEmpty() && (!"READY".equals(row.get("state")) || ((Number)row.get("attempts")).longValue()!=0))
                throw ApiException.conflict("UNIT_ALREADY_STARTED");
            String operation=operations.prepareBound(owner,task,request.toolVersionId(),request.gatewayId(),request.businessKey(),input);
            if(!prior.isEmpty()) {
                if(!operation.equals(prior.get(0).get("operation_id")))throw ApiException.conflict("UNIT_OPERATION_CONFLICT");
                return Map.of("operationId",operation);
            }
            db.update("insert into unit_operations values(?,?,?,?)",owner.tenant(),unit,operation,json.write(input));
            db.update("update work_units set next_dispatch_at=? where id=?",clock.millis(),unit);
            auth.audit(owner,"UNIT_OPERATION_BOUND",Map.of("unitId",unit,"operationId",operation));
            return Map.of("operationId",operation);
        });
    }
    private record Work(ControlService.ModelLease lease,String operation,Map<String,Object> input) {}
    @SuppressWarnings("unchecked")
    public Map<String,Object> execute(Identity node,String attempt,long epoch) {
        Work work=tx.execute(status->{
            locks.lock(node.tenant());
            var lease=control.connectorLease(node,attempt,epoch,true);
            var rows=db.queryForList("select * from unit_operations where unit_id=? and tenant_id=?",lease.unitId(),node.tenant());
            if(rows.isEmpty())throw ApiException.missing();
            var row=rows.get(0);
            return new Work(lease,(String)row.get("operation_id"),(Map<String,Object>)json.read((String)row.get("input_json")));
        });
        var existing=db.queryForList("select result_json from unit_results where unit_id=? and attempt_id=?",work.lease().unitId(),attempt);
        if(!existing.isEmpty())return (Map<String,Object>)json.read((String)existing.get(0).get("result_json"));
        var operation=operations.executeBound(work.lease().owner(),work.operation(),work.input(),
            ()->control.connectorLease(node,attempt,epoch,false));
        if(Set.of("UNKNOWN","DISPATCHED").contains(operation.get("state"))) {
            // No external call while holding the database lock. Refresh authority immediately before lookup.
            tx.executeWithoutResult(status->{locks.lock(node.tenant());control.connectorLease(node,attempt,epoch,false);});
            operation=operations.reconcileBound(work.lease().owner(),work.operation());
        }
        String state=(String)operation.get("state");
        var result=Map.<String,Object>of("operationId",work.operation(),"state",state);
        if(!Set.of("CONFIRMED","REJECTED").contains(state))return result;
        return tx.execute(status->{
            locks.lock(node.tenant());control.connectorLease(node,attempt,epoch,true);
            var previous=db.queryForList("select result_json from unit_results where unit_id=?",work.lease().unitId());
            if(!previous.isEmpty())return (Map<String,Object>)json.read((String)previous.get(0).get("result_json"));
            control.connectorLease(node,attempt,epoch,false);
            db.update("insert into unit_results(unit_id,attempt_id,result_json,accepted_at) values(?,?,?,?)",work.lease().unitId(),attempt,json.write(result),clock.millis());
            db.update("update attempts set state='SUCCEEDED' where id=?",attempt);
            db.update("update work_units set state=? where id=?",state.equals("CONFIRMED")?"SUCCEEDED":"FAILED",work.lease().unitId());
            control.refreshTask(work.lease().taskId());
            auth.audit(node,"CONNECTOR_RESULT_VERIFIED",Map.of("attemptId",attempt,"operationId",work.operation(),"state",state));
            return result;
        });
    }
}
