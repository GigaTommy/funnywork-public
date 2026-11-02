package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Expired ownership stays reserved until its trusted node confirms driver shutdown. */
@Service
public class ComputerControl {
    private final JdbcTemplate db;private final TenantLocks locks;private final AuthService auth;private final ComputerGrants grants;private final Clock clock;
    public ComputerControl(JdbcTemplate db,TenantLocks locks,AuthService auth,ComputerGrants grants,Clock clock){this.db=db;this.locks=locks;this.auth=auth;this.grants=grants;this.clock=clock;}
    private Identity node(Identity node){locks.lock(node.tenant());node=auth.current(node);node.requireNode();return node;}
    private long expires(Map<String,Object> scope){return Math.min(clock.millis()+15000,((Number)scope.get("expiresAt")).longValue());}
    private Map<String,Object> result(Object generation,long expiry,Object session){
        long remaining=expiry-clock.millis();
        if(remaining<=0)throw ApiException.conflict("GUI_CONTROL_EXPIRED");
        return Map.of("generation",generation,"expiresAt",expiry,"session",session,"remainingMs",remaining);
    }
    private Map<String,Object> own(Identity node,String attempt,long epoch,long generation){
        var rows=db.queryForList("select * from computer_controls where tenant_id=? and node_id=? and attempt_id=?",node.tenant(),node.id(),attempt);
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        if(((Number)row.get("epoch")).longValue()!=epoch || ((Number)row.get("generation")).longValue()!=generation)throw ApiException.conflict("STALE_GUI_CONTROL");
        return row;
    }
    private void active(Identity node,String attempt,Map<String,Object> row){
        if(!"ACTIVE".equals(row.get("state")) || ((Number)row.get("expires_at")).longValue()<=clock.millis())throw ApiException.conflict("GUI_CONTROL_EXPIRED");
        if(db.queryForObject("select count(*) from computer_sessions where tenant_id=? and node_id=? and os_session=? and current_attempt=? and generation=?",Integer.class,node.tenant(),node.id(),row.get("os_session"),attempt,row.get("generation"))!=1)throw ApiException.conflict("STALE_GUI_CONTROL");
    }
    @Transactional public Map<String,Object> acquire(Identity node,String attempt,long epoch){
        node=node(node);var scope=grants.scope(node,attempt,epoch);
        var prior=db.queryForList("select generation from computer_controls where attempt_id=?",attempt);
        if(!prior.isEmpty()){
            var row=own(node,attempt,epoch,((Number)prior.get(0).get("generation")).longValue());active(node,attempt,row);return result(row.get("generation"),Math.min(((Number)row.get("expires_at")).longValue(),((Number)scope.get("expiresAt")).longValue()),row.get("os_session"));
        }
        String session=(String)((Map<?,?>)scope.get("policy")).get("session");
        var slots=db.queryForList("select * from computer_sessions where tenant_id=? and node_id=? and os_session=?",node.tenant(),node.id(),session);
        if(slots.isEmpty())db.update("insert into computer_sessions values(?,?,?,0,null)",node.tenant(),node.id(),session);
        else if(slots.get(0).get("current_attempt")!=null)throw ApiException.conflict("GUI_SESSION_BUSY");
        long generation=slots.isEmpty()?1:Math.addExact(((Number)slots.get(0).get("generation")).longValue(),1);
        long expires=expires(scope);
        db.update("insert into computer_controls values(?,?,?,?,?,?,'ACTIVE',?)",node.tenant(),attempt,node.id(),session,epoch,generation,expires);
        db.update("update computer_sessions set generation=?,current_attempt=? where tenant_id=? and node_id=? and os_session=?",generation,attempt,node.tenant(),node.id(),session);
        auth.audit(node,"COMPUTER_CONTROL_ACQUIRED",Map.of("attemptId",attempt,"generation",generation));
        return result(generation,expires,session);
    }
    @Transactional public Map<String,Object> check(Identity node,String attempt,long epoch,long generation,boolean renew){
        node=node(node);var scope=grants.scope(node,attempt,epoch);var row=own(node,attempt,epoch,generation);active(node,attempt,row);
        long expiry=Math.min(((Number)row.get("expires_at")).longValue(),((Number)scope.get("expiresAt")).longValue());
        if(renew){expiry=expires(scope);db.update("update computer_controls set expires_at=? where attempt_id=?",expiry,attempt);}
        return result(generation,expiry,row.get("os_session"));
    }
    @Transactional public void release(Identity node,String attempt,long epoch,long generation,boolean driverStopped){
        node=node(node);
        if(!driverStopped)throw ApiException.conflict("DRIVER_STOP_REQUIRED");
        var row=own(node,attempt,epoch,generation);
        if("RELEASED".equals(row.get("state")))return;
        if(db.update("update computer_sessions set current_attempt=null where tenant_id=? and node_id=? and os_session=? and current_attempt=? and generation=?",node.tenant(),node.id(),row.get("os_session"),attempt,generation)!=1)throw ApiException.conflict("STALE_GUI_CONTROL");
        db.update("update computer_controls set state='RELEASED' where attempt_id=?",attempt);
        auth.audit(node,"COMPUTER_CONTROL_RELEASED",Map.of("attemptId",attempt,"generation",generation));
    }
}
