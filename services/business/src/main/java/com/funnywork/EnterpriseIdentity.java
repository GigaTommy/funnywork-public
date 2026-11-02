package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;

/** JWT access-token verification with deployment-pinned issuers/keys and local authorization. */
@Service
public class EnterpriseIdentity {
    public record Provider(String id,String tenant,String issuer,String audience,List<RSAPublicKey> keys) {
        public Provider(String id,String tenant,String issuer,String audience,RSAPublicKey key) {
            this(id,tenant,issuer,audience,List.of(key));
        }
        public Provider {
            URI uri=URI.create(issuer);
            if(id==null || !id.matches("[a-z0-9-]{1,64}") || tenant==null || tenant.isBlank()
                || !"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || audience==null || audience.isBlank() || keys==null || keys.isEmpty() || keys.size()>3
                || keys.stream().anyMatch(k->k==null || k.getModulus().bitLength()<2048 || k.getModulus().bitLength()>8192)
                || new HashSet<>(keys).size()!=keys.size())
                throw new IllegalArgumentException("Invalid enterprise provider");
            keys=List.copyOf(keys);
        }
    }
    public record Link(@NotBlank @Size(max=64) String providerId,@NotBlank @Size(max=256) String subject,
                       @NotBlank @Size(max=64) String principalId) {}
    public record Unlink(@NotBlank @Size(max=64) String providerId,@NotBlank @Size(max=256) String subject) {}
    private record Verifier(Provider provider,List<NimbusJwtDecoder> decoders) {}
    private final Map<String,Verifier> providers;
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final Json json;
    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseIdentity(List<Provider> beans,EnterpriseIdentityConfiguration configuration,JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,Clock clock) {
        this(java.util.stream.Stream.concat(beans.stream(),configuration.providers().stream()).toList(),db,auth,locks,json,clock);
    }
    public EnterpriseIdentity(List<Provider> configured,JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,Clock clock) {
        if(configured.size()>8)throw new IllegalArgumentException("Too many identity providers");
        this.db=db;this.auth=auth;this.locks=locks;this.json=json;
        Map<String,Verifier> values=new HashMap<>();Set<String> issuers=new HashSet<>();
        for(var p:configured) {
            List<NimbusJwtDecoder> decoders=new ArrayList<>();
            for(var key:p.keys()) {
            var decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
            var times=new JwtTimestampValidator(Duration.ZERO);times.setClock(clock);
            OAuth2TokenValidator<Jwt> claims=jwt->{
                Instant issued=jwt.getIssuedAt(),expires=jwt.getExpiresAt();
                String subject=jwt.getSubject();
                boolean valid="at+jwt".equals(jwt.getHeaders().get("typ")) && jwt.getAudience()!=null && jwt.getAudience().contains(p.audience())
                    && subject!=null && !subject.isBlank() && subject.length()<=256
                    && issued!=null && expires!=null && issued.isBefore(expires)
                    && !issued.isAfter(clock.instant().plusSeconds(30)) && Duration.between(issued,expires).getSeconds()<=3600;
                return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(p.issuer()),times,claims));
            decoders.add(decoder);
            }
            if(values.putIfAbsent(p.id(),new Verifier(p,List.copyOf(decoders)))!=null || !issuers.add(p.issuer()))
                throw new IllegalArgumentException("Duplicate enterprise provider");
        }
        providers=Map.copyOf(values);
    }
    private ApiException denied(){return new ApiException(401,"UNAUTHENTICATED","A valid mapped enterprise access token is required");}
    public Identity authenticate(String token) {
        if(token.length()>16384)throw denied();
        for(var verifier:providers.values()) {
            Jwt jwt=null;
            for(var decoder:verifier.decoders()) {
                try {jwt=decoder.decode(token);break;}
                catch(JwtException | IllegalArgumentException invalid){ /* Try another deployment-pinned key. */ }
            }
            if(jwt==null)continue;
            var p=verifier.provider();
            var rows=db.queryForList("select p.* from enterprise_identities e join principals p on p.id=e.principal_id and p.tenant_id=e.tenant_id where e.provider_id=? and e.subject_hash=? and e.tenant_id=? and p.enabled=true and p.kind='USER'",p.id(),AuthService.hash(jwt.getSubject()),p.tenant());
            if(rows.isEmpty())throw denied();
            var row=rows.get(0);
            return new Identity((String)row.get("id"),(String)row.get("tenant_id"),"USER",json.strings((String)row.get("roles")),json.strings((String)row.get("zones")),((Number)row.get("auth_version")).longValue());
        }
        throw denied();
    }
    private Provider provider(Identity admin,String id) {
        var verifier=providers.get(id);
        if(verifier==null || !admin.tenant().equals(verifier.provider().tenant()))throw ApiException.missing();
        return verifier.provider();
    }
    private Map<String,Object> managedPrincipal(Identity admin,String id) {
        var rows=db.queryForList("select * from principals where id=? and tenant_id=?",id,admin.tenant());
        if(rows.isEmpty())throw ApiException.missing();
        var row=rows.get(0);
        if(!"USER".equals(row.get("kind")) || !admin.zones().containsAll(json.strings((String)row.get("zones"))))throw ApiException.forbidden();
        return row;
    }
    @Transactional
    public void link(Identity admin,Link request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");provider(admin,request.providerId());
        var principal=managedPrincipal(admin,request.principalId());
        if(!Boolean.TRUE.equals(principal.get("enabled")))throw ApiException.forbidden();
        String subject=AuthService.hash(request.subject());
        var prior=db.queryForList("select principal_id from enterprise_identities where provider_id=? and subject_hash=?",request.providerId(),subject);
        if(!prior.isEmpty()) {
            if(!request.principalId().equals(prior.get(0).get("principal_id")))throw ApiException.conflict("IDENTITY_MAPPING_CONFLICT");
            return;
        }
        db.update("insert into enterprise_identities values(?,?,?,?)",admin.tenant(),request.providerId(),subject,request.principalId());
        auth.audit(admin,"ENTERPRISE_IDENTITY_LINKED",Map.of("providerId",request.providerId(),"principalId",request.principalId()));
    }
    @Transactional
    public void unlink(Identity admin,Unlink request) {
        locks.lock(admin.tenant());admin=auth.current(admin);admin.requireUser("ADMIN");provider(admin,request.providerId());
        var prior=db.queryForList("select principal_id from enterprise_identities where tenant_id=? and provider_id=? and subject_hash=?",admin.tenant(),request.providerId(),AuthService.hash(request.subject()));
        if(prior.isEmpty())return;
        String principal=(String)prior.get(0).get("principal_id");managedPrincipal(admin,principal);
        db.update("delete from enterprise_identities where tenant_id=? and provider_id=? and subject_hash=?",admin.tenant(),request.providerId(),AuthService.hash(request.subject()));
        db.update("update principals set auth_version=auth_version+1 where id=?",principal);
        auth.audit(admin,"ENTERPRISE_IDENTITY_UNLINKED",Map.of("providerId",request.providerId(),"principalId",principal));
    }
}
