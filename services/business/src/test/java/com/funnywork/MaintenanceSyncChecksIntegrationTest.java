package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:syncchecks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.execution-enforcement=true"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class MaintenanceSyncChecksIntegrationTest {
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ManagementIdentity management;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @Autowired ControlService control;@Autowired ExecutionResources resources;@Autowired MaintenanceSyncChecks checks;@Autowired MaintenanceService maintenance;@Autowired AuthService auth;
    @Autowired DesktopHistorySync history;@Autowired MaintenanceDesktopChannel desktops;@Autowired DesktopInteractions interactions;
    @Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;
    @Autowired ExecutionPreview preview;
    Identity admin;String repo,project,pool,profile;List<MaintenanceSyncChecks.Target> targets;
    @BeforeEach void setup() throws Exception {
        String id=AuthService.id();auth.create(id,AuthService.id(),"USER","Admin",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("development"),id);admin=auth.authenticate(id);
        repo=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/project","main","development",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
        project=(String)((Map<?,?>)resources.create(admin,"projects",new ExecutionResources.Named("Project","development"))).get("id");
        pool=(String)((Map<?,?>)resources.create(admin,"pools",new ExecutionResources.Named("Pool","development"))).get("id");
        profile=(String)((Map<?,?>)resources.profile(admin,new ExecutionResources.Profile("Verify","development","VERIFY",Set.of("macos","windows","linux")))).get("id");
        resources.route(admin,new ExecutionResources.Route(repo,project,pool,profile,true,0));
        targets=new ArrayList<>();
        for(String platform:List.of("macos","windows","linux")){
            String device=AuthService.id();history.registerDevice(admin,new DesktopHistorySync.Device(device,platform,switch(platform){case "macos"->"darwin";case "windows"->"win32";default->"linux";}));desktops.bind(admin,new MaintenanceDesktopChannel.Binding(device,"development"));
            interactions.exchange(admin,device,state("runtime"));
            resources.member(admin,pool,new ExecutionResources.Member(device,true,0));resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",true,System.currentTimeMillis()+3600000,0));targets.add(new MaintenanceSyncChecks.Target(device,platform));
        }
    }
    DesktopInteractions.State state(String epoch) throws Exception {return new DesktopInteractions.State(epoch,mapper.readTree("{}"),mapper.readTree("[]"),false,false,mapper.readTree("{\"models\":[],\"presets\":[],\"maintenance\":{\"executor\":\"funnywork-native-v1\",\"gitVersion\":\"git 2.0\"},\"maintenanceExecutors\":[{\"executor\":\"funnywork-codex-v1\",\"protocolVersion\":1,\"executorVersion\":\"1\",\"gitVersion\":\"git 2.0\",\"capabilities\":[\"sync-check\"]}]}"));}
    MaintenanceSyncChecks.Create request(){return new MaintenanceSyncChecks.Create(repo,"a".repeat(40),"1.2.3","funnywork-codex-v1",1,targets);}
    Map<?,?> create(){return (Map<?,?>)checks.create(admin,"key",request());}
    void allowDevelopment(){
        String develop=(String)((Map<?,?>)resources.profile(admin,new ExecutionResources.Profile("Develop","development","DEVELOP",Set.of("macos","windows","linux")))).get("id");
        resources.route(admin,new ExecutionResources.Route(repo,project,pool,develop,true,0));
        for(var target:targets)resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,develop,target.deviceId(),"ALLOW",true,System.currentTimeMillis()+3600000,0));
    }
    Map<?,?> previewDevice(String purpose){
        var result=(Map<?,?>)preview.preview(admin,new ExecutionPreview.Request(repo,"VERIFY","macos","","",purpose));
        return ((List<Map<?,?>>)result.get("items")).stream().filter(row->targets.get(0).deviceId().equals(row.get("id"))).findFirst().orElseThrow();
    }
    MaintenanceSyncChecks.Receipt receipt(Map<?,?> payload,String outcome,boolean stopped){return new MaintenanceSyncChecks.Receipt("runtime",(String)payload.get("specHash"),outcome,"1.2.3","a".repeat(40),Set.of("sync-check"),"Version probe completed","1","0.153.4","b".repeat(64),2,stopped?"completed":"unknown",stopped,"d".repeat(40),false);}
    @Test void createsExactlyThreeBoundedChecksAndAcceptsIdempotentEvidence() throws Exception {
        var job=create();assertEquals(3,((List<?>)job.get("targets")).size());assertEquals(job.get("id"),create().get("id"));
        String device=targets.get(0).deviceId();var commands=interactions.exchange(admin,device,state("runtime"));assertEquals(1,commands.size());var command=commands.get(0);assertEquals("maintenance-sync-check",command.get("kind"));
        var payload=(Map<?,?>)command.get("payload");assertEquals(60000,payload.get("timeoutMs"));assertFalse(payload.containsKey("prompt"));assertEquals("funnywork-codex-v1",payload.get("executor"));assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
        String target=(String)command.get("id");assertEquals(false,((Map<?,?>)checks.receipt(admin,device,target,receipt(payload,"CHECKED",true))).get("duplicate"));assertEquals(true,((Map<?,?>)checks.receipt(admin,device,target,receipt(payload,"CHECKED",true))).get("duplicate"));
        var detail=(Map<?,?>)checks.detail(admin,(String)job.get("id"));assertTrue(((List<Map<?,?>>)detail.get("targets")).stream().anyMatch(t->Boolean.TRUE.equals(t.get("aligned"))));
        assertEquals(0,db.queryForObject("select count(*) from maintenance_problems where tenant_id=?",Integer.class,admin.tenant()));
        assertEquals(0,db.queryForObject("select count(*) from execution_routes where tenant_id=? and action='DEVELOP'",Integer.class,admin.tenant()));
        assertEquals(0,db.queryForObject("select count(*) from execution_writers where tenant_id=?",Integer.class,admin.tenant()));
    }
    @Test void readOnlyPreviewUsesVerifyAndCodexCapabilityWithoutRelaxingNativeVerify() {
        String device=targets.get(0).deviceId();
        db.update("update nodes set status='DRAINING' where id=(select node_id from maintenance_desktop_bindings where device_id=?)",device);
        assertEquals(true,previewDevice("SYNC_CHECK").get("available"));
        assertEquals(false,previewDevice(null).get("available"));
        assertTrue(((List<?>)previewDevice(null).get("reasons")).contains("EXECUTOR_MISMATCH"));
        assertThrows(ApiException.class,()->preview.preview(admin,new ExecutionPreview.Request(repo,"DEVELOP","macos","","","SYNC_CHECK")));
        db.update("update sync_devices set model_choices_json='{}' where id=?",device);
        assertEquals(false,previewDevice("SYNC_CHECK").get("available"));
    }
    @Test void developmentOnlyAndMissingAuthorizationCannotCreateOrPreviewChecks() {
        resources.route(admin,new ExecutionResources.Route(repo,project,pool,profile,false,1));
        assertThrows(ApiException.class,this::create);
        allowDevelopment();
        assertThrows(ApiException.class,this::create);
        assertEquals(false,previewDevice("SYNC_CHECK").get("authorized"));
        assertEquals(0,db.queryForObject("select count(*) from maintenance_sync_checks where tenant_id=?",Integer.class,admin.tenant()));
    }
    @Test void unrelatedNativeCapacityDoesNotHideAnIdleSyncCheckDevice() throws Exception {
        allowDevelopment();db.update("update tenant_guards set max_concurrency=1 where tenant_id=?",admin.tenant());
        maintenance.create(admin,"native",new MaintenanceService.Problem(repo,"Native fix","Fix menu","Tests pass","windows","a".repeat(40)));
        assertEquals(1,control.tickTenant(admin.tenant()));
        assertEquals("maintenance",interactions.exchange(admin,targets.get(1).deviceId(),state("runtime")).get(0).get("kind"));
        create();assertEquals(true,previewDevice("SYNC_CHECK").get("available"));
        assertEquals("maintenance-sync-check",interactions.exchange(admin,targets.get(0).deviceId(),state("runtime")).get(0).get("kind"));
        assertEquals(false,previewDevice("SYNC_CHECK").get("available"));
    }
    @Test void grantsForAnotherRepositoryOrDeviceDoNotAuthorizeTheTarget() {
        String other=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/other","main","development",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
        resources.route(admin,new ExecutionResources.Route(other,project,pool,profile,true,0));
        assertThrows(ApiException.class,()->checks.create(admin,"other",new MaintenanceSyncChecks.Create(other,"a".repeat(40),"1.2.3","funnywork-codex-v1",1,targets)));
        String device=targets.get(0).deviceId();resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",false,System.currentTimeMillis()+3600000,1));
        assertThrows(ApiException.class,this::create); // Other devices retain valid grants in the same pool.
    }
    @Test void expiredVerifyGrantBlocksDispatchEvenWithDevelopmentAuthority() throws Exception {
        create();allowDevelopment();String device=targets.get(0).deviceId();
        resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",true,1,1));
        assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
        assertEquals("BLOCKED",db.queryForObject("select state from maintenance_sync_check_targets where device_id=?",String.class,device));
    }
    @Test void expiredVerifyGrantRejectsLeaseAndReceiptButAllowsStopEvidence() throws Exception {
        create();allowDevelopment();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);var payload=(Map<?,?>)command.get("payload");String target=(String)command.get("id");
        resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",true,1,1));
        assertThrows(ApiException.class,()->checks.renew(admin,device,target,new MaintenanceSyncChecks.Renewal("runtime",(String)payload.get("specHash"))));
        assertThrows(ApiException.class,()->checks.receipt(admin,device,target,receipt(payload,"CHECKED",true)));
        checks.stopped(admin,device,target,new MaintenanceSyncChecks.Stopped("runtime",(String)payload.get("specHash"),"c".repeat(64)));
        assertEquals("STOPPED",db.queryForObject("select state from maintenance_sync_check_targets where id=?",String.class,target));
        assertNull(db.queryForObject("select receipt_hash from maintenance_sync_check_targets where id=?",String.class,target));
    }
    @Test void rejectsUnknownExecutorProtocolAndDuplicatePlatforms() throws Exception {
        var choices=(com.fasterxml.jackson.databind.node.ObjectNode)state("runtime").choices();((com.fasterxml.jackson.databind.node.ObjectNode)choices.path("maintenanceExecutors").get(0)).put("protocolVersion",2);
        assertThrows(ApiException.class,()->interactions.exchange(admin,targets.get(0).deviceId(),new DesktopInteractions.State("runtime",mapper.createObjectNode(),mapper.createArrayNode(),false,false,choices)));
        targets.set(2,targets.get(0));assertThrows(ApiException.class,this::create);
    }
    @Test void rejectsForeignTenantAndNonAdmin() {
        var job=create();String id=AuthService.id();auth.create(id,AuthService.id(),"USER","Other",Set.of("ADMIN"),Set.of("development"),id);Identity other=auth.authenticate(id);
        assertThrows(ApiException.class,()->checks.detail(other,(String)job.get("id")));assertThrows(ApiException.class,()->checks.capabilities(other,targets.get(0).deviceId()));assertThrows(ApiException.class,()->checks.create(other,"key",request()));
        String viewer=AuthService.id();auth.create(viewer,admin.tenant(),"USER","Viewer",Set.of("VIEWER"),Set.of("development"),viewer);assertThrows(ApiException.class,()->checks.capabilities(auth.authenticate(viewer),targets.get(0).deviceId()));
    }
    @Test void oldRuntimeAndExpiredReceiptsCannotComplete() throws Exception {
        create();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);var payload=(Map<?,?>)command.get("payload");
        interactions.exchange(admin,device,state("new-runtime"));assertThrows(ApiException.class,()->checks.receipt(admin,device,(String)command.get("id"),receipt(payload,"CHECKED",true)));
        String another=targets.get(1).deviceId();var other=interactions.exchange(admin,another,state("runtime")).get(0);db.update("update maintenance_sync_check_targets set expires_at=0 where id=?",other.get("id"));assertThrows(ApiException.class,()->checks.receipt(admin,another,(String)other.get("id"),receipt((Map<?,?>)other.get("payload"),"CHECKED",true)));
    }
    @Test void unknownIsTerminalAndUnstoppedCannotClaimSuccess() throws Exception {
        create();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);var payload=(Map<?,?>)command.get("payload");String target=(String)command.get("id");
        assertThrows(ApiException.class,()->checks.receipt(admin,device,target,receipt(payload,"CHECKED",false)));
        checks.receipt(admin,device,target,receipt(payload,"UNKNOWN",false));assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());assertEquals("UNKNOWN",db.queryForObject("select state from maintenance_sync_check_targets where id=?",String.class,target));
    }
    @Test void revokedCreatorOrBindingCannotDispatch() throws Exception {
        create();String device=targets.get(0).deviceId();db.update("update maintenance_desktop_bindings set enabled=false where device_id=?",device);assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
    }
    @Test void grantRevokedAfterCreationBlocksDispatch() throws Exception {
        create();String device=targets.get(0).deviceId();resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",false,System.currentTimeMillis()+3600000,1));
        assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());assertEquals(1,db.queryForObject("select count(*) from maintenance_sync_check_targets where device_id=? and state='BLOCKED'",Integer.class,device));
    }
    @Test void unknownAndExpiredClaimKeepNextTaskQuarantinedUntilStopped() throws Exception {
        create();String device=targets.get(0).deviceId();var first=interactions.exchange(admin,device,state("runtime")).get(0);var payload=(Map<?,?>)first.get("payload");String target=(String)first.get("id");
        checks.create(admin,"second",request());
        db.update("update maintenance_sync_check_targets set expires_at=0,lease_expires_at=0 where id=?",target);
        assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
        assertThrows(ApiException.class,()->checks.renew(admin,device,target,new MaintenanceSyncChecks.Renewal("runtime",(String)payload.get("specHash"))));
        db.update("update maintenance_sync_check_targets set state='UNKNOWN' where id=?",target);
        assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
        assertThrows(ApiException.class,()->checks.stopped(admin,targets.get(1).deviceId(),target,new MaintenanceSyncChecks.Stopped("runtime",(String)payload.get("specHash"),"c".repeat(64))));
        checks.stopped(admin,device,target,new MaintenanceSyncChecks.Stopped("runtime",(String)payload.get("specHash"),"c".repeat(64)));
        assertEquals(1,interactions.exchange(admin,device,state("runtime")).size());
    }
    @Test void renewalRechecksGrantAndStoppedEvidenceWorksAfterRevocation() throws Exception {
        create();allowDevelopment();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);var payload=(Map<?,?>)command.get("payload");String target=(String)command.get("id");
        var renewal=new MaintenanceSyncChecks.Renewal("runtime",(String)payload.get("specHash"));assertEquals(15000L,((Map<?,?>)checks.renew(admin,device,target,renewal)).get("leaseDurationMs"));
        resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,device,"ALLOW",false,System.currentTimeMillis()+3600000,1));
        assertThrows(ApiException.class,()->checks.renew(admin,device,target,renewal));assertThrows(ApiException.class,()->checks.receipt(admin,device,target,receipt(payload,"CHECKED",true)));
        var stopped=new MaintenanceSyncChecks.Stopped("runtime",(String)payload.get("specHash"),"c".repeat(64));checks.stopped(admin,device,target,stopped);
        assertEquals(true,((Map<?,?>)checks.stopped(admin,device,target,stopped)).get("duplicate"));assertEquals("STOPPED",db.queryForObject("select state from maintenance_sync_check_targets where id=?",String.class,target));
        assertNull(db.queryForObject("select receipt_hash from maintenance_sync_check_targets where id=?",String.class,target));
    }
    @Test void nativeAndCodexCannotBeClaimedTogetherOrAcrossConcurrentExchanges() throws Exception {
        allowDevelopment();
        create();maintenance.create(admin,"native",new MaintenanceService.Problem(repo,"Native fix","Fix menu","Tests pass","macos","a".repeat(40)));
        assertEquals(1,control.tickTenant(admin.tenant()));String device=targets.get(0).deviceId();
        var commands=interactions.exchange(admin,device,state("runtime"));assertEquals(1,commands.size());assertEquals("maintenance",commands.get(0).get("kind"));
        assertEquals("QUEUED",db.queryForObject("select state from maintenance_sync_check_targets where device_id=?",String.class,device));
        String windows=targets.get(1).deviceId();var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=executor.submit(()->interactions.exchange(admin,windows,state("runtime")));var b=executor.submit(()->interactions.exchange(admin,windows,state("runtime")));assertEquals(1,a.get().size()+b.get().size());}finally{executor.shutdownNow();}
    }
    @Test void codexUnknownQuarantinesNativeSchedulerAndDispatch() throws Exception {
        allowDevelopment();
        create();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);
        checks.receipt(admin,device,(String)command.get("id"),receipt((Map<?,?>)command.get("payload"),"UNKNOWN",false));
        maintenance.create(admin,"native",new MaintenanceService.Problem(repo,"Native fix","Fix menu","Tests pass","macos","a".repeat(40)));
        assertEquals(0,control.tickTenant(admin.tenant()));assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty());
    }
    void withoutEnforcement(org.junit.jupiter.api.function.Executable check) throws Throwable {
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("sync-check-test",Map.of("funnywork.execution-enforcement","false")));
        try{check.execute();}finally{environment.getPropertySources().remove("sync-check-test");}
    }
    @Test void disabledEnforcementRejectsCreation() throws Throwable {
        withoutEnforcement(()->assertEquals("SYNC_CHECK_ENFORCEMENT_REQUIRED",assertThrows(ApiException.class,this::create).code));
        assertEquals(0,db.queryForObject("select count(*) from maintenance_sync_checks where tenant_id=?",Integer.class,admin.tenant()));
    }
    @Test void disabledEnforcementBlocksDispatch() throws Throwable {
        create();String device=targets.get(0).deviceId();
        withoutEnforcement(()->assertTrue(interactions.exchange(admin,device,state("runtime")).isEmpty()));
        assertEquals("BLOCKED",db.queryForObject("select state from maintenance_sync_check_targets where device_id=?",String.class,device));
    }
    @Test void disabledEnforcementRejectsReceipt() throws Throwable {
        create();String device=targets.get(0).deviceId();var command=interactions.exchange(admin,device,state("runtime")).get(0);
        withoutEnforcement(()->assertEquals("SYNC_CHECK_ENFORCEMENT_REQUIRED",assertThrows(ApiException.class,()->checks.receipt(admin,device,(String)command.get("id"),receipt((Map<?,?>)command.get("payload"),"CHECKED",true))).code));
        assertNull(db.queryForObject("select receipt_hash from maintenance_sync_check_targets where id=?",String.class,command.get("id")));
    }
    @Test void managementSessionRequiresExplicitResourceWriteAndListsScopedHistory() throws Exception {
        db.update("insert into management_identity_links values(?,?,?)",admin.tenant(),"sync-admin",admin.id());
        org.mockito.Mockito.doReturn(new ManagementIdentity.Authority(admin.tenant(),"sync-admin",Set.of("funnyworker:resources:read"))).when(management).authority("sync-management-session");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/maintenance/sync-checks").header("Authorization","Bearer sync-management-session").header("X-FunnyWorker-Management","1")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/maintenance/sync-checks").header("Authorization","Bearer sync-management-session").header("X-FunnyWorker-Management","1").header("Idempotency-Key","management").contentType("application/json").content(mapper.writeValueAsString(request()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        org.mockito.Mockito.doReturn(new ManagementIdentity.Authority(admin.tenant(),"sync-admin",Set.of("funnyworker:resources:read","funnyworker:resources:write"))).when(management).authority("sync-management-session");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/maintenance/sync-checks").header("Authorization","Bearer sync-management-session").header("X-FunnyWorker-Management","1").header("Idempotency-Key","management").contentType("application/json").content(mapper.writeValueAsString(request()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(1,((List<?>)checks.list(admin)).size());
        assertThrows(ApiException.class,()->ManagementIdentity.permission("POST","/api/v1/maintenance/sync-checks/unknown/action"));
    }
    @Test void httpRequiresAuthenticationAndValidBoundedRequest() throws Exception {
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/maintenance/sync-checks/devices/"+targets.get(0).deviceId()+"/capabilities")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+3600000);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/maintenance/sync-checks").header("Authorization","Bearer "+token).header("Idempotency-Key","http-key").contentType("application/json").content(mapper.writeValueAsString(request()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var wrongVersion=mapper.valueToTree(request());((com.fasterxml.jackson.databind.node.ObjectNode)wrongVersion).put("expectedVersion","v1.2.3");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/maintenance/sync-checks").header("Authorization","Bearer "+token).header("Idempotency-Key","invalid-version").contentType("application/json").content(mapper.writeValueAsString(wrongVersion))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var malformed=mapper.valueToTree(request());((com.fasterxml.jackson.databind.node.ObjectNode)malformed).put("baseSha","main");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/maintenance/sync-checks").header("Authorization","Bearer "+token).header("Idempotency-Key","invalid-key").contentType("application/json").content(mapper.writeValueAsString(malformed))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void capabilitiesAreReadOnlyAndStaleDeviceCannotCreate() {
        String device=targets.get(0).deviceId();assertEquals(true,((Map<?,?>)checks.capabilities(admin,device)).get("available"));db.update("update sync_devices set state_seen=0 where id=?",device);assertEquals(false,((Map<?,?>)checks.capabilities(admin,device)).get("available"));assertThrows(ApiException.class,this::create);
    }
}
