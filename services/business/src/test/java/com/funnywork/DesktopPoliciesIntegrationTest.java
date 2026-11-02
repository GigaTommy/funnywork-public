package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:desktop-policies;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class DesktopPoliciesIntegrationTest {
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired DesktopPolicies policies;@Autowired AuthService auth;@Autowired DesktopHistorySync sync;@Autowired DesktopInteractions interactions;@Autowired DesktopDeviceLinks links;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MockMvc http;
    Identity admin,user,other;String token,device;
    Identity create(String tenant,String role,Set<String> zones,String token){auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),zones,token);return auth.authenticate(token);}
    @BeforeEach void setup(){String tenant=AuthService.id();admin=create(tenant,"ADMIN",Set.of("sandbox"),AuthService.id());token=AuthService.id();user=create(tenant,"VIEWER",Set.of("sandbox"),token);other=create(tenant,"VIEWER",Set.of("sandbox"),AuthService.id());device=AuthService.id();sync.registerDevice(user,new DesktopHistorySync.Device(device,"Mac","darwin"));interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode()));}
    DesktopPolicies.Policy policy(boolean cross,boolean control){return new DesktopPolicies.Policy(new DesktopPolicies.Constraints(cross,control),new DesktopPolicies.Defaults("dark","zh-CN"));}
    @Test void defaultsDoNotOverrideUserPreferencesAndConstraintsTakePrecedence(){
        var preview=policies.preview(admin,new DesktopPolicies.Preview(policy(false,true),new DesktopPolicies.Preferences("light",null),1));var effective=(Map<?,?>)preview.get("effective");assertEquals("light",effective.get("theme"));assertEquals("USER",effective.get("themeSource"));assertEquals("zh-CN",effective.get("locale"));assertEquals("ORGANIZATION_DEFAULT",effective.get("localeSource"));assertEquals(false,effective.get("remoteControl"));
        assertEquals(false,policies.preview(admin,new DesktopPolicies.Preview(policy(true,true),new DesktopPolicies.Preferences(null,null),0)).get("compatible"));
        assertEquals(false,policies.preview(admin,new DesktopPolicies.Preview(policy(true,true),new DesktopPolicies.Preferences(null,null),2)).get("compatible"));
    }
    @Test void personalLocaleRemainsIndependentFromOrganizationDefault()throws Exception {
        var issued=policies.issue(user,"a".repeat(32));
        var receipt=new DesktopPolicies.Receipt("a".repeat(32),0,(String)issued.get("digest"),1,"1",new DesktopPolicies.Preferences(null,"fr-FR"));
        http.perform(post("/api/account/policy/receipt").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsBytes(receipt))).andExpect(status().isOk()).andExpect(jsonPath("$.effective.locale").value("fr-FR")).andExpect(jsonPath("$.effective.localeSource").value("USER"));
        assertEquals(400,assertThrows(ApiException.class,()->policies.preview(admin,new DesktopPolicies.Preview(new DesktopPolicies.Policy(new DesktopPolicies.Constraints(true,true),new DesktopPolicies.Defaults("dark","fr-FR")),new DesktopPolicies.Preferences(null,null),1))).status);
    }
    @Test void publicationIsVersionedScopedAndRestorable(){
        assertEquals(1L,policies.publish(admin,new DesktopPolicies.Release(0,policy(true,false))).get("version"));
        assertEquals(409,assertThrows(ApiException.class,()->policies.publish(admin,new DesktopPolicies.Release(0,policy(true,true)))).status);
        assertFalse(policies.canControl(user));assertEquals(2L,policies.restore(admin,new DesktopPolicies.Restore(1,0)).get("version"));assertTrue(policies.canControl(user));
        assertEquals(403,assertThrows(ApiException.class,()->policies.publish(user,new DesktopPolicies.Release(2,policy(true,true)))).status);
        create(admin.tenant(),"VIEWER",Set.of("elsewhere"),AuthService.id());assertEquals(403,assertThrows(ApiException.class,()->policies.read(admin)).status);
    }
    @Test void receiptsMustMatchIssuedCurrentPolicyAndSameAccount(){
        String client="a".repeat(32);var issued=policies.issue(user,client);String digest=(String)issued.get("digest");
        var receipt=new DesktopPolicies.Receipt(client,0,digest,1,"0.2.0-rc.2",new DesktopPolicies.Preferences(null,null));
        assertEquals(409,assertThrows(ApiException.class,()->policies.acknowledge(other,receipt)).status);
        assertEquals("APPLIED",policies.acknowledge(user,receipt).get("state"));
        assertEquals(409,assertThrows(ApiException.class,()->policies.acknowledge(user,new DesktopPolicies.Receipt(client,0,digest,2,"1",receipt.preferences()))).status);
        policies.publish(admin,new DesktopPolicies.Release(0,policy(true,false)));assertEquals(409,assertThrows(ApiException.class,()->policies.acknowledge(user,receipt)).status);
        var row=db.queryForMap("select * from desktop_policy_receipts where principal_id=?",user.id());assertEquals(0L,((Number)row.get("applied_version")).longValue());
    }
    @Test void disablingRemoteControlExpiresQueuedWorkBeforeItCanBeClaimed(){
        String session=(String)sync.registerSession(user,new DesktopHistorySync.Registration(device,json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false),0)).get("id");
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode().set("native",json.createObjectNode().put("seq",0).put("running",false))));
        var command=new DesktopInteractions.Command(AuthService.id(),"epoch","prompt",json.createObjectNode().put("text","Hello").put("mode","queue"));
        interactions.enqueue(user,session,command);policies.publish(admin,new DesktopPolicies.Release(0,policy(true,false)));
        assertEquals("expired",db.queryForObject("select status from sync_commands where id=?",String.class,command.id()));assertTrue(interactions.claim(user,device,"epoch").isEmpty());
        assertEquals(403,assertThrows(ApiException.class,()->interactions.enqueue(user,session,new DesktopInteractions.Command(AuthService.id(),"epoch","prompt",command.payload()))).status);
    }
    @Test void crossDeviceRestrictionAppliesToLegacyRequestsWithoutTrustingHeaders()throws Exception {
        var code=links.create(user,device);policies.publish(admin,new DesktopPolicies.Release(0,policy(false,false)));
        http.perform(get("/api/account/sync/devices").header("Authorization","Bearer "+token).header("X-Client-Type","desktop")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_DEVICE_DISABLED"));
        assertEquals(403,assertThrows(ApiException.class,()->links.create(user,device)).status);
        assertEquals(0,db.queryForObject("select count(*) from sync_device_links where id=?",Integer.class,code.get("id")));
        links.cancel(user,(String)code.get("id"),new DesktopDeviceLinks.Secret((String)code.get("secret")));
        // The source can still publish state, but receives no remote commands.
        assertTrue(interactions.exchange(user,device,new DesktopInteractions.State("epoch",json.createObjectNode())).isEmpty());
    }    @Test void unknownForcedFieldsAreRejectedRatherThanIgnored()throws Exception {
        String adminToken=AuthService.id();create(AuthService.id(),"ADMIN",Set.of("sandbox"),adminToken);
        http.perform(post("/api/v1/desktop-policy").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"expectedVersion\":0,\"policy\":{\"constraints\":{\"crossDeviceAccess\":true,\"remoteControl\":true,\"unknownForcedSetting\":true},\"defaults\":{\"theme\":\"system\",\"locale\":\"system\"}}}")).andExpect(status().isBadRequest());
    }
    @Test void commandClaimWaitsForPolicyPublicationCommit()throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var written=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        try {
            var publication=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{policies.publish(admin,new DesktopPolicies.Release(0,policy(true,false)));written.countDown();try{if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test synchronization timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;}));
            assertTrue(written.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var claim=executor.submit(()->interactions.claim(user,device,"epoch"));
            assertThrows(java.util.concurrent.TimeoutException.class,()->claim.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));finish.countDown();assertTrue(publication.get(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(claim.get(5,java.util.concurrent.TimeUnit.SECONDS).isEmpty());
        }finally{finish.countDown();executor.shutdownNow();}
    }

}
