package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class NodeEnrollment {
    public record Issue(@NotNull @Valid Requests.Node node,@Min(30) @Max(900) int expiresInSeconds) {}
    public record Redeem(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    private final JdbcTemplate db;
    private final TenantLocks locks;
    private final AuthService auth;
    private final ControlService control;
    private final Clock clock;
    public NodeEnrollment(JdbcTemplate db,TenantLocks locks,AuthService auth,ControlService control,Clock clock){this.db=db;this.locks=locks;this.auth=auth;this.control=control;this.clock=clock;}
    @Transactional public Map<String,Object> status(Identity admin,String id) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");
        var rows=db.queryForList("select id,zone,capacity,capability,expires_at,state,node_id from node_enrollments where id=? and tenant_id=?",id,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);admin.requireZone((String)row.get("zone"));
        String state=(String)row.get("state");
        if("ISSUED".equals(state) && ((Number)row.get("expires_at")).longValue()<=clock.millis())state="EXPIRED";
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enrollmentId",id);result.put("state",state);result.put("zone",row.get("zone"));
        result.put("capacity",row.get("capacity"));result.put("capability",row.get("capability"));result.put("expiresAt",row.get("expires_at"));
        if(row.get("node_id")!=null) {
            result.put("nodeId",row.get("node_id"));
            result.put("nodeEnabled",db.queryForObject("select enabled from principals where id=? and tenant_id=?",Boolean.class,row.get("node_id"),admin.tenant()));
        }
        return result;
    }
    @Transactional public Map<String,Object> issue(Identity admin,Issue request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");admin.requireZone(request.node().zone());
        if(request.expiresInSeconds()<30 || request.expiresInSeconds()>900)throw new ApiException(400,"INVALID_TTL","Invalid enrollment lifetime");
        if(db.queryForObject("select count(*) from node_enrollments where tenant_id=? and state='ISSUED' and expires_at>?",Integer.class,admin.tenant(),clock.millis())>=100)
            throw new ApiException(429,"ENROLLMENT_LIMIT","Too many outstanding enrollments");
        String id=AuthService.id(),token=auth.newToken();long expires=clock.millis()+request.expiresInSeconds()*1000L;
        var node=request.node();
        db.update("insert into node_enrollments(id,tenant_id,issuer_id,issuer_version,token_hash,name,zone,capacity,capability,expires_at,state) values(?,?,?,?,?,?,?,?,?,?,'ISSUED')",id,admin.tenant(),admin.id(),admin.authVersion(),AuthService.hash(token),node.name(),node.zone(),node.capacity(),node.capability(),expires);
        auth.audit(admin,"NODE_ENROLLMENT_ISSUED",Map.of("enrollmentId",id,"zone",node.zone(),"expiresAt",expires));
        return Map.of("enrollmentId",id,"token",token,"expiresAt",expires);
    }
    @Transactional public void revoke(Identity admin,String id) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");
        var rows=db.queryForList("select * from node_enrollments where id=? and tenant_id=?",id,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);admin.requireZone((String)row.get("zone"));
        if("CONSUMED".equals(row.get("state")))throw ApiException.conflict("ENROLLMENT_ALREADY_CONSUMED");
        if(db.update("update node_enrollments set state='REVOKED' where id=? and state='ISSUED'",id)>0)
            auth.audit(admin,"NODE_ENROLLMENT_REVOKED",Map.of("enrollmentId",id));
    }
    private ApiException denied(){return new ApiException(401,"INVALID_ENROLLMENT","A valid unused enrollment credential is required");}
    @Transactional public Map<String,Object> redeem(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw denied();
        String hash=AuthService.hash(token);
        var found=db.queryForList("select tenant_id from node_enrollments where token_hash=?",hash);
        if(found.isEmpty())throw denied();String tenant=(String)found.get(0).get("tenant_id");locks.lock(tenant);
        var row=db.queryForMap("select * from node_enrollments where token_hash=?",hash);
        if(!"ISSUED".equals(row.get("state")) || ((Number)row.get("expires_at")).longValue()<=clock.millis())throw denied();
        Identity issuer;
        try {
            issuer=auth.current(new Identity((String)row.get("issuer_id"),tenant,"USER",Set.of(),Set.of(),((Number)row.get("issuer_version")).longValue()));
            issuer.requireUser("ADMIN");issuer.requireZone((String)row.get("zone"));
        } catch(ApiException invalid){throw denied();}
        var result=control.registerNode(issuer,new Requests.Node((String)row.get("name"),(String)row.get("zone"),((Number)row.get("capacity")).intValue(),(String)row.get("capability")));
        db.update("update node_enrollments set state='CONSUMED',node_id=? where id=?",result.get("nodeId"),row.get("id"));
        auth.audit(issuer,"NODE_ENROLLMENT_CONSUMED",Map.of("enrollmentId",row.get("id"),"nodeId",result.get("nodeId")));
        return result;
    }
}
