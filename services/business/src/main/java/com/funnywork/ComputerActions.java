package com.funnywork;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class ComputerActions {
    public record Action(@Min(1) long epoch,@Min(1) long generation,@NotBlank @Size(max=100) String actionId,
        @NotNull @Pattern(regexp="click|fill") String action,@NotNull @Pattern(regexp="[a-f0-9]{64}") String signature) {}
    private final JdbcTemplate db;private final TenantLocks locks;private final ComputerGrants grants;private final ComputerControl controls;
    private final AuthService auth;private final Clock clock;private final Validator validator;
    public ComputerActions(JdbcTemplate db,TenantLocks locks,ComputerGrants grants,ComputerControl controls,AuthService auth,Clock clock,Validator validator){this.db=db;this.locks=locks;this.grants=grants;this.controls=controls;this.auth=auth;this.clock=clock;this.validator=validator;}
    private Map<String,Object> receipt(String id,String state,boolean execute){return Map.of("actionId",id,"status",state,"shouldExecute",execute,"businessVerified",false);}
    private void matches(Map<String,Object> row,Action action){
        if(!action.action().equals(row.get("action_type")) || !action.signature().equals(row.get("signature")))throw ApiException.conflict("ACTION_ID_CONFLICT");
    }
    @Transactional public Map<String,Object> operate(Identity node,String attempt,Action action,String operation){
        locks.lock(node.tenant());
        if(action==null || !validator.validate(action).isEmpty() || !Set.of("lookup","claim","dispatched").contains(operation))throw new ApiException(400,"INVALID_COMPUTER_ACTION","Invalid desktop action receipt");
        controls.check(node,attempt,action.epoch(),action.generation(),false);
        var scope=grants.scope(node,attempt,action.epoch());String unit=(String)scope.get("unitId");
        var policy=(Map<?,?>)scope.get("policy");
        if(!((List<?>)policy.get("actions")).contains(action.action()))throw ApiException.forbidden();
        var rows=db.queryForList("select * from computer_actions where unit_id=? and action_id=?",unit,action.actionId());
        if(!rows.isEmpty()) {
            var row=rows.get(0);matches(row,action);
            if(operation.equals("dispatched")) {
                if(!attempt.equals(row.get("attempt_id")) || ((Number)row.get("generation")).longValue()!=action.generation())throw ApiException.conflict("ACTION_ID_CONFLICT");
                if(db.update("update computer_actions set state='DISPATCHED' where unit_id=? and action_id=? and state='UNKNOWN'",unit,action.actionId())>0)
                    auth.audit(node,"COMPUTER_ACTION_DISPATCHED",Map.of("unitId",unit,"actionId",action.actionId()));
                return receipt(action.actionId(),"DISPATCHED",false);
            }
            return receipt(action.actionId(),(String)row.get("state"),false);
        }
        if(operation.equals("lookup"))return receipt(action.actionId(),"NOT_FOUND",false);
        if(operation.equals("dispatched"))throw ApiException.missing();
        if(db.queryForObject("select count(*) from computer_actions where unit_id=? and (state='UNKNOWN' or attempt_id<>? or generation<>?)",Integer.class,unit,attempt,action.generation())>0)throw ApiException.conflict("COMPUTER_RECOVERY_REQUIRED");
        if(db.queryForObject("select count(*) from computer_actions where unit_id=?",Integer.class,unit)>=((Number)policy.get("maxActions")).intValue())throw ApiException.conflict("ACTION_BUDGET_EXCEEDED");
        db.update("insert into computer_actions values(?,?,?,?,?,?,?,'UNKNOWN',?)",node.tenant(),unit,action.actionId(),attempt,action.generation(),action.action(),action.signature(),clock.millis());
        auth.audit(node,"COMPUTER_ACTION_CLAIMED",Map.of("unitId",unit,"actionId",action.actionId(),"action",action.action()));
        return receipt(action.actionId(),"UNKNOWN",true);
    }
}
