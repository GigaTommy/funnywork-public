package com.funnywork;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Immutable, expiring target authorization. This does not allocate a GUI lease. */
@Service
public class ComputerGrants {
    public record Grant(@NotBlank @Size(max=64) String nodeId,
        @NotNull @Pattern(regexp="[1-9][0-9]{0,9}") String session,
        @NotNull @Pattern(regexp="[1-9][0-9]{0,9}:[1-9][0-9]{0,19}") String application,
        @NotNull @Pattern(regexp="[1-9][0-9]{0,19}") String window,
        @NotEmpty Set<@NotNull @Pattern(regexp="click|fill") String> actions,
        @Min(1) @Max(128) int maxActions,@Min(1) @Max(3600) int validForSeconds) {}
    private final JdbcTemplate db;private final TenantLocks locks;private final AuthService auth;
    private final Json json;private final Clock clock;private final Validator validator;private final ControlService control;
    public ComputerGrants(JdbcTemplate db,TenantLocks locks,AuthService auth,Json json,Clock clock,Validator validator,ControlService control) {
        this.db=db;this.locks=locks;this.auth=auth;this.json=json;this.clock=clock;this.validator=validator;this.control=control;
    }
    private Map<String,Object> unit(Identity admin,String task,String unit) {
        var rows=db.queryForList("select u.*,t.zone,t.state as task_state,v.capability from work_units u join tasks t on t.id=u.task_id join agent_versions v on v.id=t.agent_id where u.id=? and u.task_id=? and u.tenant_id=?",unit,task,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();
        var row=rows.get(0);admin.requireZone((String)row.get("zone"));
        if(!Capabilities.COMPUTER.equals(row.get("capability")))throw ApiException.conflict("CAPABILITY_MISMATCH");
        return row;
    }
    @Transactional public void grant(Identity admin,String task,String unit,Grant request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");
        var row=unit(admin,task,unit);
        if(request==null || !validator.validate(request).isEmpty())throw new ApiException(400,"INVALID_COMPUTER_POLICY","Invalid desktop target scope");
        var nodes=db.queryForList("select n.zone,n.capability,p.enabled from nodes n join principals p on p.id=n.id where n.id=? and n.tenant_id=?",request.nodeId(),admin.tenant());
        if(nodes.isEmpty())throw ApiException.missing();
        var node=nodes.get(0);
        if(!row.get("zone").equals(node.get("zone")) || !Capabilities.COMPUTER.equals(node.get("capability")) || !Boolean.TRUE.equals(node.get("enabled")))throw ApiException.forbidden();
        Map<String,Object> policy=new TreeMap<>();
        policy.put("nodeId",request.nodeId());policy.put("session",request.session());policy.put("application",request.application());policy.put("window",request.window());
        policy.put("actions",new TreeSet<>(request.actions()));policy.put("maxActions",request.maxActions());policy.put("validForSeconds",request.validForSeconds());
        String encoded=json.write(policy);long now=clock.millis();
        var prior=db.queryForList("select * from computer_grants where unit_id=?",unit);
        if(!prior.isEmpty()) {
            var old=prior.get(0);
            if(encoded.equals(old.get("policy_json")) && Boolean.TRUE.equals(old.get("enabled")) && ((Number)old.get("expires_at")).longValue()>now)return;
            throw ApiException.conflict("COMPUTER_POLICY_IMMUTABLE");
        }
        if(!"RUNNING".equals(row.get("task_state")) || !"READY".equals(row.get("state")) || ((Number)row.get("attempts")).intValue()!=0)throw ApiException.conflict("UNIT_ALREADY_STARTED");
        db.update("insert into computer_grants(tenant_id,unit_id,node_id,issuer_id,issuer_version,policy_json,enabled,created_at,expires_at) values(?,?,?,?,?,?,true,?,?)",admin.tenant(),unit,request.nodeId(),admin.id(),admin.authVersion(),encoded,now,now+request.validForSeconds()*1000L);
        auth.audit(admin,"COMPUTER_UNIT_GRANTED",Map.of("taskId",task,"unitId",unit,"nodeId",request.nodeId(),"policy",policy));
    }
    @Transactional public Map<String,Object> scope(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());node=auth.current(node);node.requireNode();
        var lease=control.computerLease(node,attempt,epoch);
        var rows=db.queryForList("select g.*,p.kind,p.roles,p.zones,p.enabled as issuer_enabled,p.auth_version,n.zone,n.capability,t.zone as task_zone,a.expires_at as lease_expires from computer_grants g join principals p on p.id=g.issuer_id join nodes n on n.id=g.node_id join tasks t on t.id=? join attempts a on a.id=? where g.unit_id=? and g.tenant_id=? and g.node_id=? and g.enabled=true and g.expires_at>?",lease.taskId(),attempt,lease.unitId(),node.tenant(),node.id(),clock.millis());
        if(rows.isEmpty())throw ApiException.forbidden();var row=rows.get(0);
        String zone=(String)row.get("task_zone");node.requireZone(zone);
        if(!"USER".equals(row.get("kind")) || !Boolean.TRUE.equals(row.get("issuer_enabled"))
            || ((Number)row.get("issuer_version")).longValue()!=((Number)row.get("auth_version")).longValue()
            || !json.strings((String)row.get("roles")).contains("ADMIN") || !json.strings((String)row.get("zones")).contains(zone)
            || !zone.equals(row.get("zone")) || !Capabilities.COMPUTER.equals(row.get("capability")))throw ApiException.forbidden();
        long expires=Math.min(((Number)row.get("expires_at")).longValue(),((Number)row.get("lease_expires")).longValue());
        return Map.of("unitId",lease.unitId(),"policy",json.read((String)row.get("policy_json")),"expiresAt",expires);
    }
    @Transactional public Map<String,Object> get(Identity admin,String task,String unit) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");unit(admin,task,unit);
        var rows=db.queryForList("select policy_json,enabled,expires_at from computer_grants where unit_id=? and tenant_id=?",unit,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        return Map.of("policy",json.read((String)row.get("policy_json")),"enabled",row.get("enabled"),"expiresAt",row.get("expires_at"));
    }
    @Transactional public void revoke(Identity admin,String task,String unit) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");unit(admin,task,unit);
        if(db.update("update computer_grants set enabled=false where unit_id=? and tenant_id=? and enabled=true",unit,admin.tenant())>0)
            auth.audit(admin,"COMPUTER_UNIT_REVOKED",Map.of("taskId",task,"unitId",unit));
    }
}
