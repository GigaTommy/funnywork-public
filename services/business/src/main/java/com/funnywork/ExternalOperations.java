package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

/** Trusted connector orchestration; intentionally not exposed as a model-callable HTTP endpoint. */
@Service
public class ExternalOperations {
    public enum Outcome { CONFIRMED, REJECTED, UNKNOWN }
    public record Evidence(Outcome outcome,String receipt) {
        public Evidence {Objects.requireNonNull(outcome);if(receipt!=null && (receipt.length()>200 || receipt.chars().anyMatch(c->c<32)))throw new IllegalArgumentException("Invalid receipt reference");
            if(outcome!=Outcome.UNKNOWN && (receipt==null || receipt.isBlank()))throw new IllegalArgumentException("Verified outcome requires a receipt reference");}
    }
    public interface Gateway {
        void dispatch(String stableOperationId,Map<String,Object> validatedInput) throws Exception;
        Evidence lookup(String stableOperationId,String expectedInputHash) throws Exception;
    }
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final ToolRegistry registry;
    private final GatewayRegistry gateways;
    public ExternalOperations(JdbcTemplate db,AuthService auth,TenantLocks locks,PlatformTransactionManager manager,Json json,Clock clock,ToolRegistry registry,GatewayRegistry gateways) {
        this.db=db;this.auth=auth;this.locks=locks;this.tx=new TransactionTemplate(manager);this.json=json;this.clock=clock;this.registry=registry;this.gateways=gateways;
    }
    private Identity actor(Identity actor) {actor=auth.current(actor);actor.requireUser("OPERATOR");return actor;}
    private Map<String,Object> own(Identity actor,String id) {
        actor=actor(actor);
        var rows=db.queryForList("select o.*,t.zone from external_operations o join tasks t on t.id=o.task_id where o.id=? and o.tenant_id=? and o.owner_id=?",id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);
    }
    private String contractHash(ToolContract contract) {
        return AuthService.hash(json.write(new TreeMap<>(Map.of("name",contract.name(),"effect",contract.effect(),"fields",new TreeMap<>(contract.fields()),"credentialRef",contract.credentialRef(),"verifier",contract.verifier()))));
    }
    public Map<String,Object> read(Identity actor,String id) {return own(actor,id);}
    // Package-only seam for deterministic gateway tests and legacy migration.
    String prepare(Identity actor,String taskId,ToolContract contract,String businessKey,Map<String,Object> input) {
        return prepare(actor,taskId,contract,businessKey,input,null,null);
    }
    public String prepareBound(Identity actor,String taskId,String toolVersion,String gatewayId,String businessKey,Map<String,Object> input) {
        var registered=registry.authorizedVersion(actor,taskId,toolVersion);
        gateways.resolve(actor.tenant(),gatewayId,registered.contract(),db.queryForObject("select zone from tasks where id=? and tenant_id=?",String.class,taskId,actor.tenant()));
        return prepare(actor,taskId,registered.contract(),businessKey,input,registered.id(),gatewayId);
    }
    private String prepare(Identity actor,String taskId,ToolContract contract,String businessKey,Map<String,Object> input,String pinnedId,String gatewayId) {
        var validated=contract.validate(input);
        if(contract.effect()==ToolContract.Effect.READ_ONLY || contract.effect()==ToolContract.Effect.ARTIFACT_WRITE)throw new ApiException(400,"WRITE_OPERATION_REQUIRED","Read-only tools do not use this write ledger");
        if(businessKey==null || businessKey.isBlank() || businessKey.length()>200)throw new ApiException(400,"INVALID_BUSINESS_KEY","Stable business key is required");
        return tx.execute(status->{
            locks.lock(actor.tenant());Identity live=actor(actor);
            var tasks=db.queryForList("select * from tasks where id=? and tenant_id=? and owner_id=?",taskId,live.tenant(),live.id());
            if(tasks.isEmpty())throw ApiException.missing();var task=tasks.get(0);live.requireZone((String)task.get("zone"));
            var registered=registry.authorized(live,taskId,contract,pinnedId);
            String hash=AuthService.hash(json.write(validated)),key=AuthService.hash(businessKey);
            var previous=db.queryForList("select * from external_operations where tenant_id=? and tool_name=? and business_key_hash=?",live.tenant(),contract.name(),key);
            if(!previous.isEmpty()) {
                var prior=previous.get(0);
                if(!prior.get("owner_id").equals(live.id()))throw ApiException.missing();
                if(!prior.get("input_hash").equals(hash) || !prior.get("effect").equals(contract.effect().name()) || !prior.get("contract_hash").equals(contractHash(contract)))throw ApiException.conflict("BUSINESS_KEY_CONFLICT");
                if(gatewayId!=null && (!gatewayId.equals(prior.get("gateway_binding_id")) || !pinnedId.equals(prior.get("tool_version_id"))))
                    throw ApiException.conflict("GATEWAY_BINDING_CONFLICT");
                return (String)prior.get("id");
            }
            if(!task.get("state").equals("RUNNING") || ((Number)task.get("owner_auth_version")).longValue()!=live.authVersion())throw ApiException.forbidden();
            String id=AuthService.id();
            db.update("insert into external_operations(id,tenant_id,owner_id,task_id,tool_name,business_key_hash,input_hash,contract_hash,effect,state,created_at,updated_at,tool_version_id,gateway_binding_id) values(?,?,?,?,?,?,?,?,?,'PREPARED',?,?,?,?)",id,live.tenant(),live.id(),taskId,contract.name(),key,hash,contractHash(contract),contract.effect().name(),clock.millis(),clock.millis(),registered.id(),gatewayId);
            auth.audit(live,"EXTERNAL_PREPARED",Map.of("operationId",id,"tool",contract.name()));return id;
        });
    }
    public Map<String,Object> executeBound(Identity actor,String id,Map<String,Object> input) {
        return executeBound(actor,id,input,()->{});
    }
    // Internal execution authority hook: caller supplies a server-owned lease check,
    // never a boolean or callback accepted from a network request.
    Map<String,Object> executeBound(Identity actor,String id,Map<String,Object> input,Runnable executionAuthority) {
        var operation=own(actor,id);
        var registered=registry.authorizedVersion(actor,(String)operation.get("task_id"),(String)operation.get("tool_version_id"));
        var gateway=gateways.resolve(actor.tenant(),(String)operation.get("gateway_binding_id"),registered.contract(),(String)operation.get("zone"));
        return execute(actor,id,registered.contract(),input,gateway,executionAuthority);
    }
    public Map<String,Object> reconcileBound(Identity actor,String id) {
        var operation=own(actor,id);
        var registered=registry.authorizedVersion(actor,(String)operation.get("task_id"),(String)operation.get("tool_version_id"));
        var gateway=gateways.resolve(actor.tenant(),(String)operation.get("gateway_binding_id"),registered.contract(),(String)operation.get("zone"));
        return reconcile(actor,id,gateway);
    }
    Map<String,Object> execute(Identity actor,String id,ToolContract contract,Map<String,Object> input,Gateway gateway) {
        return execute(actor,id,contract,input,gateway,()->{});
    }
    private Map<String,Object> execute(Identity actor,String id,ToolContract contract,Map<String,Object> input,Gateway gateway,Runnable executionAuthority) {
        var validated=contract.validate(input);
        boolean claimed=Boolean.TRUE.equals(tx.execute(status->{
            locks.lock(actor.tenant());executionAuthority.run();var operation=own(actor,id);
            if(!operation.get("contract_hash").equals(contractHash(contract)) || !operation.get("tool_name").equals(contract.name()) || !operation.get("effect").equals(contract.effect().name()) || !operation.get("input_hash").equals(AuthService.hash(json.write(validated))))throw ApiException.conflict("OPERATION_CONFLICT");
            if(!operation.get("state").equals("PREPARED"))return false;
            if(operation.get("tool_version_id")==null)throw new ApiException(403,"LEGACY_OPERATION_UNBOUND","Legacy operation requires reviewed tool binding");
            registry.authorized(actor,(String)operation.get("task_id"),contract,(String)operation.get("tool_version_id"));
            var task=db.queryForMap("select * from tasks where id=?",operation.get("task_id"));
            if(!task.get("state").equals("RUNNING") || ((Number)task.get("owner_auth_version")).longValue()!=actor.authVersion())throw ApiException.forbidden();
            transition(actor,id,"DISPATCHED",null);return true;
        }));
        if(!claimed)return read(actor,id); // DISPATCHED and UNKNOWN are never automatically sent again.
        // No database transaction or tenant lock spans the external operation.
        try {gateway.dispatch(id,validated);}catch(Exception failure) {
            finish(actor,id,new Evidence(Outcome.UNKNOWN,null));return read(actor,id);
        }
        return reconcile(actor,id,gateway);
    }
    Map<String,Object> reconcile(Identity actor,String id,Gateway gateway) {
        var operation=own(actor,id);String state=(String)operation.get("state");
        if(!Set.of("DISPATCHED","UNKNOWN").contains(state))return operation;
        if(operation.get("tool_version_id")==null)throw new ApiException(403,"LEGACY_OPERATION_UNBOUND","Legacy operation requires reviewed tool binding");
        registry.authorizedVersion(actor,(String)operation.get("task_id"),(String)operation.get("tool_version_id"));
        Evidence evidence;
        try {evidence=gateway.lookup(id,(String)operation.get("input_hash"));if(evidence==null)evidence=new Evidence(Outcome.UNKNOWN,null);}
        catch(Exception failure) {evidence=new Evidence(Outcome.UNKNOWN,null);}
        finish(actor,id,evidence);return read(actor,id);
    }
    private void finish(Identity actor,String id,Evidence evidence) {
        tx.executeWithoutResult(status->{
            locks.lock(actor.tenant());var operation=own(actor,id);
            if(Set.of("DISPATCHED","UNKNOWN").contains(operation.get("state")))transition(actor,id,evidence.outcome().name(),evidence.receipt());
        });
    }
    private void transition(Identity actor,String id,String state,String receipt) {
        db.update("update external_operations set state=?,receipt_ref=?,updated_at=? where id=?",state,receipt,clock.millis(),id);
        auth.audit(actor,"EXTERNAL_"+state,Map.of("operationId",id));
    }
}
