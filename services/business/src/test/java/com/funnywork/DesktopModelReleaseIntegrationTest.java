package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:desktop-model-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=model-release-test-bootstrap-012345678901234567890","funnywork.scheduling-enabled=false"})
class DesktopModelReleaseIntegrationTest {
    static Path file;static JdbcTemplate postgres;static String schema;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry)throws Exception {
        file=Files.createTempFile("funnywork-model-test-",".json");var signer=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] publicKey=signer.getPublic().getEncoded();
        new ObjectMapper().writeValue(file.toFile(),new DesktopModelPublication.Configuration("demo",Base64.getEncoder().encodeToString(signer.getPrivate().getEncoded()),Base64.getEncoder().encodeToString(Arrays.copyOfRange(publicKey,publicKey.length-32,publicKey.length)),"https://api.deepseek.com/v1","deepseek-chat","integration-secret","disabled",2048,7));
        registry.add("funnywork.desktop-model-file",file::toString);
        String url=System.getenv("FUNNYWORK_MODEL_TEST_JDBC_URL");
        if(url!=null&&!url.isBlank()){
            String user=System.getenv().getOrDefault("FUNNYWORK_MODEL_TEST_JDBC_USER",System.getProperty("user.name"));
            String password=System.getenv().getOrDefault("FUNNYWORK_MODEL_TEST_JDBC_PASSWORD","");
            postgres=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password));schema="fw_model_test_"+UUID.randomUUID().toString().replace("-","");postgres.execute("create schema "+schema);
            registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        }
    }
    @AfterAll static void clean()throws Exception {Files.deleteIfExists(file);if(postgres!=null)postgres.execute("drop schema "+schema+" cascade");}
    @Autowired DesktopModelPublication publications;@Autowired DesktopModelReceipts receipts;@Autowired DesktopHistorySync sync;@Autowired ManagedDesktopDevices devices;@Autowired DesktopPolicies policies;@Autowired DesktopModelReleases releases;
    @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;
    @MockitoSpyBean ManagementIdentity management;@MockitoSpyBean AuthService auth;
    @BeforeEach void setup(){db.update("delete from desktop_model_receipts");db.update("delete from desktop_model_releases");db.update("delete from management_identity_links");db.update("insert into management_identity_links values('000000','1','bootstrap-admin')");permissions("funnyworker:models:read","funnyworker:models:write","funnyworker:desktop:read");}
    void permissions(String... permissions){doReturn(new ManagementIdentity.Authority("000000","1",Set.of(permissions))).when(management).authority("model-session");}
    String body(long version)throws Exception{return mapper.writeValueAsString(new DesktopModelReleases.Settings(version,"https://api.deepseek.com/v1","deepseek-reasoner","enabled",4096,"DEPLOYMENT_DEFAULT"));}
    @Test void httpPublicationAndRollbackKeepSharedCredentialsServerSide()throws Exception {
        http.perform(post("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1").contentType("application/json").content(body(7))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(8));
        var device=KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded();String publicKey=Base64.getEncoder().encodeToString(Arrays.copyOfRange(device,device.length-32,device.length));
        http.perform(post("/api/account/model/publication").header("Authorization","Bearer ruoyi.model-session").contentType("application/json").content(mapper.writeValueAsString(new DesktopModelPublication.Device("a".repeat(32),publicKey)))).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("MODEL_SECRET_PUBLICATION_DISABLED"));
        http.perform(post("/api/v1/desktop-model-releases/rollback").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1").contentType("application/json").content("{\"expectedVersion\":8,\"targetVersion\":7}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(9));
        String state=http.perform(get("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1")).andExpect(status().isOk()).andExpect(jsonPath("$.settings.model").value("deepseek-chat")).andReturn().getResponse().getContentAsString();assertFalse(state.contains("integration-secret"));assertFalse(state.contains("signingKey"));
    }
    @Test void readOnlyManagementAndDesktopPermissionsCannotPublish()throws Exception {
        permissions("funnyworker:models:read","funnyworker:desktop:read");
        http.perform(get("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1")).andExpect(status().isOk());
        http.perform(post("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1").contentType("application/json").content(body(7))).andExpect(status().isForbidden());
        permissions("funnyworker:desktop:read");http.perform(get("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1")).andExpect(status().isForbidden());
    }
    @Test void failedAuditRollsBackBothPublicationAndCapturedBaseline()throws Exception {
        doThrow(new IllegalStateException("simulated audit failure")).when(auth).audit(any(),eq("DESKTOP_MODEL_PUBLISHED"),any());
        try{http.perform(post("/api/v1/desktop-model-releases").header("Authorization","Bearer model-session").header("X-FunnyWorker-Management","1").contentType("application/json").content(body(7)));}catch(jakarta.servlet.ServletException expected){}
        assertEquals(0,db.queryForObject("select count(*) from desktop_model_releases",Integer.class));
    }
    final Map<String,String> deviceKeys=new HashMap<>();
    Identity desktop(){return auth.authenticate("model-release-test-bootstrap-012345678901234567890");}
    String issue(Identity actor,String node)throws Exception {
        var key=KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded();
        String publicKey=deviceKeys.computeIfAbsent(node,ignored->Base64.getEncoder().encodeToString(Arrays.copyOfRange(key,key.length-32,key.length)));
        // Fixtures represent receipts issued before shared-key publication was retired.
        String digest=AuthService.hash(AuthService.id());
        receipts.issued(actor,node,AuthService.hash(publicKey),digest,System.currentTimeMillis(),System.currentTimeMillis()+86400000,releases.resolve(actor));
        return digest;
    }
    Map<?,?> device(Identity actor,String id){return (Map<?,?>)((List<?>)devices.list(actor,"","ALL",id).get("items")).get(0);}
    @Test void deviceReportsActualLoadingAndJoinsOnlyItsOwnPolicyReceipt()throws Exception {
        var actor=desktop();String id=AuthService.id(),node=AuthService.id().replace("-","");sync.registerDevice(actor,new DesktopHistorySync.Device(id,id,"darwin"));String digest=issue(actor,node);
        var pending=new DesktopModelReceipts.Receipt(node,id,7,digest,"0.2.1-alpha.1",false);
        http.perform(post("/api/account/model/receipt").header("Authorization","Bearer ruoyi.model-session").contentType("application/json").content(mapper.writeValueAsBytes(pending))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_APPLIED"));
        assertEquals("NOT_APPLIED",device(actor,id).get("modelStatus"));assertEquals("UNREPORTED",device(actor,id).get("policyStatus"));
        receipts.acknowledge(actor,new DesktopModelReceipts.Receipt(node,id,7,digest,"0.2.1-alpha.1",true));
        var policy=policies.issue(actor,node);policies.acknowledge(actor,new DesktopPolicies.Receipt(node,0,(String)policy.get("digest"),1,"0.2.1-alpha.1",new DesktopPolicies.Preferences(null,null)));
        var view=device(actor,id);assertEquals("APPLIED",view.get("modelStatus"));assertEquals("APPLIED",view.get("policyStatus"));assertEquals("0.2.1-alpha.1",view.get("client_version"));assertFalse(view.toString().contains("integration-secret"));assertEquals(7L,view.get("modelTargetVersion"));
        releases.publish(actor,new DesktopModelReleases.Settings(7L,"https://api.deepseek.com/v1","deepseek-reasoner","enabled",4096,"DEPLOYMENT_DEFAULT"));assertEquals("UPDATE_REQUIRED",device(actor,id).get("modelStatus"));
        assertEquals(409,assertThrows(ApiException.class,()->receipts.acknowledge(actor,pending)).status);
    }
    @Test void rejectsExpiredSupersededForeignAndRevokedDeviceReceipts()throws Exception {
        var actor=desktop();String id=AuthService.id(),node=AuthService.id().replace("-","");sync.registerDevice(actor,new DesktopHistorySync.Device(id,id,"darwin"));String digest=issue(actor,node);
        var old=new DesktopModelReceipts.Receipt(node,id,7,digest,"1",true);receipts.acknowledge(actor,old);
        String replacement=issue(actor,node);assertEquals("PENDING",device(actor,id).get("modelStatus"));assertEquals(409,assertThrows(ApiException.class,()->receipts.acknowledge(actor,old)).status);
        var fresh=new DesktopModelReceipts.Receipt(node,id,7,replacement,"1",true);receipts.acknowledge(actor,fresh);
        String foreignToken=AuthService.id();auth.create(AuthService.id(),actor.tenant(),"USER","Other member",Set.of("VIEWER"),Set.of("sandbox"),foreignToken);var foreign=auth.authenticate(foreignToken);
        assertEquals(404,assertThrows(ApiException.class,()->receipts.acknowledge(foreign,fresh)).status);
        db.update("update desktop_model_receipts set expires_at=0 where tenant_id=? and principal_id=? and device_id=?",actor.tenant(),actor.id(),node);assertEquals("EXPIRED",device(actor,id).get("modelStatus"));assertEquals(409,assertThrows(ApiException.class,()->receipts.acknowledge(actor,fresh)).status);
        sync.revokeDevice(actor,id);assertEquals(404,assertThrows(ApiException.class,()->receipts.acknowledge(actor,fresh)).status);
    }

}
