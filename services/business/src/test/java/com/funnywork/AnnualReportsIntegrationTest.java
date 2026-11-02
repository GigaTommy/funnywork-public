package com.funnywork;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_WORKFLOW_DB_URL:jdbc:h2:mem:annual;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class AnnualReportsIntegrationTest {
 @Autowired ManagementTaskWorkflow workflow;
 @Autowired ManagementAudit audit;@Autowired MaintenanceService maintenance;@Autowired AnnualReports reports;@Autowired ExecutionResources resources;@Autowired ExecutionPreview preview;
 @Autowired MaintenanceDesktopChannel desktops;@Autowired DesktopHistorySync history;@Autowired DesktopInteractions interactions;
 @Autowired ControlService control;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired Json json;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;@Autowired ArtifactService artifacts;
 Identity admin;String tenant,project,pool,profile,dataset,device,other,task,attempt,hash,snapshot;long epoch;
 String id(Object r){return (String)((Map<?,?>)r).get("id");}
 @BeforeEach void setup() throws Exception {
  tenant=AuthService.id();String token=AuthService.id();auth.create(token,tenant,"USER","Report owner",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("development"),token);admin=auth.authenticate(token);
  project=id(resources.create(admin,"projects",new ExecutionResources.Named("Annual project","development")));pool=id(resources.create(admin,"pools",new ExecutionResources.Named("Report pool","development")));
  profile=id(resources.profile(admin,new ExecutionResources.Profile("Annual compilation","development","COMPILE",Set.of("macos"))));
  var created=(Map<?,?>)reports.createDataset(admin,new AnnualReports.Dataset("Example company",2025,project,List.of(new AnnualReports.Material("r1","Revenue","REVENUE",new BigDecimal("120.10")),new AnnualReports.Material("c1","Cost","COST",new BigDecimal("40.00")),new AnnualReports.Material("p1","Prior revenue","PRIOR_REVENUE",new BigDecimal("100.00")))));dataset=(String)created.get("id");snapshot=(String)created.get("snapshotHash");
  resources.route(admin,new ExecutionResources.Route(dataset,project,pool,profile,true,0));device=device("Report Mac");other=device("Unapproved Mac");
  resources.member(admin,pool,new ExecutionResources.Member(device,true,0));resources.member(admin,pool,new ExecutionResources.Member(other,true,0));grant(true,0);
 }
 String device(String name)throws Exception{String id=AuthService.id();history.registerDevice(admin,new DesktopHistorySync.Device(id,name,"darwin"));desktops.bind(admin,new MaintenanceDesktopChannel.Binding(id,"development"));interactions.exchange(admin,id,state(id,true));return id;}
 DesktopInteractions.State state(String id,boolean capable)throws Exception{return new DesktopInteractions.State(id,mapper.readTree("{}"),mapper.readTree("[]"),false,true,mapper.readTree("{\"models\":[],\"presets\":[],\"workspaceOperations\":true,\"annualReport\":\""+(capable?"annual-report.v1":"")+"\",\"maintenance\":{\"executor\":\"funnywork-native-v1\",\"gitVersion\":\"git-test\",\"delivery\":true}}"));}
 void grant(boolean enabled,int revision){resources.grant(admin,new ExecutionResources.Grant(project,dataset,pool,profile,device,"ALLOW",enabled,System.currentTimeMillis()+3600000,revision));}
 String queue(String key){String created=(String)((Map<?,?>)reports.compile(admin,key,new AnnualReports.Compile(dataset,"macos"))).get("taskId");assertEquals(project,db.queryForObject("select project_id from task_creation_attribution where task_id=?",String.class,created));return created;}
 void start()throws Exception{task=queue("report");assertEquals(1,control.tickTenant(tenant));var frames=interactions.exchange(admin,device,state(device,true));assertEquals(1,frames.size());assertEquals("annual-report",frames.get(0).get("kind"));attempt=(String)frames.get(0).get("id");var p=(Map<?,?>)frames.get(0).get("payload");epoch=((Number)p.get("epoch")).longValue();hash=(String)p.get("specHash");assertFalse(json.write(p).contains("baseSha"));}
 String candidate(){return json.write(Map.of("schema","annual-candidate.v1","snapshotHash",snapshot,"sourceIds",List.of("c1","p1","r1"),"facts",Map.of("revenue","120.10","cost","40.00","profit","80.10","priorRevenue","100.00","growthPercent","20.10")));}
 AnnualReports.Candidate receipt(String content){return new AnnualReports.Candidate(epoch,device,hash,content);}
 @Test void directedReportIsIndependentlyVerifiedAndDeliveredWithoutRepository()throws Exception{
  start();var result=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate()));assertEquals("DELIVERED",result.get("state"));var flow=(Map<?,?>)workflow.inspect(admin,task);assertEquals("SERVER_VERIFIED",flow.get("validation"));assertEquals("DELIVERED",flow.get("delivery"));assertFalse(json.write(flow).contains("120.10"));
  assertEquals("SUCCEEDED",db.queryForObject("select state from tasks where id=?",String.class,task));assertEquals(0,db.queryForObject("select count(*) from maintenance_repositories where tenant_id=?",Integer.class,tenant));
  String content=new String(Base64.getDecoder().decode((String)artifacts.read(admin,(String)result.get("artifactId"),1).get("content_base64")),java.nio.charset.StandardCharsets.UTF_8);assertTrue(content.contains("80.10"));assertTrue(content.contains("20.10%"));
  assertEquals(true,((Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate()))).get("duplicate"));
 }
 @Test void incorrectNumbersAreRejectedWithoutFormalArtifact()throws Exception{start();var r=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("80.10","999.00")));assertEquals("REJECTED",r.get("state"));assertEquals("NOT_CONFIRMED",((Map<?,?>)workflow.inspect(admin,task)).get("delivery"));assertEquals("",r.get("artifactId"));assertEquals("FAILED",db.queryForObject("select state from tasks where id=?",String.class,task));}
 @Test void extraNarrativeOrSourceCannotEnterDelivery()throws Exception{start();var r=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("\"schema\":","\"narrative\":\"unlimited access\",\"schema\":")));assertEquals("REJECTED",r.get("state"));}
 @Test void noGrantWaitsAndDoesNotBroadcastMaterials()throws Exception{grant(false,1);task=queue("wait");assertEquals(0,control.tickTenant(tenant));assertTrue(interactions.exchange(admin,device,state(device,true)).isEmpty());assertTrue(interactions.exchange(admin,other,state(other,true)).isEmpty());assertEquals("WAITING_FOR_ELIGIBLE_RESOURCE",((Map<?,?>)resources.placement(admin,task)).get("state"));}
 @Test void unapprovedTerminalCannotRenewOrUpload()throws Exception{start();assertThrows(ApiException.class,()->desktops.renew(admin,other,attempt,new MaintenanceDesktopChannel.Renewal(other,epoch)));assertThrows(ApiException.class,()->desktops.annualCandidate(admin,other,attempt,new AnnualReports.Candidate(epoch,other,hash,candidate())));}
 @Test void revokingMaterialBlocksRenewalAndCandidateEvenAfterReenable()throws Exception{start();reports.revoke(admin,dataset,new ExecutionResources.Switch(false,1));assertThrows(ApiException.class,()->desktops.renew(admin,device,attempt,new MaintenanceDesktopChannel.Renewal(device,epoch)));reports.revoke(admin,dataset,new ExecutionResources.Switch(true,2));assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,attempt,receipt(candidate())));}
 @Test void oldEpochAndSpecCannotSubmit()throws Exception{start();assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,attempt,new AnnualReports.Candidate(epoch+1,device,hash,candidate())));assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,attempt,new AnnualReports.Candidate(epoch,device,"a".repeat(64),candidate())));}
 @Test void samePhysicalNodeCapacityIsNotDuplicated()throws Exception{task=queue("capacity");assertEquals(1,control.tickTenant(tenant));assertEquals(0,control.tickTenant(tenant));assertEquals(1,db.queryForObject("select count(*) from nodes where tenant_id=? and id=(select node_id from maintenance_desktop_bindings where device_id=?)",Integer.class,tenant,device));assertEquals(1,db.queryForObject("select count(*) from attempts where tenant_id=?",Integer.class,tenant));}
 @Test void oldClientWithoutCapabilityCannotReceiveReport()throws Exception{interactions.exchange(admin,device,state(device,false));task=queue("old");assertEquals(0,control.tickTenant(tenant));}
 @Test void wrongProjectAndRepositoryPurposeCannotAuthorizeDataset(){String wrong=id(resources.create(admin,"projects",new ExecutionResources.Named("Other project","development")));assertThrows(ApiException.class,()->resources.route(admin,new ExecutionResources.Route(dataset,wrong,pool,profile,true,1)));String dev=id(resources.profile(admin,new ExecutionResources.Profile("Development","development","DEVELOP",Set.of("macos"))));assertThrows(ApiException.class,()->resources.grant(admin,new ExecutionResources.Grant(project,dataset,pool,dev,device,"ALLOW",true,System.currentTimeMillis()+60000,0)));}
 @Test void conflictingRequestDoesNotLeaveOrphanTask(){task=queue("one");assertThrows(ApiException.class,()->queue("two"));assertEquals(1,db.queryForObject("select count(*) from tasks where tenant_id=?",Integer.class,tenant));}
 @Test void ordinaryTaskCreationCannotBypassReportSpecification(){String agent=db.queryForObject("select agent_id from annual_datasets where id=?",String.class,dataset);assertThrows(ApiException.class,()->control.createTask(admin,"bypass",new Requests.Task(agent,"development",List.of("arbitrary"))));}
 @Test void explicitDenyTakesEffectDuringExecution()throws Exception{start();resources.grant(admin,new ExecutionResources.Grant(project,dataset,pool,profile,device,"DENY",true,System.currentTimeMillis()+3600000,1));assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,attempt,receipt(candidate())));}
 @Test void reportOnlyOriginalAppDoesNotNeedGit()throws Exception{
  var reportOnly=new DesktopInteractions.State(device,mapper.readTree("{}"),mapper.readTree("[]"),false,true,mapper.readTree("{\"models\":[],\"presets\":[],\"workspaceOperations\":true,\"annualReport\":\"annual-report.v1\"}"));
  interactions.exchange(admin,device,reportOnly);task=queue("report-only");assertEquals(1,control.tickTenant(tenant));var frames=interactions.exchange(admin,device,reportOnly);assertEquals("annual-report",frames.get(0).get("kind"));
 }
 @Test void confirmedRuntimeFailureIsTerminalWithoutDelivery()throws Exception{start();var r=(Map<?,?>)desktops.annualFailure(admin,device,attempt,new AnnualReports.Failure(epoch,device,hash));assertEquals(true,r.get("accepted"));assertEquals("RUNTIME_FAILED",db.queryForObject("select state from annual_report_jobs where task_id=?",String.class,task));assertEquals("FAILED",db.queryForObject("select state from tasks where id=?",String.class,task));}
 @Test void missingSourceIsRejected()throws Exception{start();assertEquals("REJECTED",((Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("\"c1\",", "")))).get("state"));}

 @Test void anotherOwnerCannotReadSourcesOrRequestCompilation(){String token=AuthService.id();auth.create(token,tenant,"USER","Other operator",Set.of("OPERATOR","VIEWER"),Set.of("development"),token);Identity otherOwner=auth.authenticate(token);assertThrows(ApiException.class,()->reports.detail(otherOwner,dataset));assertThrows(ApiException.class,()->reports.compile(otherOwner,"cross-owner",new AnnualReports.Compile(dataset,"macos")));assertTrue(((List<?>)reports.list(otherOwner)).isEmpty());}
 @Test void expiredMaterialGrantNeverStarts(){db.update("update execution_dataset_grants set expires_at=1 where repository_id=?",dataset);task=queue("expired");assertEquals(0,control.tickTenant(tenant));}
 @Test void zeroPriorRevenueHasExplicitUnavailableGrowth()throws Exception{
  var r=(Map<?,?>)reports.createDataset(admin,new AnnualReports.Dataset("Zero prior",2025,project,List.of(new AnnualReports.Material("r1","Revenue","REVENUE",new BigDecimal("120.10")),new AnnualReports.Material("c1","Cost","COST",new BigDecimal("40.00")),new AnnualReports.Material("p1","Prior","PRIOR_REVENUE",BigDecimal.ZERO))));dataset=(String)r.get("id");snapshot=(String)r.get("snapshotHash");resources.route(admin,new ExecutionResources.Route(dataset,project,pool,profile,true,0));grant(true,0);start();assertEquals("DELIVERED",((Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("100.00","0.00").replace("\"growthPercent\":\"20.10\"","\"growthPercent\":\"N/A\"")))).get("state"));
 }

 @Test void datasetAuditCanBeSearchedWithoutExposingSourceValues(){var page=audit.page(admin,System.currentTimeMillis()-60000,System.currentTimeMillis()+1000,0,50,"ANNUAL_DATASET_CREATED",dataset);var items=(List<?>)page.get("items");assertEquals(1,items.size());var details=(Map<?,?>)((Map<?,?>)items.get(0)).get("details");assertEquals(dataset,details.get("datasetId"));assertFalse(json.write(details).contains("120.10"));assertFalse(json.write(details).contains("materials"));}
 @Test void repositoryAndAnnualPurposesShareOnePhysicalCapacity(){
  String repo=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/shared-capacity","main","development",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");String dev=id(resources.profile(admin,new ExecutionResources.Profile("Developer","development","DEVELOP",Set.of("macos"))));resources.route(admin,new ExecutionResources.Route(repo,project,pool,dev,true,0));resources.grant(admin,new ExecutionResources.Grant(project,repo,pool,dev,device,"ALLOW",true,System.currentTimeMillis()+3600000,0));maintenance.create(admin,"code-task",new MaintenanceService.Problem(repo,"Fix a menu","Use a short menu","Menu uses Tasks","macos","a".repeat(40)));queue("annual-task");assertEquals(1,control.tickTenant(tenant));assertEquals(1,db.queryForObject("select count(*) from attempts where tenant_id=? and state='RESERVED'",Integer.class,tenant));assertEquals(1,db.queryForObject("select count(*) from work_units where tenant_id=? and state='READY'",Integer.class,tenant));
 }

 @Test void formalDeliveryCannotBeDeletedOrRevisedThroughGenericArtifactApi()throws Exception{
  start();var r=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate()));String artifact=(String)r.get("artifactId");
  assertEquals("FORMAL_DELIVERY_PROTECTED",assertThrows(ApiException.class,()->artifacts.delete(admin,artifact)).code);
  assertEquals("FORMAL_DELIVERY_PROTECTED",assertThrows(ApiException.class,()->artifacts.update(admin,artifact,new ArtifactService.Revision(1,Base64.getEncoder().encodeToString("changed".getBytes())))).code);
  assertEquals("DELIVERED",db.queryForObject("select state from annual_report_jobs where task_id=?",String.class,task));
  assertEquals(1,db.queryForObject("select count(*) from task_artifacts where artifact_id=?",Integer.class,artifact));
  assertEquals(artifacts.read(admin,artifact,1),reports.delivery(admin,dataset));
 }
 @Test void rejectedCandidateRecordsIndividualFailuresAndSkipsRendering()throws Exception{
  start();var r=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("80.10","999.00")));
  var evidence=(Map<?,?>)json.read(db.queryForObject("select evidence_json from annual_report_jobs where task_id=?",String.class,task));
  assertEquals("annual-evidence.v2",evidence.get("schema"));var checks=(List<?>)evidence.get("checks");
  assertTrue(checks.stream().anyMatch(v->v instanceof Map<?,?> c&&"PROFIT_RECONCILIATION".equals(c.get("code"))&&"FAILED".equals(c.get("status"))&&"PROFIT_MISMATCH".equals(c.get("reason"))));
  assertTrue(checks.stream().anyMatch(v->v instanceof Map<?,?> c&&"SERVER_RENDERING".equals(c.get("code"))&&"NOT_RUN".equals(c.get("status"))));
  assertTrue(checks.stream().anyMatch(v->v instanceof Map<?,?> c&&"RECOMPUTED_TOTALS".equals(c.get("code"))&&"PASSED".equals(c.get("status"))));
  assertEquals("REJECTED",r.get("state"));
 }
 @Test void malformedCandidateIsSettledWithEvidenceInsteadOfBeingRetriedForever()throws Exception{
  start();assertEquals("REJECTED",((Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt("{"))).get("state"));
  assertEquals("FAILED",db.queryForObject("select state from tasks where id=?",String.class,task));
  assertEquals(true,((Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt("{"))).get("duplicate"));
 }

 @Test void expiredCandidateCannotPublishBeforeOrAfterNewAttemptDelivery()throws Exception{
  start();String old=attempt;long oldEpoch=epoch;var oldReceipt=receipt(candidate());
  db.update("update attempts set expires_at=0 where id=?",old);
  assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,old,oldReceipt)).code);
  assertEquals(0,db.queryForObject("select count(*) from unit_results where attempt_id=?",Integer.class,old));
  db.update("update work_units set next_dispatch_at=0 where task_id=?",task);control.tickTenant(tenant);var frames=interactions.exchange(admin,device,state(device,true));assertEquals(1,frames.size());
  attempt=(String)frames.get(0).get("id");var payload=(Map<?,?>)frames.get(0).get("payload");epoch=((Number)payload.get("epoch")).longValue();hash=(String)payload.get("specHash");assertNotEquals(old,attempt);assertTrue(epoch>oldEpoch);
  var delivered=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,receipt(candidate()));assertEquals("DELIVERED",delivered.get("state"));
  assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,old,oldReceipt));
  assertEquals(1,db.queryForObject("select count(*) from unit_results where unit_id=(select unit_id from annual_report_jobs where task_id=?)",Integer.class,task));
  assertEquals(1,db.queryForObject("select count(*) from task_artifacts where task_id=?",Integer.class,task));
 }
 @Test void committedCandidateReplayOnlyAcknowledgesOriginalResultEvenAfterDeadline()throws Exception{
  start();var body=receipt(candidate());var first=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,body);
  db.update("update attempts set expires_at=0 where id=?",attempt);
  var replay=(Map<?,?>)desktops.annualCandidate(admin,device,attempt,body);assertEquals(true,replay.get("duplicate"));
  assertEquals("RESULT_CONFLICT",assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,attempt,receipt(candidate().replace("80.10","999.00")))).code);
  assertEquals(first.get("artifactId"),((Map<?,?>)reports.delivery(admin,dataset)).get("artifactId"));
  assertEquals(1,db.queryForObject("select count(*) from unit_results where attempt_id=?",Integer.class,attempt));
 }

 @Test void restartedRuntimeCannotRebindAnOldAttemptButCanReceiveANewOne()throws Exception{
  start();String old=attempt;var oldReceipt=receipt(candidate());String runtime=AuthService.id();var original=state(device,true);
  var restarted=new DesktopInteractions.State(runtime,original.sessions(),original.workspaces(),original.attachmentUploads(),original.sessionForks(),original.choices());
  assertTrue(interactions.exchange(admin,device,restarted).isEmpty());
  assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,old,oldReceipt));
  assertThrows(ApiException.class,()->desktops.annualCandidate(admin,device,old,new AnnualReports.Candidate(epoch,runtime,hash,candidate())));
  db.update("update attempts set expires_at=0 where id=?",old);db.update("update work_units set next_dispatch_at=0 where task_id=?",task);
  control.tickTenant(tenant);interactions.exchange(admin,device,restarted);db.update("update work_units set next_dispatch_at=0 where task_id=?",task);control.tickTenant(tenant);
  var frames=interactions.exchange(admin,device,restarted);assertEquals(1,frames.size());var payload=(Map<?,?>)frames.get(0).get("payload");String fresh=(String)frames.get(0).get("id");assertNotEquals(old,fresh);
  assertEquals("DELIVERED",((Map<?,?>)desktops.annualCandidate(admin,device,fresh,new AnnualReports.Candidate(((Number)payload.get("epoch")).longValue(),runtime,(String)payload.get("specHash"),candidate()))).get("state"));
  assertEquals(0,db.queryForObject("select count(*) from unit_results where attempt_id=?",Integer.class,old));
 }

}
