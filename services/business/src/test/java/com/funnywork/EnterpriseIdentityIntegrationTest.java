package com.funnywork;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
 "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:enterprise;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
 "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
 "funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(EnterpriseIdentityIntegrationTest.Config.class)
class EnterpriseIdentityIntegrationTest {
    static final Instant NOW=Instant.parse("2026-09-30T00:00:00Z");
    static final String ISSUER="https://identity.internal.example";
    static final KeyPair KEYS=key();
    static KeyPair key() {
        try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock enterpriseClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean EnterpriseIdentity.Provider provider(){
            return new EnterpriseIdentity.Provider("internal","enterprise",ISSUER,"funnywork-api",(RSAPublicKey)KEYS.getPublic());
        }
    }
    @Autowired AuthService auth;
    @Autowired EnterpriseIdentity identities;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired Json json;
    @Autowired TenantLocks locks;
    Identity admin,owner;
    @BeforeEach void setup(){
        db.update("delete from enterprise_identities where tenant_id='enterprise'");
        db.update("delete from events where tenant_id='enterprise'");
        db.update("delete from api_tokens where principal_id in (select id from principals where tenant_id='enterprise')");
        db.update("delete from principals where tenant_id='enterprise'");
        auth.create("enterprise-admin","enterprise","USER","admin",Set.of("ADMIN"),Set.of("sandbox"),"enterprise-admin-token");
        auth.create("enterprise-owner","enterprise","USER","owner",Set.of("OPERATOR"),Set.of("sandbox"),"enterprise-owner-token");
        admin=auth.authenticate("enterprise-admin-token");owner=auth.authenticate("enterprise-owner-token");
    }
    JWTClaimsSet.Builder claims(){
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("employee-123").audience("funnywork-api")
            .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(600)));
    }
    String token(JWTClaimsSet claims,KeyPair key,String type)throws Exception{
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(type)).build(),claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
    }
    void link(){identities.link(admin,new EnterpriseIdentity.Link("internal","employee-123",owner.id()));}
    @Test void validAccessTokenUsesLocalRolesAndHttpIdentity() throws Exception {
        http.perform(put("/api/v1/identity-links").header("Authorization","Bearer enterprise-admin-token")
            .contentType("application/json").content(json.write(new EnterpriseIdentity.Link("internal","employee-123",owner.id())))).andExpect(status().isOk());
        String token=token(claims().claim("roles",List.of("ADMIN")).claim("tenant","foreign").claim("zones",List.of("other")).build(),KEYS,"at+jwt");
        var identity=identities.authenticate(token);
        assertEquals("enterprise",identity.tenant());assertEquals(Set.of("OPERATOR"),identity.roles());assertEquals(Set.of("sandbox"),identity.zones());
        http.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.principalId").value(owner.id()));
        http.perform(post("/api/v1/users").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"name\":\"bad\",\"roles\":[\"ADMIN\"],\"zones\":[\"sandbox\"]}")).andExpect(status().isForbidden());
    }
    @Test void signatureIssuerAudienceTypeAndLifetimeAreMandatory()throws Exception{
        link();
        List<JWTClaimsSet> invalid=List.of(
            claims().issuer("https://foreign.example").build(),claims().audience("other").build(),
            claims().audience((String)null).build(),claims().expirationTime(null).build(),
            claims().expirationTime(Date.from(NOW.minusSeconds(1))).build(),claims().notBeforeTime(Date.from(NOW.plusSeconds(60))).build(),
            claims().issueTime(Date.from(NOW.plusSeconds(60))).build(),claims().expirationTime(Date.from(NOW.plusSeconds(3601))).build());
        for(var claims:invalid)assertEquals(401,assertThrows(ApiException.class,()->identities.authenticate(token(claims,KEYS,"at+jwt"))).status);
        assertThrows(ApiException.class,()->identities.authenticate(token(claims().build(),key(),"at+jwt")));
        assertThrows(ApiException.class,()->identities.authenticate(token(claims().build(),KEYS,"JWT")));
        http.perform(get("/api/v1/me").header("Authorization","Bearer malformed.jwt.value")).andExpect(status().isUnauthorized());
    }
    @Test void unmappedDisabledAndRevokedSubjectsCannotAuthenticate()throws Exception{
        String token=token(claims().build(),KEYS,"at+jwt");
        assertThrows(ApiException.class,()->identities.authenticate(token));
        link();var old=identities.authenticate(token);
        identities.unlink(admin,new EnterpriseIdentity.Unlink("internal","employee-123"));
        assertThrows(ApiException.class,()->identities.authenticate(token));assertThrows(ApiException.class,()->auth.current(old));
        link();auth.disable(admin,owner.id());assertThrows(ApiException.class,()->identities.authenticate(token));
    }
    @Test void pinnedKeyOverlapAndRetirementPreserveIdentityMapping()throws Exception {
        link();var next=key();
        String oldToken=token(claims().build(),KEYS,"at+jwt"),newToken=token(claims().build(),next,"at+jwt");
        var overlap=new EnterpriseIdentity(List.of(new EnterpriseIdentity.Provider("internal","enterprise",ISSUER,"funnywork-api",List.of((RSAPublicKey)KEYS.getPublic(),(RSAPublicKey)next.getPublic()))),db,auth,locks,json,Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(owner.id(),overlap.authenticate(oldToken).id());assertEquals(owner.id(),overlap.authenticate(newToken).id());
        var retired=new EnterpriseIdentity(List.of(new EnterpriseIdentity.Provider("internal","enterprise",ISSUER,"funnywork-api",(RSAPublicKey)next.getPublic())),db,auth,locks,json,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThrows(ApiException.class,()->retired.authenticate(oldToken));assertEquals(owner.id(),retired.authenticate(newToken).id());
    }
    @Test void mappingsRequireAdministratorAndCannotSilentlyReassign(){
        link();link();
        assertThrows(ApiException.class,()->identities.link(owner,new EnterpriseIdentity.Link("internal","new",owner.id())));
        assertThrows(ApiException.class,()->identities.link(admin,new EnterpriseIdentity.Link("internal","employee-123",admin.id())));
        assertThrows(ApiException.class,()->identities.link(admin,new EnterpriseIdentity.Link("unknown","new",owner.id())));
        auth.create("enterprise-node","enterprise","NODE","node",Set.of(),Set.of("sandbox"),"node-token");
        assertThrows(ApiException.class,()->identities.link(admin,new EnterpriseIdentity.Link("internal","node","enterprise-node")));
        assertFalse(json.write(db.queryForList("select payload_json from events where tenant_id='enterprise'")).contains("employee-123"));
    }
}
