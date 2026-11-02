package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_WORKFLOW_DB_URL:jdbc:h2:mem:maintenance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.execution-enforcement=false"})
@AutoConfigureMockMvc
class MaintenanceIntegrationTest {
    @Autowired ManagementTaskWorkflow workflow;
    @Autowired ManagementTasks managementTasks;
    @Autowired MaintenanceService maintenance;
    @Autowired MaintenanceDesktopChannel desktops;
    @Autowired DesktopHistorySync history;
    @Autowired DesktopInteractions interactions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired ControlService control;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired Json json;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ManagementIdentity management;
    Identity admin,node;
    String tenant,repo,problem,task,attempt;
    long epoch;
    Map<String,Object> spec;
    @BeforeEach void fixture() {
        tenant=AuthService.id();String credential=AuthService.id();
        auth.create(credential,tenant,"USER","Maintainer",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("development"),credential);
        admin=auth.authenticate(credential);
        repo=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/project","main","development",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
        var registered=control.registerNode(admin,new Requests.Node("Mac","development",1,Capabilities.REPOSITORY));
        node=auth.authenticate((String)registered.get("token"));
        maintenance.profile(node,new MaintenanceService.Profile("macos","funnywork-native-v1","git-test"));control.heartbeat(node,true);
    }
    MaintenanceService.Problem request(String platform) {return new MaintenanceService.Problem(repo,"Fix menu","Use a short menu name","Menu shows Tasks",platform,"a".repeat(40));}
    void start() {
        var created=maintenance.create(admin,"request",request("macos"));problem=(String)created.get("problemId");task=(String)created.get("taskId");
        assertEquals(1,control.tickTenant(tenant));var work=control.poll(node);attempt=(String)work.get("attemptId");epoch=((Number)work.get("epoch")).longValue();spec=maintenance.spec(node,attempt,epoch);
    }
    MaintenanceService.Report candidate(String hash) {return new MaintenanceService.Report(epoch,hash,"CANDIDATE_READY","b".repeat(40),"https://github.com/example/project/pull/42","Short menu implemented",List.of(new MaintenanceService.CheckResult("diff",0,"passed")));}
    @Test void issueDispatchCandidateAcceptanceAndReworkUseExistingTasks() throws Exception {
        http.perform(post("/api/v1/maintenance/nodes/profile").header("Authorization","Bearer "+token(node)).contentType("application/json").content(json.write(new MaintenanceService.Profile("macos","funnywork-native-v1","git-test"))))
            .andExpect(status().isNoContent());
        start();assertEquals(true,maintenance.create(admin,"request",request("macos")).get("reused"));
        http.perform(get("/api/v1/attempts/"+attempt+"/maintenance/spec?epoch="+epoch).header("Authorization","Bearer "+token(node)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.spec.repository").value("example/project"));
        http.perform(post("/api/v1/attempts/"+attempt+"/maintenance/result").header("Authorization","Bearer "+token(node)).contentType("application/json").content(json.write(candidate((String)spec.get("specHash")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.businessVerified").value(false));
        assertEquals("OUTPUT_READY",control.getTask(admin,task).get("state"));
        assertEquals("AWAITING_ACCEPTANCE",maintenance.detail(admin,problem).get("state"));
        assertEquals(true,maintenance.report(node,attempt,candidate((String)spec.get("specHash"))).get("duplicate"));
        maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"rework","Check keyboard behavior"));
        assertEquals("IN_PROGRESS",maintenance.detail(admin,problem).get("state"));
        assertNotEquals(task,maintenance.detail(admin,problem).get("task_id"));
        assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Old acceptance")));
    }
    @Test void originalDesktopChannelDispatchesReportsAndRejectsAnotherRuntime() throws Exception {
        // Keep the legacy fixture node offline so only the original desktop binding can claim.
        control.heartbeat(node,false);
        String device=AuthService.id();
        history.registerDevice(admin,new DesktopHistorySync.Device(device,"Original Mac","darwin"));
        desktops.bind(admin,new MaintenanceDesktopChannel.Binding(device,"development"));
        var state=new DesktopInteractions.State("native-runtime",mapper.readTree("{}"),mapper.readTree("[]"),false,true,
            mapper.readTree("{\"models\":[],\"presets\":[],\"maintenance\":{\"executor\":\"funnywork-native-v1\",\"gitVersion\":\"git-test\"}}"));
        assertTrue(interactions.exchange(admin,device,state).isEmpty());
        var created=maintenance.create(admin,"native-request",request("macos"));
        problem=(String)created.get("problemId");task=(String)created.get("taskId");
        assertEquals(1,control.tickTenant(tenant));
        var commands=interactions.exchange(admin,device,state);assertEquals(1,commands.size());
        var command=commands.get(0);assertEquals("maintenance",command.get("kind"));
        attempt=(String)command.get("id");var payload=(Map<?,?>)command.get("payload");
        epoch=((Number)payload.get("epoch")).longValue();String hash=(String)payload.get("specHash");
        assertEquals(409,assertThrows(ApiException.class,()->desktops.renew(admin,device,attempt,new MaintenanceDesktopChannel.Renewal("another-runtime",epoch))).status);
        desktops.renew(admin,device,attempt,new MaintenanceDesktopChannel.Renewal("native-runtime",epoch));
        desktops.report(admin,device,attempt,new MaintenanceDesktopChannel.Receipt("native-runtime",candidate(hash)));
        assertEquals("AWAITING_ACCEPTANCE",maintenance.detail(admin,problem).get("state"));
        assertEquals(409,assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Checked","c".repeat(40)))).status);
        maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Native PR checked","b".repeat(40)));
        assertEquals("ACCEPTED",maintenance.detail(admin,problem).get("state"));
    }
    private String token(Identity identity) {
        // Register a fresh credential for the same test principal through the existing authentication service.
        String value=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(value),identity.id(),System.currentTimeMillis()+3600000);return value;
    }
    @Test void candidateRequiresAllChecksMatchingSpecAndRepository() {
        start();assertThrows(ApiException.class,()->maintenance.report(node,attempt,candidate("0".repeat(64))));
        String agent=db.queryForObject("select agent_id from maintenance_repositories where id=?",String.class,repo);
        assertThrows(ApiException.class,()->control.createTask(admin,"unstructured",new Requests.Task(agent,"development",List.of("Fix it"))));
        var missing=new MaintenanceService.Report(epoch,(String)spec.get("specHash"),"CANDIDATE_READY","b".repeat(40),"https://github.com/example/project/pull/42","Missing check",List.of());
        assertThrows(ApiException.class,()->maintenance.report(node,attempt,missing));
        var foreign=new MaintenanceService.Report(epoch,(String)spec.get("specHash"),"CANDIDATE_READY","b".repeat(40),"https://github.com/other/project/pull/42","Wrong repository",List.of(new MaintenanceService.CheckResult("diff",0,"passed")));
        assertThrows(ApiException.class,()->maintenance.report(node,attempt,foreign));
        assertThrows(ApiException.class,()->control.result(node,attempt,new Requests.Result(epoch,"b".repeat(64),1,1)));
        maintenance.report(node,attempt,candidate((String)spec.get("specHash")));
        assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Wrong candidate","c".repeat(40))));
        maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Diff and runtime behavior reviewed","b".repeat(40)));
        assertEquals("ACCEPTED",maintenance.detail(admin,problem).get("state"));
        assertEquals("SUCCEEDED",control.getTask(admin,task).get("state"));
        assertThrows(ApiException.class,()->maintenance.report(node,attempt,new MaintenanceService.Report(epoch,(String)spec.get("specHash"),"RUNTIME_FAILED",null,null,"Different receipt",List.of())));
    }
    @Test void mismatchedPlatformDoesNotConsumeAttemptAndSpecIsTenantScoped() {
        var created=maintenance.create(admin,"request",request("windows"));
        assertEquals(0,control.tickTenant(tenant));assertEquals("NO_WORK",control.poll(node).get("status"));
        assertEquals(0,db.queryForObject("select count(*) from attempts a join work_units u on a.unit_id=u.id where u.task_id=?",Integer.class,created.get("taskId")));
        assertThrows(ApiException.class,()->maintenance.spec(node,"missing",1));
    }
    @Test void failedReportIsIdempotentAndNeverTriggersAutomaticReimplementation() {
        start();var report=new MaintenanceService.Report(epoch,(String)spec.get("specHash"),"CHECKS_FAILED",null,null,"Build failed",List.of(new MaintenanceService.CheckResult("diff",1,"Failure")));
        maintenance.report(node,attempt,report);assertEquals(true,maintenance.report(node,attempt,report).get("duplicate"));
        assertEquals("BLOCKED",maintenance.detail(admin,problem).get("state"));assertEquals(0,control.tickTenant(tenant));
        assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","Cannot accept a failure")));
    }
    @Test void managementPermissionsExposeOnlyOperatorAndEnrollmentRoutes() throws Exception {
        assertEquals("funnyworker:tasks:read",ManagementIdentity.permission("GET","/api/v1/maintenance/problems"));
        assertEquals("funnyworker:tasks:read",ManagementIdentity.permission("GET","/api/v1/maintenance/repositories"));
        assertEquals("funnyworker:agents:write",ManagementIdentity.permission("POST","/api/v1/maintenance/repositories"));
        assertEquals("funnyworker:nodes:write",ManagementIdentity.permission("POST","/api/v1/maintenance/node-enrollments"));
        assertEquals("funnyworker:tasks:write",ManagementIdentity.permission("POST","/api/v1/maintenance/problems/example/decision"));
        assertThrows(ApiException.class,()->ManagementIdentity.module("/api/v1/maintenance/nodes/profile"));
        http.perform(post("/api/v1/maintenance/node-enrollments").header("Authorization","Bearer "+token(admin)).contentType("application/json").content(json.write(new NodeEnrollment.Issue(new Requests.Node("Mac","development",1,Capabilities.REPOSITORY),900))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
    }
    @Test void cancelledAndExpiredMaintenanceNeverAcceptOldEvidenceOrAutomaticallyReimplement() {
        start();control.cancel(admin,task);
        assertThrows(ApiException.class,()->maintenance.spec(node,attempt,epoch));
        assertThrows(ApiException.class,()->maintenance.report(node,attempt,candidate((String)spec.get("specHash"))));
        assertEquals("BLOCKED",maintenance.detail(admin,problem).get("state"));
        maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"rework","Old process stopped; rerun requested"));
        assertEquals(1,control.tickTenant(tenant));var next=control.poll(node);String second=(String)next.get("attemptId");
        db.update("update attempts set expires_at=0 where id=?",second);
        assertEquals(0,control.tickTenant(tenant));assertEquals("BLOCKED",maintenance.detail(admin,problem).get("state"));
        assertEquals("FAILED",db.queryForObject("select state from work_units where id=?",String.class,next.get("unitId")));
    }
    @Test void foreignTenantsAndUnassignedNodesCannotReadOrSubmitAnAssignment() {
        start();String key=AuthService.id();auth.create(AuthService.id(),AuthService.id(),"USER","Other",Set.of("ADMIN","OPERATOR"),Set.of("development"),key);
        Identity foreign=auth.authenticate(key);
        assertThrows(ApiException.class,()->maintenance.detail(foreign,problem));
        assertThrows(ApiException.class,()->maintenance.create(foreign,"foreign",request("macos")));
        var extra=control.registerNode(admin,new Requests.Node("Other Mac","development",1,Capabilities.REPOSITORY));Identity other=auth.authenticate((String)extra.get("token"));
        maintenance.profile(other,new MaintenanceService.Profile("macos","codex-test","git-test"));
        assertThrows(ApiException.class,()->maintenance.spec(other,attempt,epoch));
        assertThrows(ApiException.class,()->maintenance.report(other,attempt,candidate((String)spec.get("specHash"))));
        assertThrows(ApiException.class,()->maintenance.spec(node,attempt,epoch+1));
    }
    @Test void wireValidationRejectsIncompleteProblemAndUnsafeFailureLinks() throws Exception {
        http.perform(post("/api/v1/maintenance/problems").header("Authorization","Bearer "+token(admin)).header("Idempotency-Key","invalid").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        start();var report=new MaintenanceService.Report(epoch,(String)spec.get("specHash"),"RUNTIME_FAILED",null,"javascript:alert(1)","Invalid link",List.of());
        assertThrows(ApiException.class,()->maintenance.report(node,attempt,report));
    }

    @Test void managementSessionUsesTheActualTaskPermissionBridge() throws Exception {
        db.update("insert into management_identity_links values(?,?,?)",tenant,"7",admin.id());
        org.mockito.Mockito.doReturn(new ManagementIdentity.Authority(tenant,"7",Set.of("funnyworker:tasks:read","funnyworker:tasks:write"))).when(management).authority("maintenance-session");
        http.perform(get("/api/v1/maintenance/repositories").header("Authorization","Bearer maintenance-session").header("X-FunnyWorker-Management","1")).andExpect(status().isOk());
        http.perform(get("/api/v1/maintenance/automation").header("Authorization","Bearer maintenance-session").header("X-FunnyWorker-Management","1")).andExpect(status().isOk()).andExpect(jsonPath("$.policies").isArray());
        assertEquals("funnyworker:tasks:read",ManagementIdentity.permission("GET","/api/v1/maintenance/automation/stages/example"));
        assertEquals("funnyworker:tasks:read",ManagementIdentity.permission("GET","/api/v1/maintenance/automation/problems/example/evidence"));
        assertEquals("funnyworker:agents:write",ManagementIdentity.permission("POST","/api/v1/maintenance/automation/policies"));
        assertEquals("funnyworker:agents:write",ManagementIdentity.permission("PUT","/api/v1/maintenance/repositories/example/checks"));
        http.perform(post("/api/v1/maintenance/automation/policies").header("Authorization","Bearer maintenance-session").header("X-FunnyWorker-Management","1").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        assertThrows(ApiException.class,()->ManagementIdentity.module("/api/v1/maintenance/automation/nodes/profile"));
        http.perform(post("/api/v1/maintenance/problems").header("Authorization","Bearer maintenance-session").header("X-FunnyWorker-Management","1").header("Idempotency-Key","management").contentType("application/json").content(json.write(request("macos"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.problemId").isString());
        http.perform(post("/api/v1/maintenance/node-enrollments").header("Authorization","Bearer maintenance-session").header("X-FunnyWorker-Management","1").contentType("application/json").content(json.write(new NodeEnrollment.Issue(new Requests.Node("Mac","development",1,Capabilities.REPOSITORY),900))))
            .andExpect(status().isForbidden());
    }

    @Autowired MaintenanceDelivery delivery;
    MaintenanceDelivery.Policy deliveryPolicy(List<String> platforms){var checks=new LinkedHashMap<String,List<MaintenanceService.Check>>();for(String platform:platforms)checks.put(platform,List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)));return new MaintenanceDelivery.Policy(repo,platforms,checks,List.of("ci"),true);}
    @Test void pinnedPlatformVerificationGatesAcceptanceAndDelivery() {
        start();maintenance.report(node,attempt,candidate((String)spec.get("specHash")));
        maintenance.profile(node,new MaintenanceService.Profile("macos","funnywork-native-v2","git-test"));delivery.configure(admin,deliveryPolicy(List.of("macos")));
        var action=new MaintenanceDelivery.Action(1,"b".repeat(40),"Verify candidate");delivery.verify(admin,problem,action);delivery.verify(admin,problem,action);
        assertEquals(1,delivery.jobs(admin,problem).size());
        assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","reviewed","b".repeat(40))));
        assertThrows(ApiException.class,()->delivery.deliver(admin,problem,action));
        control.tickTenant(tenant);var work=control.poll(node);String verification=(String)work.get("attemptId");long e=((Number)work.get("epoch")).longValue();var assigned=maintenance.spec(node,verification,e);
        assertEquals("VERIFY",((Map<?,?>)assigned.get("spec")).get("mode"));
        assertThrows(ApiException.class,()->maintenance.report(node,verification,new MaintenanceService.Report(e,(String)assigned.get("specHash"),"VERIFIED","c".repeat(40),"https://github.com/example/project/pull/42","wrong",List.of(new MaintenanceService.CheckResult("diff",0,"ok")))));
        var passed=new MaintenanceService.Report(e,(String)assigned.get("specHash"),"VERIFIED","b".repeat(40),"https://github.com/example/project/pull/42","passed",List.of(new MaintenanceService.CheckResult("diff",0,"ok")));
        maintenance.report(node,verification,passed);assertEquals(true,maintenance.report(node,verification,passed).get("duplicate"));
        var changed=deliveryPolicy(List.of("macos"));delivery.configure(admin,new MaintenanceDelivery.Policy(repo,changed.platforms(),changed.checks(),List.of("changed-ci"),true));
        assertThrows(ApiException.class,()->maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","old policy evidence","b".repeat(40))));
        delivery.configure(admin,changed);
        maintenance.decide(admin,problem,new MaintenanceService.Decision(1,"accept","reviewed","b".repeat(40)));
        db.update("insert into maintenance_stage_runs(id,tenant_id,repository_id,subject_id,stage,input_hash,input_json,policy_revision,state,created_at,updated_at) values(?,?,?,?,'DELIVERY',?,?,1,'PASSED',100,100)",AuthService.id(),tenant,repo,problem+":"+"b".repeat(40),AuthService.id(),"{\"problemVersion\":1,\"private\":\"must-not-leak\"}");
        var oldFlow=(Map<?,?>)workflow.inspect(admin,task);assertEquals("NOT_CONFIRMED",oldFlow.get("delivery"));assertFalse(json.write(oldFlow).contains("must-not-leak"));
        assertEquals(1,((List<?>)maintenance.detail(admin,problem).get("reports")).size());
        delivery.deliver(admin,problem,new MaintenanceDelivery.Action(2,"b".repeat(40),"Authorize merge and source closure"));
        control.tickTenant(tenant);var job=control.poll(node);String deliveryAttempt=(String)job.get("attemptId");long deliveryEpoch=((Number)job.get("epoch")).longValue();var deliverySpec=maintenance.spec(node,deliveryAttempt,deliveryEpoch);assertEquals("DELIVER",((Map<?,?>)deliverySpec.get("spec")).get("mode"));
        assertThrows(ApiException.class,()->maintenance.report(node,deliveryAttempt,new MaintenanceService.Report(deliveryEpoch,(String)deliverySpec.get("specHash"),"DELIVERED","b".repeat(40),"https://github.com/example/project/pull/42","{}",List.of(new MaintenanceService.CheckResult("delivery",0,"ok")))));
        String proof=json.write(Map.of("candidateSha","b".repeat(40),"prUrl","https://github.com/example/project/pull/42","mergeSha","c".repeat(40),"issues",List.of()));
        maintenance.report(node,deliveryAttempt,new MaintenanceService.Report(deliveryEpoch,(String)deliverySpec.get("specHash"),"DELIVERED","b".repeat(40),"https://github.com/example/project/pull/42",proof,List.of(new MaintenanceService.CheckResult("delivery",0,proof))));
        assertEquals("DELIVERED",delivery.jobs(admin,problem).get(1).get("state"));
        var flow=(Map<?,?>)workflow.inspect(admin,task);assertEquals("ACCEPTED",flow.get("validation"));assertEquals("DELIVERY_FLOW_CONFIRMED",flow.get("delivery"));assertFalse(json.write(flow).contains("requirement_json"));assertFalse(json.write(flow).contains("report_json"));assertFalse(json.write(flow).contains("spec_json"));
    }
    @Test void everyConfiguredPlatformMustVerifyAndPolicyChangesInvalidateOldEvidence() {
        start();maintenance.report(node,attempt,candidate((String)spec.get("specHash")));maintenance.profile(node,new MaintenanceService.Profile("macos","funnywork-native-v2","git-test"));delivery.configure(admin,deliveryPolicy(List.of("macos","windows","linux")));
        delivery.verify(admin,problem,new MaintenanceDelivery.Action(1,"b".repeat(40),"Verify all"));assertEquals(3,delivery.jobs(admin,problem).size());
        control.tickTenant(tenant);assertEquals(1,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id join maintenance_delivery_jobs j on j.task_id=u.task_id where j.problem_id=? and a.state='RESERVED' and j.platform='macos'",Integer.class,problem));
        assertThrows(ApiException.class,()->delivery.configure(admin,deliveryPolicy(List.of("macos"))));
        assertThrows(ApiException.class,()->delivery.verify(admin,problem,new MaintenanceDelivery.Action(1,"c".repeat(40),"wrong")));
        assertEquals("funnyworker:tasks:write",ManagementIdentity.permission("POST","/api/v1/maintenance/problems/abc/deliver"));
    }

    @Test void historicalTaskCannotRevealTheCurrentWorkflowAfterItsScopeChanges(){
        start();
        String finance=(String)((Map<?,?>)managementTasks.department(admin,new ManagementTasks.Department("Finance","development"))).get("id"),engineering=(String)((Map<?,?>)managementTasks.department(admin,new ManagementTasks.Department("Engineering","development"))).get("id");
        managementTasks.assign(admin,task,new ManagementTasks.Context(finance,"",0));String agent=db.queryForObject("select agent_id from tasks where id=?",String.class,task);
        String current=(String)control.createMaintenanceTask(admin,AuthService.id(),new Requests.Task(agent,"development",List.of("new requirement"))).get("taskId");managementTasks.assign(admin,current,new ManagementTasks.Context(engineering,"",0));db.update("update maintenance_problems set task_id=? where id=?",current,problem);
        String credential=AuthService.id();auth.create(credential,tenant,"USER","Scoped admin",Set.of("ADMIN"),Set.of("development"),credential);Identity limited=auth.authenticate(credential);managementTasks.saveLimits(admin,limited.id(),new ManagementTasks.Limits(true,Set.of(finance),false,Set.of(),0));
        var view=(Map<?,?>)workflow.inspect(limited,task);assertEquals(false,view.get("currentWorkflowVisible"));assertFalse(view.containsKey("state"));assertFalse(view.containsKey("stages"));assertFalse(view.containsKey("workbench"));assertFalse(json.write(view).contains(current));
    }

}
