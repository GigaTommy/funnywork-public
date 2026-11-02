package com.funnywork;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:execution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ExecutionResourcesIntegrationTest {
 @Autowired ExecutionResources resources;
 @Autowired ExecutionPreview preview;
 @Autowired MaintenanceService maintenance;
 @Autowired MaintenanceDesktopChannel desktops;
 @Autowired DesktopHistorySync history;
 @Autowired DesktopInteractions interactions;
 @Autowired ControlService control;
 @Autowired AuthService auth;
 @Autowired JdbcTemplate db;
 @Autowired Json json;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ManagementIdentity management;
 @Autowired MockMvc http;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
 Identity admin;String tenant,credential,repo,project,pool,profile,deviceA,deviceB,deviceU,task,attempt,hash;long epoch;
 String id(Object result){return (String)((Map<?,?>)result).get("id");}
 @BeforeEach void setup() throws Exception {
  tenant=AuthService.id();credential=AuthService.id();auth.create(credential,tenant,"USER","Policy owner",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("development"),credential);admin=auth.authenticate(credential);
  repo=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/project","main","development",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
  project=id(resources.create(admin,"projects",new ExecutionResources.Named("Project A","development")));
  pool=id(resources.create(admin,"pools",new ExecutionResources.Named("A development","development")));
  profile=id(resources.profile(admin,new ExecutionResources.Profile("Develop","development","DEVELOP",Set.of("macos"))));
  resources.route(admin,new ExecutionResources.Route(repo,project,pool,profile,true,0));
  deviceA=device("A");deviceB=device("B");deviceU=device("Unapproved");
  resources.member(admin,pool,new ExecutionResources.Member(deviceA,true,0));
  resources.member(admin,pool,new ExecutionResources.Member(deviceU,true,0));
  String poolB=id(resources.create(admin,"pools",new ExecutionResources.Named("B development","development")));
  resources.member(admin,poolB,new ExecutionResources.Member(deviceB,true,0));
  grant(true,0);
 }
 String device(String name) throws Exception {String d=AuthService.id();history.registerDevice(admin,new DesktopHistorySync.Device(d,name,"darwin"));desktops.bind(admin,new MaintenanceDesktopChannel.Binding(d,"development"));interactions.exchange(admin,d,state(d));return d;}
 DesktopInteractions.State state(String d) throws Exception {return new DesktopInteractions.State(d,mapper.readTree("{}"),mapper.readTree("[]"),false,true,mapper.readTree("{\"models\":[],\"presets\":[],\"maintenance\":{\"executor\":\"funnywork-native-v1\",\"gitVersion\":\"git-test\",\"delivery\":true,\"triage\":true}}"));}
 void grant(boolean enabled,int version){resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceA,"ALLOW",enabled,System.currentTimeMillis()+3600000,version));}
 String queue(String key){String created=(String)maintenance.create(admin,key,new MaintenanceService.Problem(repo,"Short menu","Replace the long menu title","Menu shows Tasks","macos","a".repeat(40))).get("taskId");assertEquals(project,db.queryForObject("select project_id from task_creation_attribution where task_id=?",String.class,created));return created;}
 void start() throws Exception {task=queue(AuthService.id());assertEquals(1,control.tickTenant(tenant));var frames=interactions.exchange(admin,deviceA,state(deviceA));assertEquals(1,frames.size());attempt=(String)frames.get(0).get("id");var payload=(Map<?,?>)frames.get(0).get("payload");epoch=((Number)payload.get("epoch")).longValue();hash=(String)payload.get("specHash");}
 MaintenanceDesktopChannel.Receipt receipt(String d){return new MaintenanceDesktopChannel.Receipt(d,new MaintenanceService.Report(epoch,hash,"CANDIDATE_READY","b".repeat(40),"https://github.com/example/project/pull/42","Short menu",List.of(new MaintenanceService.CheckResult("diff",0,"passed"))));}
 @Test void onlyAuthorizedAReceivesOneAttemptAndOtherDevicesCannotRenewOrSubmit() throws Exception {
  assertTrue(resources.enforced());start();assertTrue(interactions.exchange(admin,deviceB,state(deviceB)).isEmpty());assertTrue(interactions.exchange(admin,deviceU,state(deviceU)).isEmpty());
  assertEquals(1,db.queryForObject("select count(*) from execution_placements where task_id=?",Integer.class,task));
  assertThrows(ApiException.class,()->desktops.renew(admin,deviceB,attempt,new MaintenanceDesktopChannel.Renewal(deviceB,epoch)));
  assertThrows(ApiException.class,()->desktops.report(admin,deviceU,attempt,receipt(deviceU)));
  desktops.renew(admin,deviceA,attempt,new MaintenanceDesktopChannel.Renewal(deviceA,epoch));desktops.report(admin,deviceA,attempt,receipt(deviceA));resources.requireEvidence(task);
  assertEquals("RELEASED",db.queryForObject("select state from execution_writers where repository_id=?",String.class,repo));
 }
 @Test void noAuthorizedDeviceWaitsWithoutBroadcastOrFailedAttempt() throws Exception {
  grant(false,1);task=queue("waiting");assertEquals(0,control.tickTenant(tenant));for(String d:List.of(deviceA,deviceB,deviceU))assertTrue(interactions.exchange(admin,d,state(d)).isEmpty());
  assertEquals(0,db.queryForObject("select count(*) from attempts where tenant_id=?",Integer.class,tenant));
  assertEquals("WAITING_FOR_ELIGIBLE_RESOURCE",((Map<?,?>)resources.placement(admin,task)).get("state"));grant(true,2);db.update("update work_units set next_dispatch_at=0 where task_id=?",task);assertEquals(1,control.tickTenant(tenant));assertEquals(1,interactions.exchange(admin,deviceA,state(deviceA)).size());
 }
 @Test void poolMembershipIsInsufficientAndExplicitDenyOverridesPoolAllow() {
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,"","ALLOW",true,System.currentTimeMillis()+3600000,0));
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceA,"DENY",true,System.currentTimeMillis()+3600000,1));
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceU,"DENY",true,System.currentTimeMillis()+3600000,0));task=queue("denied");assertEquals(0,control.tickTenant(tenant));
 }
 @Test void removalAndReenrollmentCannotReviveOldLeaseAndUnknownWriterStaysLocked() throws Exception {
  start();resources.member(admin,pool,new ExecutionResources.Member(deviceA,false,1));
  assertThrows(ApiException.class,()->desktops.renew(admin,deviceA,attempt,new MaintenanceDesktopChannel.Renewal(deviceA,epoch)));
  resources.member(admin,pool,new ExecutionResources.Member(deviceA,true,2));assertThrows(ApiException.class,()->desktops.report(admin,deviceA,attempt,receipt(deviceA)));
  control.tickTenant(tenant);assertEquals("UNKNOWN",db.queryForObject("select state from execution_writers where repository_id=?",String.class,repo));
  String next=queue("second");assertEquals(0,control.tickTenant(tenant));assertEquals(0,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id where u.task_id=?",Integer.class,next));
 }
 @Test void candidateAndCompletedReceiptAreRejectedAfterGrantRevocation() throws Exception {
  start();desktops.report(admin,deviceA,attempt,receipt(deviceA));grant(false,1);
  assertThrows(ApiException.class,()->resources.requireEvidence(task));assertThrows(ApiException.class,()->desktops.report(admin,deviceA,attempt,receipt(deviceA)));
 }
 @Test void uploadIsBoundToCurrentDeviceHashLeaseAndPolicy() throws Exception {
  start();String bytes="candidate";String digest=AuthService.hash(bytes);
  var request=new ExecutionResources.UploadAuthorization(deviceA,epoch,hash,digest,32);String permit=id(desktops.authorizeUpload(admin,deviceA,attempt,request));
  assertThrows(ApiException.class,()->desktops.upload(admin,deviceB,attempt,new ExecutionResources.Upload(deviceB,epoch,hash,permit,"Y2FuZGlkYXRl")));
  assertThrows(ApiException.class,()->desktops.upload(admin,deviceA,attempt,new ExecutionResources.Upload(deviceA,epoch+1,hash,permit,"Y2FuZGlkYXRl")));
  assertThrows(ApiException.class,()->desktops.upload(admin,deviceA,attempt,new ExecutionResources.Upload(deviceA,epoch,hash,permit,"eA==")));
  var upload=new ExecutionResources.Upload(deviceA,epoch,hash,permit,"Y2FuZGlkYXRl");assertEquals(false,((Map<?,?>)desktops.upload(admin,deviceA,attempt,upload)).get("acceptedAsResult"));assertEquals(true,((Map<?,?>)desktops.upload(admin,deviceA,attempt,upload)).get("duplicate"));
  grant(false,1);assertThrows(ApiException.class,()->desktops.upload(admin,deviceA,attempt,upload));assertEquals(0,db.queryForObject("select count(*) from maintenance_reports where attempt_id=?",Integer.class,attempt));
 }
 @Test void expiresAndPurposeDisablePreventPlacement() {
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceA,"ALLOW",true,1,1));queue("expired");assertEquals(0,control.tickTenant(tenant));grant(true,2);resources.enable(admin,"profiles",profile,new ExecutionResources.Switch(false,1));assertEquals(0,control.tickTenant(tenant));
 }
 @Test void sameRepositoryCannotMapDifferentActionsToDifferentProjects() {
  String other=id(resources.create(admin,"projects",new ExecutionResources.Named("Project B","development")));String verify=id(resources.profile(admin,new ExecutionResources.Profile("Verify","development","VERIFY",Set.of("macos"))));
  assertThrows(ApiException.class,()->resources.route(admin,new ExecutionResources.Route(repo,other,pool,verify,true,0)));
  assertThrows(ApiException.class,()->resources.member(admin,pool,new ExecutionResources.Member(deviceA,false,0)));
 }
 @Test void tenantIsolationAndOperatorCannotMutatePolicy() throws Exception {
  String secret=AuthService.id();auth.create(secret,AuthService.id(),"USER","Other tenant",Set.of("ADMIN","OPERATOR"),Set.of("development"),secret);Identity other=auth.authenticate(secret);
  assertThrows(ApiException.class,()->resources.member(other,pool,new ExecutionResources.Member(deviceA,true,1)));
  String operator=AuthService.id();auth.create(operator,tenant,"USER","Reader",Set.of("OPERATOR"),Set.of("development"),operator);
  http.perform(get("/api/v1/execution-resources").header("Authorization","Bearer "+credential)).andExpect(status().isOk()).andExpect(jsonPath("$.enforced").value(true));
  http.perform(post("/api/v1/execution-pools").header("Authorization","Bearer "+operator).contentType("application/json").content("{\"name\":\"bad\",\"zone\":\"development\"}")).andExpect(status().isForbidden());
  http.perform(put("/api/v1/execution-profiles/"+profile+"/enabled").header("Authorization","Bearer "+credential).contentType("application/json").content("{\"enabled\":false,\"expectedVersion\":1}")).andExpect(status().isOk());
 }
 @Test void repositoryHasSingleWriterAcrossMultipleTasks() throws Exception {start();String second=queue("another");assertEquals(0,control.tickTenant(tenant));assertEquals(0,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id where u.task_id=?",Integer.class,second));desktops.report(admin,deviceA,attempt,receipt(deviceA));db.update("update work_units set next_dispatch_at=0 where task_id=?",second);assertEquals(1,control.tickTenant(tenant));}
 @Test void revokedReservationIsReleasedBeforeStartAndCanUseAnotherAuthorizedMember() throws Exception {
  task=queue("reserved");assertEquals(1,control.tickTenant(tenant));grant(false,1);
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceU,"ALLOW",true,System.currentTimeMillis()+3600000,0));
  db.update("update work_units set next_dispatch_at=0 where task_id=?",task);assertEquals(1,control.tickTenant(tenant));
  assertEquals(0,control.tickTenant(tenant));assertTrue(interactions.exchange(admin,deviceA,state(deviceA)).isEmpty());assertEquals(1,interactions.exchange(admin,deviceU,state(deviceU)).size());
 }
 @Test void expiredUploadAndWrongSpecCannotBeAccepted() throws Exception {
  start();String permit=id(desktops.authorizeUpload(admin,deviceA,attempt,new ExecutionResources.UploadAuthorization(deviceA,epoch,hash,AuthService.hash("candidate"),32)));
  assertThrows(ApiException.class,()->desktops.upload(admin,deviceA,attempt,new ExecutionResources.Upload(deviceA,epoch,"0".repeat(64),permit,"Y2FuZGlkYXRl")));
  db.update("update execution_upload_authorizations set expires_at=1 where id=?",permit);
  assertThrows(ApiException.class,()->desktops.upload(admin,deviceA,attempt,new ExecutionResources.Upload(deviceA,epoch,hash,permit,"Y2FuZGlkYXRl")));
 }
 @Test void disablingBoundNodeAlsoInvalidatesCandidateEvidence() throws Exception {
  start();desktops.report(admin,deviceA,attempt,receipt(deviceA));db.update("update nodes set admin_paused=true where id=(select node_id from maintenance_desktop_bindings where device_id=?)",deviceA);assertThrows(ApiException.class,()->resources.requireEvidence(task));
 }

 @Test void managementBridgeAllowsResourceReadButRequiresResourceWriteForPolicyChanges() throws Exception {
  db.update("insert into management_identity_links values(?,?,?)",tenant,"71",admin.id());
  org.mockito.Mockito.doReturn(new ManagementIdentity.Authority(tenant,"71",Set.of("funnyworker:resources:read"))).when(management).authority("execution-session");
  http.perform(get("/api/v1/execution-resources").header("Authorization","Bearer execution-session").header("X-FunnyWorker-Management","1")).andExpect(status().isOk());
  http.perform(post("/api/v1/execution-pools").header("Authorization","Bearer execution-session").header("X-FunnyWorker-Management","1").contentType("application/json").content("{\"name\":\"bad\",\"zone\":\"development\"}")).andExpect(status().isForbidden());
  assertEquals("funnyworker:resources:write",ManagementIdentity.permission("PUT","/api/v1/execution-pools/example/members"));
  assertEquals("funnyworker:tasks:read",ManagementIdentity.permission("GET","/api/v1/tasks/example/placement"));
 }

 Map<?,?> preview(){return (Map<?,?>)preview.preview(admin,new ExecutionPreview.Request(repo,"DEVELOP","macos","",""));}
 Map<?,?> candidate(Map<?,?> page,String d){return (Map<?,?>)((List<?>)page.get("items")).stream().filter(x->d.equals(((Map<?,?>)x).get("id"))).findFirst().orElseThrow();}
 @Test void previewUsesProjectAuthorizationWithoutReservingOrExposingTaskText(){
  var page=preview();assertEquals(false,page.get("reserved"));assertEquals(true,candidate(page,deviceA).get("available"));
  assertTrue(((List<?>)candidate(page,deviceB).get("reasons")).contains("NOT_MEMBER"));
  assertTrue(((List<?>)candidate(page,deviceU).get("reasons")).contains("NO_VALID_GRANT"));
  assertEquals(0,db.queryForObject("select count(*) from attempts where tenant_id=?",Integer.class,tenant));
  assertFalse(json.write(page).contains("specHash"));
 }
 @Test void previewSeparatesPolicyDenialFromAvailability(){
  resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,profile,deviceA,"DENY",true,System.currentTimeMillis()+3600000,1));
  assertTrue(((List<?>)candidate(preview(),deviceA).get("reasons")).contains("EXPLICIT_DENY"));
  grant(true,2);db.update("update nodes set last_seen=1 where id=(select node_id from maintenance_desktop_bindings where device_id=?)",deviceA);
  var a=candidate(preview(),deviceA);assertEquals(true,a.get("authorized"));assertEquals(false,a.get("available"));assertTrue(((List<?>)a.get("reasons")).contains("OFFLINE"));
  resources.enable(admin,"profiles",profile,new ExecutionResources.Switch(false,1));assertTrue(((List<?>)candidate(preview(),deviceA).get("reasons")).contains("PROFILE_DISABLED"));
 }
 @Test void previewReportsSharedCapacityAndWriterBusy() throws Exception {
  start();var a=candidate(preview(),deviceA);assertEquals(true,a.get("authorized"));assertTrue(((List<?>)a.get("reasons")).containsAll(List.of("AT_CAPACITY","WRITER_BUSY")));
  var placement=(Map<?,?>)resources.placement(admin,task);var record=(Map<?,?>)((List<?>)placement.get("placements")).get(0);assertEquals("A",record.get("deviceName"));assertEquals("Project A",record.get("projectName"));assertEquals(1,record.get("profileRevision"));
 }
 @Test void previewIsTenantScopedAndReadPermissionDoesNotGrantMutations() throws Exception {
  String secret=AuthService.id();auth.create(secret,AuthService.id(),"USER","Other tenant",Set.of("ADMIN","OPERATOR"),Set.of("development"),secret);
  assertThrows(ApiException.class,()->preview.preview(auth.authenticate(secret),new ExecutionPreview.Request(repo,"DEVELOP","macos","","")));
  db.update("insert into management_identity_links values(?,?,?)",tenant,"72",admin.id());
  org.mockito.Mockito.doReturn(new ManagementIdentity.Authority(tenant,"72",Set.of("funnyworker:resources:read"))).when(management).authority("preview-session");
  http.perform(post("/api/v1/execution-placement/preview").header("Authorization","Bearer preview-session").header("X-FunnyWorker-Management","1").contentType("application/json").content(json.write(new ExecutionPreview.Request(repo,"DEVELOP","macos","","")))).andExpect(status().isOk()).andExpect(jsonPath("$.reserved").value(false));
  assertEquals("funnyworker:resources:read",ManagementIdentity.permission("POST","/api/v1/execution-placement/preview"));
  assertEquals("funnyworker:resources:read",ManagementIdentity.permission("GET","/api/v1/execution-resources/devices"));
 }
 @Test void deviceCatalogPaginatesWithoutDroppingOrDuplicatingVisibleDevices(){
  for(int i=0;i<51;i++)history.registerDevice(admin,new DesktopHistorySync.Device(AuthService.id(),"Catalog fixture "+i,"darwin"));
  var first=(Map<?,?>)preview.devices(admin,"","");assertEquals(50,((List<?>)first.get("items")).size());assertEquals(true,first.get("hasMore"));
  var second=(Map<?,?>)preview.devices(admin,(String)first.get("after"),"");assertEquals(4,((List<?>)second.get("items")).size());
  var ids=new HashSet<Object>();for(var page:List.of(first,second))for(var item:(List<?>)page.get("items"))assertTrue(ids.add(((Map<?,?>)item).get("id")));
 }

 @Test void taskPlacementPreservesExistingTaskOwnerBoundaryAndViewerReadAccess() throws Exception {
  start();String secret=AuthService.id();auth.create(secret,tenant,"USER","Another administrator",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("development"),secret);
  assertThrows(ApiException.class,()->resources.placement(auth.authenticate(secret),task));
  db.update("update principals set roles=?,auth_version=auth_version+1 where id=?",json.write(Set.of("VIEWER")),admin.id());
  assertEquals("ASSIGNED",((Map<?,?>)resources.placement(auth.authenticate(credential),task)).get("state"));
 }

 @Test void resourceViewerCanInspectButCannotChangePolicy() throws Exception {
  String secret=AuthService.id();auth.create(secret,tenant,"USER","Resource observer",Set.of("VIEWER"),Set.of("development"),secret);Identity viewer=auth.authenticate(secret);
  assertNotNull(resources.overview(viewer));assertNotNull(preview.devices(viewer,"",""));
  assertNotNull(preview.preview(viewer,new ExecutionPreview.Request(repo,"DEVELOP","macos","","")));
  assertThrows(ApiException.class,()->resources.enable(viewer,"pools",pool,new ExecutionResources.Switch(false,1)));
 }

 @Test void previewIncludesExistingTenantAndAgentQuotaGates() throws Exception {
  start();db.update("update tenant_guards set max_concurrency=1 where tenant_id=?",tenant);
  assertTrue(((List<?>)candidate(preview(),deviceA).get("reasons")).contains("TENANT_AT_CAPACITY"));
  assertEquals(true,candidate(preview(),deviceA).get("authorized"));
 }

}
