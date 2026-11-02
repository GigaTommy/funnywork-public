package com.funnywork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

@Service
public class AuthService implements ApplicationRunner {
    private final JdbcTemplate db;
    private final Json json;
    private final Clock clock;
    private final String bootstrap;
    @Value("${funnywork.local-management-bootstrap:false}")
    private boolean localManagementBootstrap;
    private final LocalCredentialPolicy credentialPolicy;
    private final TenantLocks locks;
    private final ApplicationEventPublisher publisher;
    public AuthService(JdbcTemplate db, Json json, Clock clock, TenantLocks locks, ApplicationEventPublisher publisher, LocalCredentialPolicy credentialPolicy, @Value("${funnywork.bootstrap-token:}") String bootstrap) {
        this.db=db; this.json=json; this.clock=clock; this.bootstrap=bootstrap;this.locks=locks;this.publisher=publisher;this.credentialPolicy=credentialPolicy;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public String newToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        for(String tenant:credentialPolicy.tenants().stream().sorted().toList()) {
            locks.ensure(tenant);locks.lock(tenant);
            db.update("update api_tokens set revoked=true where revoked=false and principal_id in (select id from principals where tenant_id=? and kind='USER')",tenant);
        }
        if (bootstrap.isBlank()) return; // No unauthenticated enrollment or built-in default password.
        if (bootstrap.length()<32) throw new IllegalArgumentException("Bootstrap token must contain at least 32 characters");
        if (db.queryForObject("select count(*) from principals",Integer.class)==0) {
            create("bootstrap-admin","demo","USER","Local bootstrap administrator",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("sandbox"),bootstrap);
        }
        // Opt-in local development initialization; never replace existing or revoked bindings.
        if(localManagementBootstrap && db.queryForObject("select count(*) from principals",Integer.class)==1
            && db.queryForObject("select count(*) from management_identity_links",Integer.class)==0) {
            db.update("insert into management_identity_links(ruoyi_tenant,ruoyi_user,principal_id) select '000000','1',id from principals where id='bootstrap-admin' and tenant_id='demo' and kind='USER' and enabled=true");
        }
    }
    void create(String id,String tenant,String kind,String name,Set<String> roles,Set<String> zones,String token) {
        locks.ensure(tenant);
        db.update("insert into principals(id,tenant_id,kind,display_name,roles,zones,enabled,auth_version) values(?,?,?,?,?,?,true,1)",id,tenant,kind,name,json.write(roles),json.write(zones));
        if(credentialPolicy.permits(tenant,kind))
            db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",hash(token),id,clock.millis()+86_400_000L);
    }
    private Identity map(Map<String,Object> row) {
        return new Identity((String)row.get("id"),(String)row.get("tenant_id"),(String)row.get("kind"),json.strings((String)row.get("roles")),json.strings((String)row.get("zones")),((Number)row.get("auth_version")).longValue());
    }
    public Identity authenticate(String token) {
        var rows=db.queryForList("select p.* from principals p join api_tokens t on p.id=t.principal_id where t.token_hash=? and t.revoked=false and t.expires_at>? and p.enabled=true",hash(token),clock.millis());
        if (rows.isEmpty()) throw new ApiException(401,"UNAUTHENTICATED","A valid, unexpired credential is required");
        Identity identity=map(rows.get(0));
        if(!credentialPolicy.permits(identity.tenant(),identity.kind()))throw new ApiException(401,"UNAUTHENTICATED","Enterprise authentication is required");
        return identity;
    }
    public Identity current(Identity identity) {
        var rows=db.queryForList("select * from principals where id=? and tenant_id=? and enabled=true",identity.id(),identity.tenant());
        if (rows.isEmpty()) throw ApiException.forbidden();
        Identity live=map(rows.get(0));
        if(live.authVersion()!=identity.authVersion()) throw ApiException.forbidden();
        return live;
    }
    @Transactional
    public Map<String,Object> addUser(Identity actor,String name,Set<String> roles,Set<String> zones) {
        locks.lock(actor.tenant());
        actor=current(actor); actor.requireUser("ADMIN");
        if (!Set.of("ADMIN","OPERATOR","VIEWER","RECONCILER").containsAll(roles) || !actor.zones().containsAll(zones)) throw ApiException.forbidden();
        String id=id(), token=newToken();
        create(id,actor.tenant(),"USER",name,roles,zones,token);
        audit(actor,"PRINCIPAL_CREATED",Map.of("principalId",id));
        if(!credentialPolicy.permits(actor.tenant(),"USER"))return Map.of("principalId",id,"authenticationMode","ENTERPRISE");
        return Map.of("principalId",id,"token",token,"expiresInSeconds",86400);
    }
    @Transactional
    public void disable(Identity actor,String principalId) {
        locks.lock(actor.tenant());
        actor=current(actor); actor.requireUser("ADMIN");
        var rows=db.queryForList("select * from principals where id=? and tenant_id=?",principalId,actor.tenant());
        if(rows.isEmpty()) throw ApiException.missing();
        if(!actor.zones().containsAll(json.strings((String)rows.get(0).get("zones")))) throw ApiException.forbidden();
        db.update("update principals set enabled=false,auth_version=auth_version+1 where id=?",principalId);
        db.update("update api_tokens set revoked=true where principal_id=?",principalId);
        audit(actor,"PRINCIPAL_DISABLED",Map.of("principalId",principalId));
    }
    @Transactional
    public Map<String,Object> rotate(Identity actor,String token) {
        locks.lock(actor.tenant());actor=current(actor);
        Identity credential=authenticate(token);
        if(!credential.id().equals(actor.id()))throw ApiException.forbidden();
        String replacement=newToken();
        db.update("update api_tokens set revoked=true where token_hash=?",hash(token));
        db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",hash(replacement),actor.id(),clock.millis()+86_400_000L);
        audit(actor,"TOKEN_ROTATED",Map.of());
        return Map.of("token",replacement,"expiresInSeconds",86400);
    }
    @Transactional
    public void logout(Identity actor,String token) {
        locks.lock(actor.tenant());actor=current(actor);
        Identity credential=authenticate(token);
        if(!credential.id().equals(actor.id()))throw ApiException.forbidden();
        db.update("update api_tokens set revoked=true where token_hash=?",hash(token));
        audit(actor,"SESSION_LOGGED_OUT",Map.of());
    }
    public void audit(Identity actor,String type,Object payload) {
        auditRead(actor,type,payload);
        publisher.publishEvent(new TenantWorkChanged(actor.tenant()));
    }
    /** Durable read audit without waking execution scheduling for an observational request. */
    public void auditRead(Identity actor,String type,Object payload) {
        db.update("insert into events(tenant_id,actor_id,event_type,payload_json,created_at,audit_zones) values(?,?,?,?,?,?)",actor.tenant(),actor.id(),type,json.write(payload),clock.millis(),json.write(actor.zones()));
    }
}
