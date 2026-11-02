package com.funnywork;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Clock;
import java.util.*;

@Service
public class NodeCertificates {
    public record Binding(@NotBlank @Size(max=16384) String certificatePem) {}
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final Clock clock;
    private final boolean required;
    public NodeCertificates(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock,@Value("${funnywork.require-node-certificate:false}") boolean required){this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;this.required=required;}
    private String fingerprint(X509Certificate certificate)throws Exception {
        certificate.checkValidity(Date.from(clock.instant()));
        if(certificate.getBasicConstraints()!=-1)throw new CertificateException();
        var usage=certificate.getExtendedKeyUsage();
        if(usage==null || !usage.contains("1.3.6.1.5.5.7.3.2"))throw new CertificateException();
        var keyUsage=certificate.getKeyUsage();
        if(keyUsage==null || keyUsage.length==0 || !keyUsage[0])throw new CertificateException();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
    private void managed(Identity admin,String node) {
        var rows=db.queryForList("select zone from nodes where id=? and tenant_id=?",node,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();admin.requireZone((String)rows.get(0).get("zone"));
    }
    @Transactional public Map<String,Object> bind(Identity admin,String node,Binding request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");managed(admin,node);
        String fingerprint;
        try {
            String pem=request.certificatePem();
            if(pem.length()>16384 || !pem.startsWith("-----BEGIN CERTIFICATE-----") || !pem.stripTrailing().endsWith("-----END CERTIFICATE-----"))throw new CertificateException();
            var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if(certificates.size()!=1)throw new CertificateException();
            fingerprint=fingerprint((X509Certificate)certificates.iterator().next());
        } catch(Exception invalid){throw new ApiException(400,"INVALID_CERTIFICATE","A valid client certificate is required");}
        var existing=db.queryForList("select fingerprint,enabled from node_certificates where node_id=?",node);
        if(!existing.isEmpty() && fingerprint.equals(existing.get(0).get("fingerprint")) && Boolean.TRUE.equals(existing.get(0).get("enabled")))return Map.of("fingerprint",fingerprint);
        if(db.queryForObject("select count(*) from node_certificates where fingerprint=? and node_id<>?",Integer.class,fingerprint,node)>0)throw ApiException.conflict("CERTIFICATE_ALREADY_BOUND");
        if(existing.isEmpty())db.update("insert into node_certificates values(?,?,?,true)",admin.tenant(),node,fingerprint);
        else db.update("update node_certificates set fingerprint=?,enabled=true where node_id=?",fingerprint,node);
        db.update("update principals set auth_version=auth_version+1 where id=?",node);
        auth.audit(admin,"NODE_CERTIFICATE_BOUND",Map.of("nodeId",node,"fingerprint",fingerprint));
        return Map.of("fingerprint",fingerprint);
    }
    @Transactional public void revoke(Identity admin,String node) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");managed(admin,node);
        if(db.update("update node_certificates set enabled=false where node_id=? and enabled=true",node)>0) {
            db.update("update principals set auth_version=auth_version+1 where id=?",node);
            auth.audit(admin,"NODE_CERTIFICATE_REVOKED",Map.of("nodeId",node));
        }
    }
    public void verify(Identity identity,HttpServletRequest request) {
        if(!"NODE".equals(identity.kind()))return;
        var rows=db.queryForList("select fingerprint,enabled from node_certificates where node_id=? and tenant_id=?",identity.id(),identity.tenant());
        if(rows.isEmpty() && !required)return;
        var denied=new ApiException(401,"NODE_CERTIFICATE_REQUIRED","A valid bound TLS client certificate is required");
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("enabled")) || !request.isSecure())throw denied;
        Object chain=request.getAttribute("jakarta.servlet.request.X509Certificate");
        if(!(chain instanceof X509Certificate[] certificates) || certificates.length==0)throw denied;
        try {if(!fingerprint(certificates[0]).equals(rows.get(0).get("fingerprint")))throw denied;}
        catch(Exception invalid){throw denied;}
    }
}
