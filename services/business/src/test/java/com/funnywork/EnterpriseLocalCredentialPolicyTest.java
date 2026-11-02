package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
 "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:credentialpolicy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
 "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
 "funnywork.enterprise-only-tenants=enterprise-policy","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(EnterpriseLocalCredentialPolicyTest.Config.class)
class EnterpriseLocalCredentialPolicyTest {
    @TestConfiguration static class Config {
        @Bean @Primary Clock enterpriseClock(){return Clock.fixed(EnterpriseIdentityIntegrationTest.NOW,ZoneOffset.UTC);}
        @Bean EnterpriseIdentity.Provider provider(){return new EnterpriseIdentity.Provider("policy","enterprise-policy",EnterpriseIdentityIntegrationTest.ISSUER,"funnywork-api",(RSAPublicKey)EnterpriseIdentityIntegrationTest.KEYS.getPublic());}
    }
    @Autowired AuthService auth;
    @Autowired EnterpriseIdentity identities;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired Json json;
    Identity admin;
    @BeforeEach void setup(){
        db.update("delete from enterprise_identities where tenant_id='enterprise-policy'");
        db.update("delete from events where tenant_id='enterprise-policy'");
        db.update("delete from api_tokens where principal_id in (select id from principals where tenant_id='enterprise-policy')");
        db.update("delete from principals where tenant_id='enterprise-policy'");
        auth.create("policy-admin","enterprise-policy","USER","Admin",Set.of("ADMIN","OPERATOR"),Set.of("sandbox"),"legacy-user");
        admin=new Identity("policy-admin","enterprise-policy","USER",Set.of("ADMIN","OPERATOR"),Set.of("sandbox"),1);
    }
    void legacyCredential(){
        db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash("legacy-user"),admin.id(),EnterpriseIdentityIntegrationTest.NOW.plusSeconds(3600).toEpochMilli());
    }
    @Test void existingUserCredentialsCannotAuthenticateRotateOrReturnAfterRollback()throws Exception {
        assertEquals(0,db.queryForObject("select count(*) from api_tokens where principal_id=?",Integer.class,admin.id()));
        legacyCredential();
        assertThrows(ApiException.class,()->auth.authenticate("legacy-user"));
        assertThrows(ApiException.class,()->auth.rotate(admin,"legacy-user"));
        http.perform(get("/api/v1/me").header("Authorization","Bearer legacy-user")).andExpect(status().isUnauthorized());
        auth.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(0,db.queryForObject("select count(*) from api_tokens where principal_id=? and revoked=false",Integer.class,admin.id()));
    }
    @Test void enterpriseAdministratorCreatesUsersWithoutLocalSecrets()throws Exception {
        identities.link(admin,new EnterpriseIdentity.Link("policy","employee-123",admin.id()));
        var fixture=new EnterpriseIdentityIntegrationTest();
        String jwt=fixture.token(fixture.claims().build(),EnterpriseIdentityIntegrationTest.KEYS,"at+jwt");
        http.perform(post("/api/v1/users").header("Authorization","Bearer "+jwt).contentType("application/json")
            .content("{\"name\":\"Staff\",\"roles\":[\"OPERATOR\"],\"zones\":[\"sandbox\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticationMode").value("ENTERPRISE"))
            .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.expiresInSeconds").doesNotExist());
        assertEquals(0,db.queryForObject("select count(*) from api_tokens t join principals p on p.id=t.principal_id where p.tenant_id='enterprise-policy'",Integer.class));
        identities.unlink(admin,new EnterpriseIdentity.Unlink("policy","employee-123"));
        http.perform(get("/api/v1/me").header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized());
    }
    @Test void nodeCredentialsRetainIndependentLifecycle() {
        auth.create("policy-node","enterprise-policy","NODE","Node",Set.of(),Set.of("sandbox"),"machine-token");
        auth.run(new DefaultApplicationArguments(new String[0]));
        var node=auth.authenticate("machine-token");
        var rotated=auth.rotate(node,"machine-token");
        assertThrows(ApiException.class,()->auth.authenticate("machine-token"));
        assertEquals(node.id(),auth.authenticate((String)rotated.get("token")).id());
        auth.logout(node,(String)rotated.get("token"));
        assertThrows(ApiException.class,()->auth.authenticate((String)rotated.get("token")));
    }
    @Test void deploymentPolicyIsExactAndFailsClosedOnMalformedLists() {
        var policy=new LocalCredentialPolicy("bank, gov");
        assertFalse(policy.permits("bank","USER"));assertTrue(policy.permits("bank","NODE"));assertTrue(policy.permits("bank-other","USER"));
        for(String invalid:List.of("bank,","bank,,gov","bank,bank","*","bank/other"))assertThrows(IllegalArgumentException.class,()->new LocalCredentialPolicy(invalid));
    }
}
