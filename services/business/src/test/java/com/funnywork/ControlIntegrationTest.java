package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(ControlIntegrationTest.TimeConfig.class)
class ControlIntegrationTest {
    @Autowired NodeCertificates certificates;
    @Autowired NodeEnrollment enrollments;
    @Autowired BrowserExecution browser;
    @Autowired ComputerGrants computerGrants;
    @Autowired ComputerControl computerControl;
    @Autowired ComputerActions computerActions;
    @Autowired ComputerReports computerReports;
    @Autowired ComputerControlInventory computerInventory;
    @Autowired ControlService control;
    @Autowired RecoveryCases recoveryCases;
    @Autowired AuthService auth;
    @Autowired ArtifactService artifacts;
    @Autowired ExternalOperations operations;
    @Autowired TenantLocks locks;
    @Autowired ToolRegistry registry;
    @Autowired ModelExecution models;
    @Autowired DocumentBindings documents;
    @Autowired ManagementUsage usage;
    @Autowired ManagementAudit audit;
    @Autowired PublishedSkills publishedSkills;
    @Autowired ConnectorExecution connectors;
    @SpyBean JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc http;
    @Autowired Json json;
    @Autowired MutableClock time;
    Identity admin,owner,other;
    String agentId;
    static class MutableClock extends Clock {
        final AtomicLong value=new AtomicLong(1_800_000_000_000L);
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(value.get());}
        @Override public long millis(){return value.get();}
        void advance(long ms){value.addAndGet(ms);}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock mutableClock(){return new MutableClock();} }
    @BeforeEach void setup() {
        for(String table:List.of("management_task_batch_items","management_task_batches","management_task_content_access","management_task_commands","task_creation_attribution","task_management_context","management_task_limits","task_member_departments","task_departments","recovery_evidence_verification","recovery_evidence","recovery_cases","computer_reconciliations","computer_reviewers","computer_verification_bindings","computer_actions","computer_controls","computer_sessions","computer_grants","browser_reconciliations","browser_reviewers","browser_verification_bindings","browser_actions","browser_grants","node_certificates","node_enrollments","enterprise_identities","reconciliation_runs","unit_reconciliations","operation_reconcile_grants","unit_operations","task_artifacts","external_operations","task_tool_grants","tool_versions","artifact_requests","artifact_grants","artifact_versions","artifacts","node_inbox","node_dispatch","events","unit_results","attempts","work_units","tasks","agent_versions","nodes","api_tokens","principals","tenant_guards")) db.update("delete from "+table);
        time.value.set(1_800_000_000_000L);
        auth.create("admin","tenant-a","USER","Admin",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("sandbox","other"),"admin-secret");
        auth.create("owner","tenant-a","USER","Owner",Set.of("OPERATOR","VIEWER"),Set.of("sandbox"),"owner-secret");
        auth.create("other","tenant-a","USER","Other",Set.of("OPERATOR","VIEWER"),Set.of("sandbox"),"other-secret");
        admin=auth.authenticate("admin-secret");owner=auth.authenticate("owner-secret");other=auth.authenticate("other-secret");
        agentId=(String)control.publishAgent(admin,new Requests.Agent("text-worker","1.0.0")).get("agentId");
    }
    @Test void computerActionsClaimOnceBlockUnknownAndEnforceBudget() throws Exception {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        var first=new ComputerActions.Action(1,1,"first","click","a".repeat(64));
        assertEquals("NOT_FOUND",computerActions.operate(node,ids[4],first,"lookup").get("status"));
        var receipt=computerActions.operate(node,ids[4],first,"claim");
        assertEquals(true,receipt.get("shouldExecute"));assertEquals(false,receipt.get("businessVerified"));
        assertEquals(false,computerActions.operate(node,ids[4],first,"claim").get("shouldExecute"));
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"first","click","b".repeat(64)),"claim"));
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"next","click","a".repeat(64)),"claim"));
        computerActions.operate(node,ids[4],first,"dispatched");
        for(int i=1;i<8;i++) {
            var action=new ComputerActions.Action(1,1,"action-"+i,"fill","a".repeat(64));
            computerActions.operate(node,ids[4],action,"claim");computerActions.operate(node,ids[4],action,"dispatched");
        }
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"ninth","click","a".repeat(64)),"claim"));
        http.perform(post("/api/v1/attempts/"+ids[4]+"/computer-actions/lookup").header("Authorization","Bearer "+ids[3])
            .contentType("application/json").content(json.write(first))).andExpect(status().isOk()).andExpect(jsonPath("$.shouldExecute").value(false));
    }
    @Test void computerActionsRejectLateReceiptAndCancellationRemainsUnverified() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        var action=new ComputerActions.Action(1,1,"pending","click","a".repeat(64));computerActions.operate(node,ids[4],action,"claim");
        time.advance(15000);control.heartbeat(node,true);
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],action,"dispatched"));
        assertEquals("UNKNOWN",db.queryForObject("select state from computer_actions",String.class));
        control.cancel(owner,ids[0]);assertEquals("RECONCILING",control.getTask(owner,ids[0]).get("state"));
        computerControl.release(node,ids[4],1,1,true);
    }
    @Test void computerActionsRequirePermittedActionCurrentGrantAndControlGeneration() {
        var ids=runningComputerFixture(Set.of("click"));var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"fill-denied","fill","a".repeat(64)),"claim"));
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,2,"x","click","a".repeat(64)),"claim"));
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"x","shell","a".repeat(64)),"claim"));
        computerGrants.revoke(admin,ids[0],ids[1]);
        assertThrows(ApiException.class,()->computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"x","click","a".repeat(64)),"claim"));
        assertEquals(0,db.queryForObject("select count(*) from computer_actions",Integer.class));
    }
    String secondComputerAttempt(String[] ids) {
        String agent=db.queryForObject("select agent_id from tasks where id=?",String.class,ids[0]);
        String task=(String)control.createTask(owner,AuthService.id(),new Requests.Task(agent,"sandbox",List.of("second desktop work"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        computerGrants.grant(admin,task,unit,computerGrant(ids[2]));
        db.update("update work_units set state='RUNNING',epoch=1,attempts=1 where id=?",unit);
        String attempt=AuthService.id();
        db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,1,'RUNNING',?,?,?)",attempt,unit,ids[2],time.millis()+90000,time.millis(),owner.tenant());
        return attempt;
    }
    @Test void computerControlExpiresWithoutAutomaticHandoffAndFencesOldGeneration() {
        var ids=runningComputerFixture();String second=secondComputerAttempt(ids);var node=auth.authenticate(ids[3]);
        var lease=computerControl.acquire(node,ids[4],1);assertEquals(1L,lease.get("generation"));
        time.advance(15000);control.heartbeat(node,true);
        assertThrows(ApiException.class,()->computerControl.check(node,ids[4],1,1,true));
        assertThrows(ApiException.class,()->computerControl.acquire(node,second,1));
        assertThrows(ApiException.class,()->computerControl.release(node,ids[4],1,1,false));
        computerControl.release(node,ids[4],1,1,true);
        assertEquals(2L,computerControl.acquire(node,second,1).get("generation"));
        computerControl.release(node,ids[4],1,1,true); // stale duplicate cannot free the new owner
        computerControl.check(node,second,1,2,false);
        assertThrows(ApiException.class,()->computerControl.acquire(node,ids[4],1));
        assertThrows(ApiException.class,()->computerControl.check(node,second,1,1,false));
    }
    @Test void computerControlRenewalRequiresLiveAuthorityAndAcquireDoesNotExtend() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        long original=((Number)computerControl.acquire(node,ids[4],1).get("expiresAt")).longValue();
        time.advance(5000);assertEquals(original,((Number)computerControl.acquire(node,ids[4],1).get("expiresAt")).longValue());
        assertEquals(time.millis()+15000,computerControl.check(node,ids[4],1,1,true).get("expiresAt"));
        computerGrants.revoke(admin,ids[0],ids[1]);
        assertThrows(ApiException.class,()->computerControl.check(node,ids[4],1,1,false));
        assertThrows(ApiException.class,()->computerControl.check(node,ids[4],1,1,true));
        computerControl.release(node,ids[4],1,1,true);
    }
    @Test void computerControlRemainingTimeShrinksAndIsCappedByAttemptExpiry() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        var initial=computerControl.acquire(node,ids[4],1);
        assertEquals(15000L,initial.get("remainingMs"));
        time.advance(4000);
        assertEquals(11000L,computerControl.acquire(node,ids[4],1).get("remainingMs"));
        assertEquals(11000L,computerControl.check(node,ids[4],1,1,false).get("remainingMs"));
        db.update("update attempts set expires_at=? where id=?",time.millis()+2000,ids[4]);
        assertEquals(2000L,computerControl.acquire(node,ids[4],1).get("remainingMs"));
        assertEquals(2000L,computerControl.check(node,ids[4],1,1,true).get("remainingMs"));
        time.advance(2000);
        assertThrows(ApiException.class,()->computerControl.check(node,ids[4],1,1,false));
    }
    @Test void computerControlConcurrentAcquisitionHasOneWinner() throws Exception {
        var ids=runningComputerFixture();String second=secondComputerAttempt(ids);var node=auth.authenticate(ids[3]);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String attempt:List.of(ids[4],second))results.add(pool.submit(()->{start.await();try{computerControl.acquire(node,attempt,1);return true;}catch(ApiException busy){return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,db.queryForObject("select count(*) from computer_controls",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void computerControlHttpReleaseAllowsCleanupAfterOwnerRevocation() throws Exception {
        var ids=runningComputerFixture();String route="/api/v1/attempts/"+ids[4]+"/computer-control";
        http.perform(post(route).header("Authorization","Bearer "+ids[3]).contentType("application/json").content("{\"epoch\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1));
        http.perform(post(route+"/release").header("Authorization","Bearer "+ids[3]).contentType("application/json").content("{\"epoch\":1,\"generation\":1,\"driverStopped\":false}"))
            .andExpect(status().isBadRequest());
        auth.disable(admin,owner.id());
        http.perform(get(route+"?epoch=1&generation=1").header("Authorization","Bearer "+ids[3])).andExpect(status().isForbidden());
        http.perform(post(route+"/release").header("Authorization","Bearer "+ids[3]).contentType("application/json").content("{\"epoch\":1,\"generation\":1,\"driverStopped\":true}"))
            .andExpect(status().isOk());
        assertEquals("RELEASED",db.queryForObject("select state from computer_controls where attempt_id=?",String.class,ids[4]));
    }
    @Test void computerReportRequiresStoppedDriverAndIsIdempotentGeneratedEvidence() throws Exception {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        computerControl.acquire(node,ids[4],1);
        var request=new ComputerReports.Report(1,"Model claims success but is not a verifier");
        assertEquals("DRIVER_STOP_REQUIRED",assertThrows(ApiException.class,()->computerReports.report(node,ids[4],request)).code);
        computerControl.release(node,ids[4],1,1,true);
        var saved=computerReports.report(node,ids[4],request);assertEquals(false,saved.get("businessVerified"));
        assertEquals("GENERATED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
        assertEquals("OUTPUT_READY",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
        assertEquals(true,computerReports.report(node,ids[4],request).get("duplicate"));
        assertThrows(ApiException.class,()->computerReports.report(node,ids[4],new ComputerReports.Report(1,"changed")));
        assertEquals(1,db.queryForObject("select count(*) from task_artifacts where task_id=?",Integer.class,ids[0]));
        http.perform(post("/api/v1/attempts/"+ids[4]+"/computer-report").header("Authorization","Bearer "+ids[3]).contentType("application/json")
            .content(json.write(Map.of("epoch",1,"text",request.text())))).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
    }
    @Test void computerUnknownSurvivesReportAndGrantRevocationDoesNotHideEvidence() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"unknown","fill","a".repeat(64)),"claim");
        computerGrants.revoke(admin,ids[0],ids[1]);computerControl.release(node,ids[4],1,1,true);
        var saved=computerReports.report(node,ids[4],new ComputerReports.Report(1,"Interrupted"));
        var artifact=artifacts.read(owner,(String)saved.get("artifactId"),1);
        var evidence=(Map<?,?>)json.read(new String(Base64.getDecoder().decode((String)artifact.get("content_base64")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false,evidence.get("businessVerified"));
        var actions=(List<?>)evidence.get("actions");assertEquals(1,actions.size());
        assertEquals("UNKNOWN",((Map<?,?>)actions.get(0)).get("state"));
        assertEquals("Interrupted",evidence.get("nodeReport"));
        assertEquals("RECONCILING",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
        String result=db.queryForObject("select result_json from unit_results where unit_id=?",String.class,ids[1]);
        assertEquals("RECONCILIATION_REQUIRED",((Map<?,?>)json.read(result)).get("reviewState"));
        assertEquals("UNKNOWN",db.queryForObject("select state from computer_actions where unit_id=?",String.class,ids[1]));
        control.cancel(owner,ids[0]);assertEquals("RECONCILING",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
    }
    @Test void computerReportRejectsOtherNodesInvalidLeaseAndRevokedOwner() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        computerControl.release(node,ids[4],1,1,true);
        var request=new ComputerReports.Report(1,"report");
        assertThrows(ApiException.class,()->computerReports.report(node("sandbox",1),ids[4],request));
        assertThrows(ApiException.class,()->computerReports.report(node,ids[4],new ComputerReports.Report(2,"report")));
        assertThrows(ApiException.class,()->computerReports.report(node,ids[4],new ComputerReports.Report(1,null)));
        auth.disable(admin,owner.id());assertThrows(ApiException.class,()->computerReports.report(node,ids[4],request));
        assertEquals(0,db.queryForObject("select count(*) from unit_results where unit_id=?",Integer.class,ids[1]));
    }
    String[] runningComputerFixture() {return runningComputerFixture(Set.of("click","fill"));}
    String[] runningComputerFixture(Set<String> actions) {
        var ids=computerUnit();computerGrants.grant(admin,ids[0],ids[1],new ComputerGrants.Grant(ids[2],"1","123:456","789",actions,8,60));
        control.heartbeat(auth.authenticate(ids[3]),true);
        // Execution scope is tested without enabling production dispatch before GUI leases exist.
        String attempt=AuthService.id();
        db.update("update work_units set state='RUNNING',epoch=1,attempts=1 where id=?",ids[1]);
        db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,1,'RUNNING',?,?,?)",attempt,ids[1],ids[2],time.millis()+90000,time.millis(),owner.tenant());
        return new String[]{ids[0],ids[1],ids[2],ids[3],attempt};
    }
    @Test void computerExecutionScopeRequiresCurrentLeaseAndReturnsBoundedLifetime() throws Exception {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        var scope=computerGrants.scope(node,ids[4],1);assertEquals(ids[1],scope.get("unitId"));
        assertEquals(time.millis()+60000,((Number)scope.get("expiresAt")).longValue());
        http.perform(get("/api/v1/attempts/"+ids[4]+"/computer-scope?epoch=1").header("Authorization","Bearer "+ids[3]))
            .andExpect(status().isOk()).andExpect(jsonPath("$.policy.nodeId").value(ids[2]));
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],2));
        assertThrows(ApiException.class,()->computerGrants.scope(owner,ids[4],1));
        assertThrows(ApiException.class,()->computerGrants.scope(node("sandbox",1),ids[4],1));
        db.update("update attempts set expires_at=? where id=?",time.millis(),ids[4]);
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
    }
    @Test void computerExecutionScopeRejectsGrantExpiryRevocationAndIssuerChange() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        db.update("update principals set auth_version=auth_version+1 where id=?",admin.id());
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
        db.update("update principals set auth_version=auth_version-1 where id=?",admin.id());
        computerGrants.scope(node,ids[4],1);
        time.advance(60000);control.heartbeat(node,true);
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
        time.advance(-60000);computerGrants.revoke(admin,ids[0],ids[1]);
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
    }
    @Test void computerExecutionScopeRechecksOwnerNodeAndIssuerRole() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);
        db.update("update principals set roles='[]' where id=?",admin.id());
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
        db.update("update principals set roles=? where id=?",json.write(admin.roles()),admin.id());
        db.update("update nodes set capability='model.read.v1' where id=?",node.id());
        assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
        db.update("update nodes set capability=? where id=?",Capabilities.COMPUTER,node.id());
        control.heartbeat(node,false);assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
        control.heartbeat(node,true);computerGrants.scope(node,ids[4],1);
        auth.disable(admin,owner.id());assertThrows(ApiException.class,()->computerGrants.scope(node,ids[4],1));
    }
    String[] computerUnit() {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("computer-grant-worker","1.0.0",Capabilities.COMPUTER)).get("agentId");
        String task=(String)control.createTask(owner,"computer-grant-task",new Requests.Task(agent,"sandbox",List.of("desktop task"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        var node=control.registerNode(admin,new Requests.Node("Desktop","sandbox",1,Capabilities.COMPUTER));
        return new String[]{task,unit,(String)node.get("nodeId"),(String)node.get("token")};
    }
    ComputerGrants.Grant computerGrant(String node) {return new ComputerGrants.Grant(node,"1","123:456","789",Set.of("click","fill"),8,60);}
    @Test void computerGrantHttpIsImmutableExpiringAndDoesNotYetEnableScheduling() throws Exception {
        var ids=computerUnit();var grant=computerGrant(ids[2]);String route="/api/v1/tasks/"+ids[0]+"/units/"+ids[1]+"/computer-grant";
        http.perform(put(route).header("Authorization","Bearer admin-secret").contentType("application/json").content(json.write(grant))).andExpect(status().isOk());
        long expires=((Number)computerGrants.get(admin,ids[0],ids[1]).get("expiresAt")).longValue();
        time.advance(10000);computerGrants.grant(admin,ids[0],ids[1],grant);
        assertEquals(expires,((Number)computerGrants.get(admin,ids[0],ids[1]).get("expiresAt")).longValue());
        http.perform(get(route).header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.policy.session").value("1"));
        var node=auth.authenticate(ids[3]);control.heartbeat(node,true);control.tick();
        assertEquals("NO_WORK",control.poll(node).get("status"));
        assertEquals(0,db.queryForObject("select count(*) from attempts",Integer.class));
        time.advance(50000);
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],grant));
        http.perform(delete(route).header("Authorization","Bearer admin-secret")).andExpect(status().isOk());
        assertEquals(false,computerGrants.get(admin,ids[0],ids[1]).get("enabled"));
    }
    @Test void computerGrantRequiresCurrentAdministratorAndMatchingTenantZoneNode() throws Exception {
        var ids=computerUnit();var grant=computerGrant(ids[2]);
        assertThrows(ApiException.class,()->computerGrants.grant(owner,ids[0],ids[1],grant));
        assertThrows(ApiException.class,()->computerGrants.get(owner,ids[0],ids[1]));
        var wrong=control.registerNode(admin,new Requests.Node("Other zone","other",1,Capabilities.COMPUTER));
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],computerGrant((String)wrong.get("nodeId"))));
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],computerGrant(node("sandbox",1).id())));
        auth.create("foreign-admin","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"foreign-secret");
        var foreign=auth.authenticate("foreign-secret");
        assertThrows(ApiException.class,()->computerGrants.grant(foreign,ids[0],ids[1],grant));
        db.update("update principals set roles='[]',auth_version=auth_version+1 where id=?",admin.id());
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],grant));
        assertEquals(0,db.queryForObject("select count(*) from computer_grants",Integer.class));
    }
    @Test void computerGrantCannotBeRetargetedOrReenabledAfterRevocation() {
        var ids=computerUnit();var grant=computerGrant(ids[2]);computerGrants.grant(admin,ids[0],ids[1],grant);
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],new ComputerGrants.Grant(ids[2],"1","123:456","790",Set.of("click"),8,60)));
        computerGrants.revoke(admin,ids[0],ids[1]);computerGrants.revoke(admin,ids[0],ids[1]);
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],grant));
        assertEquals(1,db.queryForObject("select count(*) from computer_grants",Integer.class));
        assertEquals(false,computerGrants.get(admin,ids[0],ids[1]).get("enabled"));
    }
    @Test void computerGrantRejectsInvalidTargetsAndAlreadyStartedUnits() throws Exception {
        var ids=computerUnit();String route="/api/v1/tasks/"+ids[0]+"/units/"+ids[1]+"/computer-grant";
        for(var grant:List.of(new ComputerGrants.Grant(ids[2],"0","123:456","789",Set.of("click"),8,60),
                new ComputerGrants.Grant(ids[2],"1","alias-only","789",Set.of("click"),8,60),
                new ComputerGrants.Grant(ids[2],"1","123:456","789",new HashSet<>(Arrays.asList("click",null)),8,60),
                new ComputerGrants.Grant(ids[2],"1","123:456","789",Set.of("shell"),8,60),
                new ComputerGrants.Grant(ids[2],"1","123:456","789",Set.of("click"),129,3601))) {
            http.perform(put(route).header("Authorization","Bearer admin-secret").contentType("application/json").content(json.write(grant))).andExpect(status().isBadRequest());
            assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],grant));
        }
        db.update("update work_units set attempts=1 where id=?",ids[1]);
        assertThrows(ApiException.class,()->computerGrants.grant(admin,ids[0],ids[1],computerGrant(ids[2])));
        assertEquals(0,db.queryForObject("select count(*) from computer_grants",Integer.class));
    }
    @Test void computerGrantRecordsIssuerVersionAndDatabaseRejectsCrossTenantBinding() {
        var ids=computerUnit();computerGrants.grant(admin,ids[0],ids[1],computerGrant(ids[2]));
        assertEquals(admin.authVersion(),db.queryForObject("select issuer_version from computer_grants where unit_id=?",Long.class,ids[1]));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update computer_grants set tenant_id='other-tenant' where unit_id=?",ids[1]));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update computer_grants set expires_at=created_at where unit_id=?",ids[1]));
    }
    @Test void computerCapabilityRegistersButCannotDispatchWithoutDedicatedAuthorization() throws Exception {
        var agentResponse=http.perform(post("/api/v1/agent-definitions").header("Authorization","Bearer admin-secret")
            .contentType("application/json").content(json.write(new Requests.Agent("computer-worker","1.0.0",Capabilities.COMPUTER))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String computer=(String)((Map<?,?>)json.read(agentResponse)).get("agentId");
        String task=(String)control.createTask(owner,"computer-pending",new Requests.Task(computer,"sandbox",List.of("desktop operation"))).get("taskId");
        var response=http.perform(post("/api/v1/nodes").header("Authorization","Bearer admin-secret")
            .contentType("application/json").content(json.write(new Requests.Node("Computer","sandbox",1,Capabilities.COMPUTER))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var computerNode=auth.authenticate((String)((Map<?,?>)json.read(response)).get("token"));
        control.heartbeat(computerNode,true);control.tick();
        assertEquals("NO_WORK",control.poll(computerNode).get("status"));
        assertEquals(0,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id where u.task_id=?",Integer.class,task));
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        assertThrows(ApiException.class,()->browser.grant(admin,task,unit,new BrowserExecution.Grant("https://crm.internal",Set.of("click"),1,null,null)));
        assertEquals(1,((List<?>)control.agentPage(owner,"",50,Capabilities.COMPUTER).get("items")).size());
        var textNode=node("sandbox",1);String textTask=task("ordinary work");control.tick();
        var textWork=control.poll(textNode);
        assertEquals(textTask,db.queryForObject("select task_id from work_units where id=?",String.class,textWork.get("unitId")));
        assertEquals("NO_WORK",control.poll(computerNode).get("status"));
    }
    @Test void unknownCapabilityCannotBecomeImplicitlyExecutable() throws Exception {
        var created=control.registerNode(admin,new Requests.Node("Legacy","sandbox",1));
        String nodeId=(String)created.get("nodeId");
        db.update("update nodes set capability='unreviewed.write.v1' where id=?",nodeId);
        db.update("update agent_versions set capability='unreviewed.write.v1' where id=?",agentId);
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);String unknownTask=task("unreviewed action");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,unknownTask);
        // Even stale scope from a different execution capability cannot authorize it.
        db.update("insert into browser_grants values(?,?,?,true)",owner.tenant(),unit,"{}");
        control.tick();
        assertEquals("NO_WORK",control.poll(node).get("status"));
        assertEquals(0,db.queryForObject("select count(*) from attempts",Integer.class));
        http.perform(post("/api/v1/agent-definitions").header("Authorization","Bearer admin-secret")
            .contentType("application/json").content("{\"name\":\"unknown\",\"version\":\"1.0.0\",\"capability\":\"unreviewed.write.v1\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void enrollmentHttpUsesOnlyAdministratorFixedConfiguration()throws Exception {
        var issued=enrollments.issue(admin,new NodeEnrollment.Issue(new Requests.Node("Installed","sandbox",2,"model.read.v1"),60));
        String token=(String)issued.get("token");
        http.perform(post("/api/v1/node-enrollments/redeem").contentType("application/json").content(json.write(Map.of("token",token,"capacity",16)))).andExpect(status().isBadRequest());
        var reply=http.perform(post("/api/v1/node-enrollments/redeem").contentType("application/json").content(json.write(Map.of("token",token))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.capability").value("model.read.v1")).andReturn().getResponse().getContentAsString();
        var node=db.queryForMap("select n.* from nodes n join node_enrollments e on e.node_id=n.id where e.id=?",issued.get("enrollmentId"));
        assertEquals(2,((Number)node.get("capacity")).intValue());assertEquals("DRAINING",node.get("status"));
        assertEquals("tenant-a",node.get("tenant_id"));
        http.perform(post("/api/v1/node-enrollments/redeem").contentType("application/json").content(json.write(Map.of("token",token)))).andExpect(status().isUnauthorized());
        assertFalse(json.write(db.queryForList("select * from node_enrollments")).contains(token));
        assertFalse(json.write(db.queryForList("select payload_json from events")).contains(token));
        assertThrows(ApiException.class,()->enrollments.revoke(admin,(String)issued.get("enrollmentId")));
    }
    @Test void enrollmentExpiresRevokesAndRechecksIssuerAuthorization() {
        var request=new NodeEnrollment.Issue(new Requests.Node("Worker","sandbox",1),30);
        var expired=enrollments.issue(admin,request);time.advance(30000);
        assertThrows(ApiException.class,()->enrollments.redeem((String)expired.get("token")));
        var revoked=enrollments.issue(admin,request);enrollments.revoke(admin,(String)revoked.get("enrollmentId"));
        assertThrows(ApiException.class,()->enrollments.redeem((String)revoked.get("token")));
        var disabled=enrollments.issue(admin,request);auth.disable(admin,admin.id());
        assertThrows(ApiException.class,()->enrollments.redeem((String)disabled.get("token")));
        assertEquals(0,db.queryForObject("select count(*) from nodes",Integer.class));
    }
    @Test void concurrentEnrollmentConsumptionCreatesExactlyOneNode()throws Exception {
        String token=(String)enrollments.issue(admin,new NodeEnrollment.Issue(new Requests.Node("Worker","sandbox",1),60)).get("token");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> redeem=()->{gate.await();try{enrollments.redeem(token);return true;}catch(ApiException e){assertEquals(401,e.status);return false;}};
            var a=pool.submit(redeem);var b=pool.submit(redeem);gate.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("select count(*) from nodes",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void enrollmentIssueAndRevocationAreTenantAndZoneScoped()throws Exception {
        var request=new NodeEnrollment.Issue(new Requests.Node("Worker","sandbox",1),60);
        assertThrows(ApiException.class,()->enrollments.issue(owner,request));
        var issued=enrollments.issue(admin,request);
        auth.create("foreign-admin","tenant-b","USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),"foreign-token");
        assertThrows(ApiException.class,()->enrollments.revoke(auth.authenticate("foreign-token"),(String)issued.get("enrollmentId")));
        http.perform(post("/api/v1/node-enrollments").contentType("application/json").content(json.write(request))).andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/node-enrollments").header("Authorization","Bearer admin-secret").contentType("application/json")
            .content("{\"node\":{\"name\":\"bad\",\"zone\":\"sandbox\",\"capacity\":17},\"expiresInSeconds\":60}")).andExpect(status().isBadRequest());
        db.update("update principals set auth_version=auth_version+1 where id=?",admin.id());
        assertThrows(ApiException.class,()->enrollments.redeem((String)issued.get("token")));
    }
    @Test void enrollmentStatusSupportsLostReplyRecoveryWithoutExposingSecrets()throws Exception {
        var issued=enrollments.issue(admin,new NodeEnrollment.Issue(new Requests.Node("Worker","sandbox",1),30));
        String id=(String)issued.get("enrollmentId");
        assertEquals("ISSUED",enrollments.status(admin,id).get("state"));
        var node=enrollments.redeem((String)issued.get("token"));
        var status=enrollments.status(admin,id);assertEquals("CONSUMED",status.get("state"));
        assertEquals(node.get("nodeId"),status.get("nodeId"));assertEquals(true,status.get("nodeEnabled"));
        auth.disable(admin,(String)node.get("nodeId"));assertEquals(false,enrollments.status(admin,id).get("nodeEnabled"));
        assertFalse(json.write(status).contains((String)issued.get("token")));assertFalse(json.write(status).contains((String)node.get("token")));
        http.perform(get("/api/v1/node-enrollments/"+id).header("Authorization","Bearer admin-secret")).andExpect(status().isOk())
            .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.token_hash").doesNotExist()).andExpect(jsonPath("$.nodeEnabled").value(false));
        http.perform(get("/api/v1/node-enrollments/"+id)).andExpect(status().isUnauthorized());
        assertThrows(ApiException.class,()->enrollments.status(owner,id));
        auth.create("foreign-status","tenant-b","USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),"foreign-status-token");
        assertThrows(ApiException.class,()->enrollments.status(auth.authenticate("foreign-status-token"),id));
        var expired=enrollments.issue(admin,new NodeEnrollment.Issue(new Requests.Node("Expired","sandbox",1),30));time.advance(30000);
        assertEquals("EXPIRED",enrollments.status(admin,(String)expired.get("enrollmentId")).get("state"));
    }
    String certificatePem(String name)throws Exception {
        try(var input=getClass().getResourceAsStream("/certificates/"+name+".pem")){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);}
    }
    java.security.cert.X509Certificate certificate(String name)throws Exception {
        return (java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certificatePem(name).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    @Test void nodeCertificateBindingRequiresTransportProofAndRevocationNeverFallsBack()throws Exception {
        var created=control.registerNode(admin,new Requests.Node("Bound","sandbox",1));String id=(String)created.get("nodeId"),token=(String)created.get("token");
        var old=auth.authenticate(token);certificates.bind(admin,id,new NodeCertificates.Binding(certificatePem("node")));
        assertThrows(ApiException.class,()->auth.current(old));
        http.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/me").secure(true).header("Authorization","Bearer "+token).header("X-Client-Cert",certificatePem("node"))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/me").secure(true).header("Authorization","Bearer "+token).requestAttr("jakarta.servlet.request.X509Certificate",new java.security.cert.X509Certificate[]{certificate("other")})).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/me").secure(true).header("Authorization","Bearer "+token).requestAttr("jakarta.servlet.request.X509Certificate",new java.security.cert.X509Certificate[]{certificate("node")})).andExpect(status().isOk());
        certificates.revoke(admin,id);
        http.perform(get("/api/v1/me").secure(true).header("Authorization","Bearer "+token).requestAttr("jakarta.servlet.request.X509Certificate",new java.security.cert.X509Certificate[]{certificate("node")})).andExpect(status().isUnauthorized());
    }
    @Test void certificateBindingHasAdministratorZoneScopeAndUniqueNodeOwnership()throws Exception {
        var a=node("sandbox",1);var b=node("sandbox",1);var binding=new NodeCertificates.Binding(certificatePem("node"));
        assertThrows(ApiException.class,()->certificates.bind(owner,a.id(),binding));
        assertThrows(ApiException.class,()->certificates.bind(admin,a.id(),new NodeCertificates.Binding("not a certificate")));
        certificates.bind(admin,a.id(),binding);certificates.bind(admin,a.id(),binding);
        assertThrows(ApiException.class,()->certificates.bind(admin,b.id(),binding));
        certificates.bind(admin,a.id(),new NodeCertificates.Binding(certificatePem("other")));
        var req=new org.springframework.mock.web.MockHttpServletRequest();req.setSecure(true);req.setAttribute("jakarta.servlet.request.X509Certificate",new java.security.cert.X509Certificate[]{certificate("node")});
        assertThrows(ApiException.class,()->certificates.verify(auth.current(new Identity(a.id(),a.tenant(),a.kind(),a.roles(),a.zones(),3)),req));
        var strict=new NodeCertificates(db,auth,locks,time,true);
        assertThrows(ApiException.class,()->strict.verify(b,req));
    }
    @Test void taskTitlesFiltersAndRetriesPreserveOwnership() throws Exception {
        var request=new Requests.Task(agentId,"sandbox",List.of("private input"),List.of(),List.of(),true,"  Report 100%_done  ");
        String id=(String)control.createTask(owner,"title-key",request).get("taskId");
        assertEquals(true,control.createTask(owner,"title-key",request).get("reused"));
        assertThrows(ApiException.class,()->control.createTask(owner,"title-key",new Requests.Task(agentId,"sandbox",List.of("private input"),List.of(),List.of(),true,"Changed")));
        control.createTask(owner,"similar-title",new Requests.Task(agentId,"sandbox",List.of("private"),List.of(),List.of(),false,"Report 100xxdone"));
        control.createTask(other,"private",request);
        var page=control.listTasks(owner,"",50,"RUNNING","sandbox","100%_",time.millis(),time.millis()+1);
        var rows=(List<?>)page.get("items");assertEquals(1,rows.size());
        var row=(Map<?,?>)rows.get(0);assertEquals(id,row.get("id"));assertEquals("Report 100%_done",row.get("title"));assertEquals("Owner",row.get("owner_name"));
        assertEquals("Report 100%_done",control.getTask(owner,id).get("title"));
        assertTrue(((List<?>)control.listTasks(owner,"",50,"FAILED","","",0,0).get("items")).isEmpty());
        assertTrue(((List<?>)control.listTasks(owner,"",50,"","","",0,time.millis()).get("items")).isEmpty());
        assertTrue(((List<?>)control.listTasks(admin,"",50,"","","100%_",0,0).get("items")).isEmpty());
        assertThrows(ApiException.class,()->control.listTasks(owner,"",50,"","other","",0,0));
        assertThrows(ApiException.class,()->control.listTasks(owner,"",50,"INVALID","","",0,0));
        assertThrows(ApiException.class,()->control.listTasks(owner,"",50,"","","",2,1));
        http.perform(get("/api/v1/tasks").param("query","100%_").param("state","RUNNING").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].title").value("Report 100%_done"));
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").header("Idempotency-Key","too-long-title").contentType("application/json")
            .content(json.write(Map.of("agentId",agentId,"zone","sandbox","inputs",List.of("test"),"title","x".repeat(161)))))
            .andExpect(status().isBadRequest());
    }
    @Test void taskSummaryMatchesScopedListsAndRefreshesAuthorization() throws Exception {
        String first=task("first"),second=task("second");
        db.update("update tasks set state='RECONCILING' where id=?",first);
        db.update("update tasks set state='FAILED' where id=?",second);
        control.createTask(other,"other-summary",new Requests.Task(agentId,"sandbox",List.of("private")));
        var summary=control.taskSummary(owner);assertEquals(2L,((Number)summary.get("total")).longValue());
        for(var item:(List<?>)summary.get("items")){
            var row=(Map<?,?>)item;
            assertEquals(((Number)row.get("count")).intValue(),((List<?>)control.listTasks(owner,"",100,(String)row.get("state"),"","",0,0).get("items")).size());
        }
        assertEquals(0L,((Number)control.taskSummary(admin).get("total")).longValue());
        http.perform(get("/api/v1/tasks/summary").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        db.update("update principals set zones='[]' where id=?",owner.id());
        assertEquals(0L,((Number)control.taskSummary(owner).get("total")).longValue());
        db.update("update principals set roles='[\"ADMIN\"]' where id=?",owner.id());
        assertThrows(ApiException.class,()->control.taskSummary(owner));
    }
    @Test void capabilityDirectorySearchIsPagedPublishedAndTenantScoped() throws Exception {
        String first=(String)control.publishAgent(admin,new Requests.Agent("Office 100%_one","1.0.0","model.read.v1")).get("agentId");
        control.publishAgent(admin,new Requests.Agent("Office 100%_two","1.0.0","model.read.v1"));
        control.publishAgent(admin,new Requests.Agent("Office 100anything","1.0.0","model.read.v1"));
        control.publishAgent(admin,new Requests.Agent("Office 100%_computer","1.0.0","computer.write.v1"));
        var page=control.agentPage(owner,"",1,"model.read.v1","100%_");assertEquals(1,((List<?>)page.get("items")).size());
        var next=control.agentPage(owner,(String)page.get("nextCursor"),1,"model.read.v1","100%_");assertEquals(1,((List<?>)next.get("items")).size());assertFalse(next.containsKey("nextCursor"));
        assertEquals(1,((List<?>)control.agentPage(owner,"",50,"",first).get("items")).size());
        assertEquals(1,((List<?>)control.agentPage(owner,"",50,"computer.write.v1","").get("items")).size());
        assertThrows(ApiException.class,()->control.agentPage(owner,"",50,"","x".repeat(101)));
        auth.create("directory-foreign","tenant-b","USER","Foreign",Set.of("VIEWER"),Set.of("sandbox"),"directory-foreign-token");
        assertEquals(List.of(),control.agentPage(auth.authenticate("directory-foreign-token"),"",50,"","").get("items"));
        http.perform(get("/api/v1/agent-definitions/page").param("query","100%_").param("capability","model.read.v1").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        db.update("update agent_versions set status='DRAFT' where id=?",first);
        assertEquals(List.of(),control.agentPage(owner,"",50,"",first).get("items"));
    }
    @Test void activityUsageIsScopedPagedAndDoesNotInventModelConsumption() throws Exception {
        long from=time.millis();String first=task("private-body");
        control.createTask(other,"usage-other",new Requests.Task(agentId,"sandbox",List.of("secret")));
        control.createTask(admin,"usage-other-zone",new Requests.Task(agentId,"other",List.of("not-visible")));
        db.update("update tasks set state='SUCCEEDED' where id=?",first);
        db.update("update principals set zones='[\"sandbox\"]' where id=?",admin.id());
        var result=usage.read(admin,from,from+1,"",1);var summary=(Map<?,?>)result.get("summary");assertEquals(2L,((Number)summary.get("tasks")).longValue());assertEquals(2L,((Number)summary.get("members")).longValue());assertEquals(1L,((Number)summary.get("succeeded")).longValue());
        assertNull(result.get("inputTokens"));assertNull(result.get("cost"));assertEquals(false,((Map<?,?>)result.get("modelConsumption")).get("budgetEnforcementAvailable"));
        assertFalse(json.write(result).contains("private-body"));assertFalse(json.write(result).contains("secret"));
        assertEquals(1,((List<?>)result.get("items")).size());assertEquals(1,((List<?>)usage.read(admin,from,from+1,(String)result.get("nextCursor"),1).get("items")).size());
        db.update("update principals set zones='[]' where id=?",admin.id());
        var narrowed=usage.read(admin,from,from+1,(String)result.get("nextCursor"),1);
        assertEquals(List.of(),narrowed.get("items"));assertEquals(Set.of(),narrowed.get("zones"));
        assertEquals(0,((Map<?,?>)narrowed.get("summary")).get("tasks"));
        db.update("update principals set zones='[\"sandbox\"]' where id=?",admin.id());
        assertEquals(0L,((Number)((Map<?,?>)usage.read(admin,from+1,from+2,"",50).get("summary")).get("tasks")).longValue());
        assertThrows(ApiException.class,()->usage.read(owner,from,from+1,"",50));
        assertThrows(ApiException.class,()->usage.read(admin,from,from+94L*86400000,"",50));
        assertThrows(ApiException.class,()->usage.read(admin,from,from,"",50));
        auth.create("usage-foreign","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"usage-foreign-token");
        assertEquals(List.of(),usage.read(auth.authenticate("usage-foreign-token"),from,from+1,"",50).get("items"));
        assertEquals("usage",ManagementIdentity.module("/api/v1/management-usage"));
        http.perform(get("/api/v1/management-usage").param("from",String.valueOf(from)).param("until",String.valueOf(from+1)).header("Authorization","Bearer admin-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.tasks").value(2)).andExpect(jsonPath("$.modelConsumption.coverage").value("UNAVAILABLE"));
    }
    @Test void auditProjectionRedactsPayloadsAndKeepsHistoricalScope() throws Exception {
        long from=time.millis();String task=task("private task text");
        auth.create("audit-other-zone",admin.tenant(),"USER","Other zone",Set.of("VIEWER"),Set.of("other"),"audit-other-zone-token");
        auth.audit(admin,"AUDIT_FIXTURE",Map.of("principalId",owner.id(),"password","secret-password","nested",Map.of("apiKey","secret-key")));
        db.update("update principals set zones='[\"sandbox\"]' where id=?",admin.id());
        assertEquals(List.of(),audit.page(admin,from,from+1,0,50,"AUDIT_FIXTURE","").get("items"));
        auth.audit(auth.current(admin),"AUDIT_FIXTURE",Map.of("principalId",owner.id(),"password","secret-password","token","private-token","version",3,"profileId","audit-model-profile","operationId","audit-operation"));
        var page=audit.page(admin,from,from+1,0,50,"AUDIT_FIXTURE",owner.id());var rows=(List<?>)page.get("items");assertEquals(1,rows.size());String serialized=json.write(page);assertFalse(serialized.contains("secret-password"));assertFalse(serialized.contains("private-token"));assertFalse(serialized.contains("nested"));
        var details=(Map<?,?>)((Map<?,?>)rows.get(0)).get("details");assertEquals(Set.of("principalId","profileId","operationId","version"),details.keySet());
        assertEquals(1,((List<?>)audit.page(admin,from,from+1,0,50,"AUDIT_FIXTURE","audit-model-profile").get("items")).size());
        assertEquals(1,((List<?>)audit.page(admin,from,from+1,0,50,"AUDIT_FIXTURE","audit-operation").get("items")).size());
        assertEquals(1,((List<?>)audit.page(admin,from,from+1,0,50,"TASK_CREATED",task).get("items")).size());assertFalse(json.write(audit.page(admin,from,from+1,0,50,"TASK_CREATED",task)).contains("private task text"));
        db.update("insert into events(tenant_id,actor_id,event_type,payload_json,created_at) values(?,?,?,?,?)",admin.tenant(),admin.id(),"LEGACY_FIXTURE","{}",from);
        assertEquals(List.of(),audit.page(admin,from,from+1,0,50,"LEGACY_FIXTURE","").get("items"));
        db.update("update principals set zones='[\"sandbox\",\"other\"]' where id=?",admin.id());assertEquals(1,((List<?>)audit.page(admin,from,from+1,0,50,"LEGACY_FIXTURE","").get("items")).size());
        assertThrows(ApiException.class,()->audit.page(owner,from,from+1,0,50,"",""));
        http.perform(get("/api/v1/management-audit").param("from",String.valueOf(from)).param("until",String.valueOf(from+1)).param("type","AUDIT_FIXTURE").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }
    @Test void skillLifecycleAuditCanBeCorrelatedWithoutExposingInstructions() {
        long from=time.millis();String instructions="private skill instruction text";
        String skill=(String)publishedSkills.publish(admin,new PublishedSkills.Publish("audit-skill","1.0.0","sandbox",instructions)).get("id");
        String agent=(String)control.publishAgent(admin,new Requests.Agent("audit-model","1.0.0","model.read.v1")).get("agentId");
        publishedSkills.bind(admin,agent,skill,true);publishedSkills.bind(admin,agent,skill,false);publishedSkills.disable(admin,skill);
        var page=audit.page(admin,from,from+1,0,50,"",skill);var rows=(List<?>)page.get("items");
        assertEquals(4,rows.size());
        for(var value:rows){var details=(Map<?,?>)((Map<?,?>)value).get("details");assertEquals(skill,details.get("skillId"));assertFalse(details.containsKey("sha256"));}
        assertFalse(json.write(page).contains(instructions));
        assertThrows(ApiException.class,()->audit.page(owner,from,from+1,0,50,"",skill));
    }
    @Test void auditCursorAdvancesOverHiddenEventsAndNeverCrossesTenants(){
        long from=time.millis();auth.audit(owner,"CURSOR_FIXTURE",Map.of("principalId",owner.id()));
        for(int i=0;i<3;i++)auth.audit(admin,"CURSOR_FIXTURE",Map.of("principalId",other.id()));
        db.update("update principals set zones='[\"sandbox\"]' where id=?",admin.id());
        var first=audit.page(admin,from,from+1,0,1,"CURSOR_FIXTURE","");assertEquals(List.of(),first.get("items"));assertTrue(first.containsKey("nextCursor"));
        var second=audit.page(admin,from,from+1,((Number)first.get("nextCursor")).longValue(),1,"CURSOR_FIXTURE","");assertEquals(1,((List<?>)second.get("items")).size());assertFalse(second.containsKey("nextCursor"));
        auth.create("audit-foreign","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"audit-foreign-token");assertEquals(List.of(),audit.page(auth.authenticate("audit-foreign-token"),from,from+1,0,50,"","").get("items"));
        assertThrows(ApiException.class,()->audit.page(admin,from,from+94L*86400000,0,50,"",""));assertEquals("audit",ManagementIdentity.module("/api/v1/management-audit"));
    }
    @Test void workbenchTaskPagesAreOwnerTenantAndZoneScoped() {
        task("first");task("second");
        control.createTask(other,"other",new Requests.Task(agentId,"sandbox",List.of("private")));
        assertEquals(2,((List<?>)control.listTasks(owner,"",50).get("items")).size());
        assertEquals(0,((List<?>)control.listTasks(admin,"",50).get("items")).size());
        var first=control.listTasks(owner,"",1);assertEquals(1,((List<?>)first.get("items")).size());
        assertEquals(1,((List<?>)control.listTasks(owner,(String)first.get("nextCursor"),1).get("items")).size());
        assertThrows(ApiException.class,()->control.listTasks(owner,"",101));
        assertEquals(1,control.availableAgents(owner).size());
        db.update("update principals set zones=? where id=?",json.write(Set.of("other")),owner.id());
        assertEquals(0,((List<?>)control.listTasks(owner,"",50).get("items")).size());
    }
    @Test void nodeInventoryIsPagedAndLimitedToCurrentAdminZones() throws Exception {
        node("sandbox",1);node("sandbox",1);node("other",1);
        auth.create("foreign-admin","tenant-b","USER","B",Set.of("ADMIN"),Set.of("sandbox"),"foreign-token");
        control.registerNode(auth.authenticate("foreign-token"),new Requests.Node("foreign","sandbox",1));
        db.update("update principals set zones=? where id=?",json.write(Set.of("sandbox")),admin.id());
        var first=control.listNodes(admin,"",1);assertEquals(1,((List<?>)first.get("items")).size());
        var second=control.listNodes(admin,(String)first.get("nextCursor"),1);assertEquals(1,((List<?>)second.get("items")).size());assertFalse(second.containsKey("nextCursor"));
        assertThrows(ApiException.class,()->control.listNodes(owner,"",50));
        assertThrows(ApiException.class,()->control.listNodes(admin,"",101));
        http.perform(get("/api/v1/nodes").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].token").doesNotExist());
        http.perform(get("/api/v1/nodes").header("Authorization","Bearer owner-secret")).andExpect(status().isForbidden());
        db.update("update principals set zones='[]' where id=?",admin.id());
        assertTrue(((List<?>)control.listNodes(admin,"",50).get("items")).isEmpty());
    }
    @Test void nodeInventoryDistinguishesFreshnessDrainingAndOccupiedSlots() {
        Identity node=node("sandbox",2);task("one");assertEquals(1,control.tick());
        var item=(Map<?,?>)((List<?>)control.listNodes(admin,"",50).get("items")).get(0);
        assertEquals("READY",item.get("availability"));assertEquals(1L,item.get("occupiedSlots"));assertEquals(1L,item.get("availableSlots"));
        control.poll(node);control.heartbeat(node,false);
        item=(Map<?,?>)((List<?>)control.listNodes(admin,"",50).get("items")).get(0);
        assertEquals("DRAINING",item.get("availability"));assertEquals(1L,item.get("occupiedSlots"));assertEquals(0L,item.get("availableSlots"));
        time.advance(120000);
        item=(Map<?,?>)((List<?>)control.listNodes(admin,"",50).get("items")).get(0);
        assertEquals("OFFLINE",item.get("availability"));assertEquals(0L,item.get("occupiedSlots"));
        db.update("update principals set enabled=false where id=?",node.id());
        item=(Map<?,?>)((List<?>)control.listNodes(admin,"",50).get("items")).get(0);
        assertEquals("DISABLED",item.get("availability"));
    }
    @Test void nodeAdmissionReasonIsBoundedConsistentAndUpdatedOnlyByNode() throws Exception {
        var registered=control.registerNode(admin,new Requests.Node("Paused","sandbox",1));
        String token=(String)registered.get("token"),id=(String)registered.get("nodeId");
        http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer "+token).contentType("application/json").content("{\"accepting\":false,\"reason\":\"MEMORY_LOW\"}")).andExpect(status().isNoContent());
        http.perform(get("/api/v1/nodes").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].admission_reason").value("MEMORY_LOW"));
        http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer "+token).contentType("application/json").content("{\"accepting\":true,\"reason\":\"MEMORY_LOW\"}")).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer "+token).contentType("application/json").content("{\"accepting\":false,\"reason\":\"arbitrary-data\"}")).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"accepting\":true}")).andExpect(status().isForbidden());
        assertEquals("MEMORY_LOW",db.queryForObject("select admission_reason from nodes where id=?",String.class,id));
        control.heartbeat(auth.authenticate(token),true);
        assertEquals("READY",db.queryForObject("select admission_reason from nodes where id=?",String.class,id));
    }
    Identity node(String zone,int capacity) {
        var created=control.registerNode(admin,new Requests.Node("Worker",zone,capacity));
        Identity n=auth.authenticate((String)created.get("token"));control.heartbeat(n,true);return n;
    }
    String task(String... inputs) {
        return (String)control.createTask(owner,UUID.randomUUID().toString(),new Requests.Task(agentId,"sandbox",List.of(inputs))).get("taskId");
    }
    Requests.Result result(Map<String,Object> work) {
        String input=(String)work.get("input");var expected=TextStats.compute(input);
        return new Requests.Result(((Number)work.get("epoch")).longValue(),(String)expected.get("sha256"),(Integer)expected.get("codePoints"),(Integer)expected.get("lines"));
    }
    String claimedRecoveryCase(){
        node("sandbox",1);task("private-task");control.tick();db.update("update attempts set recovery_generation='previous'");
        recoveryCases.discover(admin,"sandbox",10);String id=db.queryForObject("select id from recovery_cases",String.class);
        recoveryCases.claim(admin,id,0);return id;
    }
    @Test void recoveryEvidencePersistsExactContentAndRetriesWithoutDuplicateOrRelease() throws Exception {
        String id=claimedRecoveryCase(),content="operator claim: 停止待核实";
        var body=new RecoveryCases.Submission(1L,"note-1","STOP_ATTESTATION",content,AuthService.hash(content));
        var first=recoveryCases.submitEvidence(admin,id,body);
        assertEquals(first,recoveryCases.submitEvidence(admin,id,body));
        assertEquals(false,first.get("physicalStopVerified"));assertEquals(false,first.get("sourceVerified"));
        assertEquals(content,recoveryCases.evidence(admin,id,(String)first.get("evidenceId")).get("content_text"));
        var second=new RecoveryCases.Submission(2L,"note-2","OPERATOR_NOTE","another",AuthService.hash("another"));
        recoveryCases.submitEvidence(admin,id,second);
        assertEquals(first,recoveryCases.submitEvidence(admin,id,body));
        var page=recoveryCases.evidencePage(admin,id,0,1);assertEquals(2L,((Number)page.get("nextCursor")).longValue());
        assertFalse(json.write(page).contains(content));assertFalse(recoveryCases.evidencePage(admin,id,2,1).containsKey("nextCursor"));
        assertEquals(2,db.queryForObject("select count(*) from recovery_evidence",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from events where event_type='RECOVERY_EVIDENCE_SUBMITTED'",Integer.class));
        assertFalse(db.queryForList("select payload_json from events where event_type='RECOVERY_EVIDENCE_SUBMITTED'").toString().contains(content));
        assertEquals("EVIDENCE_KEY_CONFLICT",assertThrows(ApiException.class,()->recoveryCases.submitEvidence(admin,id,new RecoveryCases.Submission(1L,"note-1","OPERATOR_NOTE",content,AuthService.hash(content)))).code);
        assertEquals("RECOVERY_CASE_CONFLICT",assertThrows(ApiException.class,()->recoveryCases.submitEvidence(admin,id,new RecoveryCases.Submission(1L,"stale","OPERATOR_NOTE",content,AuthService.hash(content)))).code);
        assertThrows(ApiException.class,()->recoveryCases.submitEvidence(admin,id,new RecoveryCases.Submission(3L,"bad","OPERATOR_NOTE",content,"a".repeat(64))));
        assertThrows(ApiException.class,()->recoveryCases.submitEvidence(admin,id,new RecoveryCases.Submission(3L,"big","OPERATOR_NOTE","x".repeat(16385),AuthService.hash("x".repeat(16385)))));
        http.perform(post("/api/v1/recovery/cases/"+id+"/evidence").header("Authorization","Bearer admin-secret").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertEquals(0,control.tick());assertEquals(1,db.queryForObject("select count(*) from attempts where state='RESERVED'",Integer.class));
    }
    @Test void recoveryEvidenceRequiresCurrentClaimantAndScopedLivePermissions(){
        String id=claimedRecoveryCase();var body=new RecoveryCases.Submission(1L,"note","NODE_COMPARISON","claim",AuthService.hash("claim"));
        auth.create("peer","tenant-a","USER","Peer",Set.of("ADMIN"),Set.of("sandbox"),"peer-token");var peer=auth.authenticate("peer-token");
        assertThrows(ApiException.class,()->recoveryCases.submitEvidence(peer,id,body));
        var receipt=recoveryCases.submitEvidence(admin,id,body);String evidence=(String)receipt.get("evidenceId");
        assertThrows(ApiException.class,()->recoveryCases.evidence(owner,id,evidence));
        auth.create("foreign","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"foreign-token");var foreign=auth.authenticate("foreign-token");
        assertEquals(404,assertThrows(ApiException.class,()->recoveryCases.evidence(foreign,id,evidence)).status);
        db.update("update recovery_cases set recovery_generation='previous' where id=?",id);
        assertEquals("STALE_RECOVERY_CASE",assertThrows(ApiException.class,()->recoveryCases.submitEvidence(admin,id,body)).code);
        db.update("update principals set auth_version=auth_version+1 where id='admin'");
        assertThrows(ApiException.class,()->recoveryCases.evidence(admin,id,evidence));
    }
    @Test void recoveryEvidenceConcurrentVersionsAcceptOneSubmission() throws Exception {
        String id=claimedRecoveryCase();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Boolean>>();
            for(String key:List.of("one","two"))futures.add(pool.submit(()->{start.await();try{recoveryCases.submitEvidence(admin,id,new RecoveryCases.Submission(1L,key,"OPERATOR_NOTE",key,AuthService.hash(key)));return true;}catch(ApiException error){assertEquals("RECOVERY_CASE_CONFLICT",error.code);return false;}}));
            start.countDown();int count=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))count++;
            assertEquals(1,count);assertEquals(1,db.queryForObject("select count(*) from recovery_evidence",Integer.class));
            assertEquals(2,db.queryForObject("select version from recovery_cases where id=?",Integer.class,id));
        }finally{pool.shutdownNow();}
    }
    @Test void recoveryTaskFilterKeepsRecoveryPermissionsAndPagination() throws Exception {
        node("sandbox",3);String id=task("one","two");String otherId=task("three");control.tick();
        db.update("update attempts set recovery_generation='previous'");recoveryCases.discover(admin,"sandbox",10);
        var page=recoveryCases.page(admin,"sandbox","",1,id);
        var rows=(List<?>)page.get("items");assertEquals(1,rows.size());assertEquals(id,((Map<?,?>)rows.get(0)).get("task_id"));
        var next=recoveryCases.page(admin,"sandbox",(String)page.get("nextCursor"),1,id);assertEquals(1,((List<?>)next.get("items")).size());assertFalse(next.containsKey("nextCursor"));
        assertEquals(1,((List<?>)recoveryCases.page(admin,"sandbox","",50,otherId).get("items")).size());
        assertThrows(ApiException.class,()->recoveryCases.page(owner,"sandbox","",50,id));
        assertThrows(ApiException.class,()->recoveryCases.page(admin,"forbidden","",50,id));
        assertThrows(ApiException.class,()->recoveryCases.page(admin,"sandbox","",50,"invalid%"));
        auth.create("foreign-filter","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"foreign-filter-token");
        assertEquals(List.of(),recoveryCases.page(auth.authenticate("foreign-filter-token"),"sandbox","",50,id).get("items"));
        http.perform(get("/api/v1/recovery/cases").param("zone","sandbox").param("taskId",otherId).header("Authorization","Bearer admin-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].task_id").value(otherId));
    }
    @Test void recoveryCasesDiscoverInBatchesAndClaimWithoutReleasingAttempts() throws Exception {
        var n=node("sandbox",2);String taskId=task("private-input-one","private-input-two");control.tick();
        db.update("update attempts set recovery_generation='previous'");
        var first=recoveryCases.discover(admin,"sandbox",1);
        assertEquals(1,first.get("created"));assertEquals(true,first.get("moreUndiscovered"));
        assertEquals(1,recoveryCases.discover(admin,"sandbox",1).get("created"));
        assertEquals(0,recoveryCases.discover(admin,"sandbox",1).get("created"));
        var page=recoveryCases.page(admin,"sandbox","",1);
        String cursor=(String)page.get("nextCursor");assertNotNull(cursor);
        assertFalse(recoveryCases.page(admin,"sandbox",cursor,1).containsKey("nextCursor"));
        String id=db.queryForObject("select min(id) from recovery_cases",String.class);
        http.perform(post("/api/v1/recovery/cases/"+id+"/claim").header("Authorization","Bearer admin-secret")
            .contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("IN_REVIEW")).andExpect(jsonPath("$.releaseAllowed").value(false));
        assertEquals("RECOVERY_CASE_CONFLICT",assertThrows(ApiException.class,()->recoveryCases.claim(admin,id,0)).code);
        assertEquals(0,control.tick());assertEquals("NO_WORK",control.poll(n).get("status"));
        assertEquals(2,db.queryForObject("select count(*) from attempts where state='RESERVED'",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from unit_results",Integer.class));
        assertFalse(json.write(page).contains("private-input"));
        assertEquals(1,db.queryForObject("select count(*) from events where event_type='RECOVERY_CASE_CLAIMED'",Integer.class));
    }
    @Test void recoveryCasesEnforceRoleTenantZoneAndLiveRevocation() throws Exception {
        node("sandbox",1);task("private");control.tick();db.update("update attempts set recovery_generation='previous'");
        assertThrows(ApiException.class,()->recoveryCases.discover(owner,"sandbox",10));
        assertThrows(ApiException.class,()->recoveryCases.discover(admin,"forbidden",10));
        assertThrows(ApiException.class,()->recoveryCases.discover(admin,"sandbox",101));
        recoveryCases.discover(admin,"sandbox",10);String id=db.queryForObject("select id from recovery_cases",String.class);
        auth.create("limited","tenant-a","USER","Limited",Set.of("ADMIN"),Set.of("other"),"limited-token");
        var limited=auth.authenticate("limited-token");
        assertThrows(ApiException.class,()->recoveryCases.claim(limited,id,0));
        assertEquals(List.of(),recoveryCases.page(limited,"other","",10).get("items"));
        auth.create("foreign","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"foreign-token");
        var foreign=auth.authenticate("foreign-token");
        assertEquals(404,assertThrows(ApiException.class,()->recoveryCases.claim(foreign,id,0)).status);
        assertEquals(List.of(),recoveryCases.page(foreign,"sandbox","",10).get("items"));
        db.update("update principals set auth_version=auth_version+1 where id='admin'");
        assertThrows(ApiException.class,()->recoveryCases.claim(admin,id,0));
        assertEquals("NEEDS_ATTENTION",db.queryForObject("select state from recovery_cases where id=?",String.class,id));
        http.perform(get("/api/v1/recovery/cases").param("zone","sandbox").header("Authorization","Bearer owner-secret")).andExpect(status().isForbidden());
    }
    @Test void recoveryCaseConcurrentClaimHasOneWinner() throws Exception {
        node("sandbox",1);task("private");control.tick();db.update("update attempts set recovery_generation='previous'");
        recoveryCases.discover(admin,"sandbox",10);String id=db.queryForObject("select id from recovery_cases",String.class);
        auth.create("second-admin","tenant-a","USER","Second",Set.of("ADMIN"),Set.of("sandbox"),"second-token");
        var second=auth.authenticate("second-token");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(var actor:List.of(admin,second))futures.add(pool.submit(()->{start.await();try{recoveryCases.claim(actor,id,0);return true;}catch(ApiException error){assertEquals("RECOVERY_CASE_CONFLICT",error.code);return false;}}));
            start.countDown();int winners=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,db.queryForObject("select version from recovery_cases where id=?",Integer.class,id));
        } finally {pool.shutdownNow();}
    }
    @Test void recoveryCasePreviousRecoveryCannotBeClaimed() {
        node("sandbox",1);task("private");control.tick();db.update("update attempts set recovery_generation='previous'");
        recoveryCases.discover(admin,"sandbox",10);String id=db.queryForObject("select id from recovery_cases",String.class);
        db.update("update recovery_cases set recovery_generation='older-recovery' where id=?",id);
        assertEquals("STALE_RECOVERY_CASE",assertThrows(ApiException.class,()->recoveryCases.claim(admin,id,0)).code);
        assertEquals(1,recoveryCases.discover(admin,"sandbox",10).get("created"));
        assertEquals(2,db.queryForObject("select count(*) from recovery_cases",Integer.class));
    }
    @Test void restoredOldAttemptCannotRenewSubmitAckOrAutomaticallyRetry() {
        var n=node("sandbox",1);String id=task("old generation");control.tick();
        var messages=control.messages(n);String message=(String)messages.get(0).get("messageId");
        var work=control.acknowledge(n,message);String attempt=(String)work.get("attemptId");
        assertEquals("initial",work.get("recoveryGeneration"));
        db.update("update attempts set recovery_generation='previous' where id=?",attempt);
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.renew(n,attempt,1)).code);
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.result(n,attempt,result(work))).code);
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.acknowledge(n,message)).code);
        assertEquals("NO_WORK",control.poll(n).get("status"));
        time.value.addAndGet(200000);control.heartbeat(n,false);assertEquals(0,control.tick());
        assertEquals("RUNNING",db.queryForObject("select state from attempts where id=?",String.class,attempt));
        assertEquals(1,db.queryForObject("select attempts from work_units where task_id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("select count(*) from unit_results",Integer.class));
    }
    @Test void oldReservationIsNotRefundedOrRedispatchedByAdmissionChanges() {
        var n=node("sandbox",1);String id=task("reserved");control.tick();
        String attempt=db.queryForObject("select id from attempts where node_id=?",String.class,n.id());
        db.update("update attempts set recovery_generation='previous' where id=?",attempt);
        control.nodeAdmission(admin,n.id(),true);control.heartbeat(n,false);
        assertEquals(0,control.tick());
        assertEquals("RESERVED",db.queryForObject("select state from attempts where id=?",String.class,attempt));
        assertEquals(1,db.queryForObject("select attempts from work_units where task_id=?",Integer.class,id));
        assertTrue(control.messages(n).isEmpty());
    }
    @Test void completedOldGenerationReceiptCannotBeReacceptedAsCurrent() {
        var n=node("sandbox",1);task("completed");control.tick();var work=control.poll(n);
        String attempt=(String)work.get("attemptId");control.result(n,attempt,result(work));
        db.update("update attempts set recovery_generation='previous' where id=?",attempt);
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.result(n,attempt,result(work))).code);
        assertEquals(1,db.queryForObject("select count(*) from unit_results",Integer.class));
    }
    @Test void appendingVersionKeepsRunningAttemptBoundAndRetryIsIdempotent() throws Exception {
        Identity node=node("sandbox",1);String id=task("root","independent");control.tick();var original=control.poll(node);
        var append=new Requests.PlanAppend(1,List.of("later"),List.of(new Requests.Dependency(0,2)));
        assertEquals(false,control.appendPlan(owner,id,append).get("reused"));
        assertEquals(2L,control.getTask(owner,id).get("planVersion"));
        assertEquals(TextStats.CAPABILITY,control.getTask(owner,id).get("capability"));
        assertEquals(1L,control.poll(node).get("planVersion"));
        assertEquals(original.get("attemptId"),control.poll(node).get("attemptId"));
        assertEquals("WAITING_DEPENDENCY",states(id).get(2));
        assertEquals(true,control.appendPlan(owner,id,append).get("reused"));
        control.result(node,(String)original.get("attemptId"),result(original));
        for(int i=0;i<2;i++) {
            assertEquals(1,control.tick());var work=control.poll(node);
            assertEquals("later".equals(work.get("input"))?2L:1L,work.get("planVersion"));
            control.result(node,(String)work.get("attemptId"),result(work));
        }
        assertEquals("SUCCEEDED",control.getTask(owner,id).get("state"));
        assertEquals(true,control.appendPlan(owner,id,append).get("reused"));
        assertEquals(2,control.plans(owner,id).size());
        assertEquals("TASK_NOT_RUNNING",assertThrows(ApiException.class,()->control.appendPlan(owner,id,new Requests.PlanAppend(2,List.of("extra"),List.of()))).code);
        http.perform(get("/api/v1/tasks/"+id+"/plans").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[1].version").value(2));
    }
    @Test void laterPlanCanUseCompletedPredecessorWithoutChangingEarlierVersionReceipt() {
        Identity node=node("sandbox",1);String id=task("root","pending");control.tick();var root=control.poll(node);
        control.result(node,(String)root.get("attemptId"),result(root));
        var second=new Requests.PlanAppend(1,List.of("second-version"),List.of(new Requests.Dependency(0,2)));
        control.appendPlan(owner,id,second);
        assertEquals("READY",states(id).get(2));
        control.appendPlan(owner,id,new Requests.PlanAppend(2,List.of("third-version"),List.of(new Requests.Dependency(2,3))));
        assertEquals("WAITING_DEPENDENCY",states(id).get(3));
        var retried=control.appendPlan(owner,id,second);
        assertEquals(2,retried.get("planVersion"));assertEquals(true,retried.get("reused"));
        assertEquals(3L,control.getTask(owner,id).get("planVersion"));
        assertEquals(List.of(1,1,2,3),db.queryForList("select plan_version from work_units where task_id=? order by ordinal",Integer.class,id));
        assertEquals(4,states(id).size());
    }
    @Test void planVersionRaceHasOneWinnerAndNoPartialLoser() throws Exception {
        String id=task("root");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<String>>();
            for(String input:List.of("one","two"))futures.add(pool.submit(()->{
                gate.await();
                try {control.appendPlan(owner,id,new Requests.PlanAppend(1,List.of(input),List.of(new Requests.Dependency(0,1))));return "OK";}
                catch(ApiException e){return e.code;}
            }));
            gate.countDown();var outcomes=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
            assertEquals(Set.of("OK","PLAN_VERSION_CONFLICT"),new HashSet<>(outcomes));
            assertEquals(2,states(id).size());assertEquals(2,control.plans(owner,id).size());
            assertEquals(1,db.queryForObject("select count(*) from task_dependencies where task_id=?",Integer.class,id));
        } finally {pool.shutdownNow();}
    }
    @Test void appendedPlanRejectsOldEdgesCyclesQuotaAndUnauthorizedChanges() throws Exception {
        String id=task("root");
        assertEquals("EXISTING_PLAN_IMMUTABLE",assertThrows(ApiException.class,()->control.appendPlan(owner,id,
            new Requests.PlanAppend(1,List.of("new"),List.of(new Requests.Dependency(1,0))))).code);
        assertEquals("INVALID_DEPENDENCY_GRAPH",assertThrows(ApiException.class,()->control.appendPlan(owner,id,
            new Requests.PlanAppend(1,List.of("a","b"),List.of(new Requests.Dependency(1,2),new Requests.Dependency(2,1))))).code);
        var append=new Requests.PlanAppend(1,List.of("new"),List.of(new Requests.Dependency(0,1)));
        assertThrows(ApiException.class,()->control.appendPlan(other,id,append));
        assertThrows(ApiException.class,()->control.appendPlan(admin,id,append));
        assertThrows(ApiException.class,()->control.plans(other,id));
        control.setAdmissionPolicy(admin,1);
        assertEquals("QUEUE_CAPACITY_EXCEEDED",assertThrows(ApiException.class,()->control.appendPlan(owner,id,append)).code);
        assertEquals(1,states(id).size());assertEquals(1,control.plans(owner,id).size());
        control.setAdmissionPolicy(admin,2);
        http.perform(post("/api/v1/tasks/"+id+"/plans").header("Authorization","Bearer owner-secret")
            .contentType("application/json").content(json.write(append))).andExpect(status().isOk()).andExpect(jsonPath("$.planVersion").value(2));
        auth.disable(admin,owner.id());assertThrows(ApiException.class,()->control.appendPlan(owner,id,append));
    }
    String modelDag(List<String> inputs,List<Requests.Dependency> edges) {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("model-flow","1.0.0","model.read.v1")).get("agentId");
        return (String)control.createTask(owner,"model-flow",new Requests.Task(agent,"sandbox",inputs,List.of(),edges)).get("taskId");
    }
    Requests.Dependency outputEdge(int from,int to) {return new Requests.Dependency(from,to,Requests.DependencyCompletion.OUTPUT_AVAILABLE);}
    @Test void modelDependenciesTransferFixedOriginalOutputAndKeepUnreviewedStatus() throws Exception {
        String id=modelDag(List.of("draft","summarize"),List.of(outputEdge(0,1)));Identity node=modelNode();
        assertEquals(1,control.tick());var first=control.poll(node);
        assertEquals(0,first.get("dependencyCount"));
        var output=models.output(node,(String)first.get("attemptId"),new Requests.ModelResult(1,"original output"));
        String artifact=(String)output.get("artifactId");
        artifacts.update(owner,artifact,new ArtifactService.Revision(1,Base64.getEncoder().encodeToString("replacement".getBytes())));
        assertEquals("RUNNING",control.getTask(owner,id).get("state"));assertEquals(1,control.tick());var second=control.poll(node);
        assertEquals(1,second.get("dependencyCount"));String attempt=(String)second.get("attemptId");
        var inputs=models.inputs(node,attempt,1);var item=(Map<?,?>)((List<?>)inputs.get("items")).get(0);
        assertEquals(1,item.get("version"));assertEquals(false,item.get("businessVerified"));
        assertEquals("original output",new String(Base64.getDecoder().decode((String)item.get("contentBase64")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(AuthService.hash("original output"),item.get("sha256"));
        assertThrows(ApiException.class,()->models.inputs(modelNode(),attempt,1));
        models.output(node,attempt,new Requests.ModelResult(1,"summary"));
        assertEquals("OUTPUT_READY",control.getTask(owner,id).get("state"));
        assertEquals(List.of("GENERATED","GENERATED"),states(id));
    }
    @Test void deletedDependencyBlocksReadAndDerivedResultCommit() {
        String id=modelDag(List.of("draft","summarize"),List.of(outputEdge(0,1)));Identity node=modelNode();control.tick();var first=control.poll(node);
        String artifact=(String)models.output(node,(String)first.get("attemptId"),new Requests.ModelResult(1,"draft")).get("artifactId");
        control.tick();var second=control.poll(node);String attempt=(String)second.get("attemptId");
        models.inputs(node,attempt,1);artifacts.delete(owner,artifact);
        assertThrows(ApiException.class,()->models.inputs(node,attempt,1));
        assertThrows(ApiException.class,()->models.output(node,attempt,new Requests.ModelResult(1,"must not commit")));
        assertEquals(1,db.queryForObject("select count(*) from unit_results",Integer.class));
        assertEquals("RUNNING",control.getTask(owner,id).get("state"));
    }
    @Test void modelDependencyBudgetAndConditionAreEnforced() {
        String id=modelDag(List.of("one","two","join"),List.of(outputEdge(0,2),outputEdge(1,2)));Identity node=modelNode();
        for(int i=0;i<2;i++){control.tick();var work=control.poll(node);models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(1,"界".repeat(50000)));}
        control.tick();var join=control.poll(node);
        assertEquals("DEPENDENCY_INPUT_TOO_LARGE",assertThrows(ApiException.class,()->models.inputs(node,(String)join.get("attemptId"),1)).code);
        assertEquals("UNSUPPORTED_DEPENDENCY_CAPABILITY",assertThrows(ApiException.class,()->control.createTask(owner,"wrong-condition",
            new Requests.Task(agentId,"sandbox",List.of("a","b"),List.of(),List.of(outputEdge(0,1))))).code);
        assertEquals("RUNNING",control.getTask(owner,id).get("state"));
    }
    @Test void historicalPlanPagesExcludeLaterAppendsAndLiveState() throws Exception {
        String id=dag("history",List.of("first","second"),List.of(new Requests.Dependency(0,1)));
        var before=control.planSnapshot(owner,id,1,-1,5);
        control.appendPlan(owner,id,new Requests.PlanAppend(1,List.of("third","fourth"),List.of(new Requests.Dependency(1,2),new Requests.Dependency(2,3))));
        assertEquals(before,control.planSnapshot(owner,id,1,-1,5));
        var page=control.planSnapshot(owner,id,2,-1,1);
        assertEquals(4,page.get("totalSteps"));assertEquals(true,page.get("hasMore"));assertEquals(0,page.get("nextAfter"));
        var next=control.planSnapshot(owner,id,2,1,1);var item=(Map<?,?>)((List<?>)next.get("items")).get(0);
        assertEquals("third",item.get("input"));assertEquals(2,item.get("createdInVersion"));assertEquals(AuthService.hash("third"),item.get("inputSha256"));
        assertFalse(item.containsKey("state"));assertEquals(List.of(Map.of("predecessor",1,"successor",2,"completion","SUCCEEDED")),next.get("dependencies"));
        control.cancel(owner,id);assertEquals(before,control.planSnapshot(owner,id,1,-1,5));
        assertEquals(false,control.planSnapshot(owner,id,2,3,5).get("hasMore"));
        http.perform(get("/api/v1/tasks/"+id+"/plans/2?after=1&limit=1").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].input").value("third")).andExpect(jsonPath("$.scope").value("INPUTS_AND_DEPENDENCIES"));
    }
    @Test void planSnapshotIsOwnerOnlyBoundedAndRevocationCheckedOnEveryPage() throws Exception {
        String text="\u0001".repeat(10000);String id=task(text,text,text,text,text,text);
        var snapshot=control.planSnapshot(owner,id,1,-1,5);
        assertTrue(json.write(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<1048576);
        assertEquals(5,((List<?>)snapshot.get("items")).size());assertEquals(true,snapshot.get("hasMore"));
        assertThrows(ApiException.class,()->control.planSnapshot(other,id,1,-1,5));
        assertThrows(ApiException.class,()->control.planSnapshot(admin,id,1,-1,5));
        assertThrows(ApiException.class,()->control.planSnapshot(node("sandbox",1),id,1,-1,5));
        assertEquals(404,assertThrows(ApiException.class,()->control.planSnapshot(owner,id,2,-1,5)).status);
        for(int[] args:List.of(new int[]{0,-1,5},new int[]{1,-2,5},new int[]{1,-1,6},new int[]{1,100,1}))
            assertEquals(400,assertThrows(ApiException.class,()->control.planSnapshot(owner,id,args[0],args[1],args[2])).status);
        auth.disable(admin,owner.id());assertThrows(ApiException.class,()->control.planSnapshot(owner,id,1,4,5));
    }
    @Test void taskPauseYieldsUnstartedReservationsAndDoesNotSpendRetries() throws Exception {
        Identity node=node("sandbox",2);String id=task("first","second");assertEquals(2,control.tick());
        var message=control.messages(node,1).get(0);String messageId=(String)message.get("messageId");
        control.taskAdmission(owner,id,true);control.taskAdmission(owner,id,true);
        assertEquals(List.of("READY","READY"),states(id));
        assertEquals(0,db.queryForObject("select sum(attempts) from work_units where task_id=?",Integer.class,id));
        assertEquals(2,db.queryForObject("select count(*) from attempts where state='YIELDED'",Integer.class));
        assertEquals(0,control.tick());assertEquals("NO_WORK",control.poll(node).get("status"));
        assertThrows(ApiException.class,()->control.acknowledge(node,messageId));
        assertEquals("PAUSED",control.getTask(owner,id).get("pauseStatus"));
        assertEquals(1,control.events(owner,id,0).stream().filter(e->"TASK_DISPATCH_PAUSED".equals(e.get("event_type"))).count());
        http.perform(put("/api/v1/tasks/"+id+"/admission").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"paused\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dispatchPaused").value(false));
        assertEquals(2,control.tick());var work=control.poll(node);assertEquals(2L,work.get("epoch"));
    }
    @Test void taskPauseAllowsCurrentResultButHoldsDependencyAndNewPlanUntilResume() {
        Identity node=node("sandbox",1);String id=dag("pause-flow",List.of("first","second"),List.of(new Requests.Dependency(0,1)));
        control.tick();var work=control.poll(node);control.taskAdmission(owner,id,true);
        assertEquals("DRAINING",control.getTask(owner,id).get("pauseStatus"));
        control.renew(node,(String)work.get("attemptId"),1);control.result(node,(String)work.get("attemptId"),result(work));
        assertEquals("PAUSED",control.getTask(owner,id).get("pauseStatus"));assertEquals(0,control.tick());
        control.appendPlan(owner,id,new Requests.PlanAppend(1,List.of("third"),List.of(new Requests.Dependency(1,2))));assertEquals(0,control.tick());
        control.taskAdmission(owner,id,false);assertEquals(1,control.tick());
        var second=control.poll(node);control.result(node,(String)second.get("attemptId"),result(second));
        assertEquals(1,control.tick());var third=control.poll(node);control.result(node,(String)third.get("attemptId"),result(third));
        assertEquals("SUCCEEDED",control.getTask(owner,id).get("state"));
    }
    @Test void pausedRunningTimeoutRetainsRetryUntilResumeAndOwnerRightsAreRequired() throws Exception {
        Identity node=node("sandbox",1);String id=task("first");control.tick();var work=control.poll(node);
        assertThrows(ApiException.class,()->control.taskAdmission(other,id,true));assertThrows(ApiException.class,()->control.taskAdmission(admin,id,true));
        db.update("update principals set roles=? where id=?",json.write(Set.of("VIEWER")),owner.id());
        assertThrows(ApiException.class,()->control.taskAdmission(owner,id,true));
        db.update("update principals set roles=? where id=?",json.write(owner.roles()),owner.id());
        control.taskAdmission(owner,id,true);time.advance(90001);control.heartbeat(node,true);assertEquals(0,control.tick());
        assertEquals("PAUSED",control.getTask(owner,id).get("pauseStatus"));
        assertThrows(ApiException.class,()->control.result(node,(String)work.get("attemptId"),result(work)));
        control.taskAdmission(owner,id,false);assertEquals(1,control.tick());assertEquals(2L,control.poll(node).get("epoch"));
        control.taskAdmission(owner,id,true);auth.disable(admin,owner.id());assertThrows(ApiException.class,()->control.taskAdmission(owner,id,false));
        control.tick();assertEquals("BLOCKED_AUTH",db.queryForObject("select state from tasks where id=?",String.class,id));
    }
    String dag(String key,List<String> inputs,List<Requests.Dependency> edges) {
        return (String)control.createTask(owner,key,new Requests.Task(agentId,"sandbox",inputs,List.of(),edges)).get("taskId");
    }
    List<String> states(String task) {return db.queryForList("select state from work_units where task_id=? order by ordinal",String.class,task);}
    @Test void dependencyDiamondDispatchesOnlyAfterEveryVerifiedPredecessor() {
        Identity node=node("sandbox",4);
        String id=dag("diamond",List.of("root","left","right","join"),List.of(new Requests.Dependency(0,1),new Requests.Dependency(0,2),new Requests.Dependency(1,3),new Requests.Dependency(2,3)));
        assertEquals(List.of("READY","WAITING_DEPENDENCY","WAITING_DEPENDENCY","WAITING_DEPENDENCY"),states(id));
        assertEquals(1,control.tick());var root=control.poll(node);control.result(node,(String)root.get("attemptId"),result(root));
        assertEquals(2,control.tick());var left=control.poll(node);
        control.result(node,(String)left.get("attemptId"),result(left));assertEquals(0,control.tick());
        assertEquals("WAITING_DEPENDENCY",states(id).get(3));
        var right=control.poll(node);control.result(node,(String)right.get("attemptId"),result(right));assertEquals(1,control.tick());
        var join=control.poll(node);assertEquals("join",join.get("input"));control.result(node,(String)join.get("attemptId"),result(join));
        assertEquals("SUCCEEDED",control.getTask(owner,id).get("state"));
        assertEquals(4,db.queryForObject("select count(*) from attempts",Integer.class));
        assertEquals(4,((List<?>)control.getTask(owner,id).get("dependencies")).size());
        assertThrows(ApiException.class,()->control.getTask(other,id));
    }
    @Test void dependencyFailuresPropagateButRetriesDoNotReleaseSuccessors() {
        Identity node=node("sandbox",1);
        var inputs=new ArrayList<String>();var edges=new ArrayList<Requests.Dependency>();
        for(int i=0;i<100;i++){inputs.add("step"+i);if(i>0)edges.add(new Requests.Dependency(i-1,i));}
        String id=dag("long-chain",inputs,edges);
        assertEquals(1,control.tick());
        for(int attempt=1;attempt<=3;attempt++) {
            var work=control.poll(node);assertEquals("step0",work.get("input"));
            time.advance(90001);control.heartbeat(node,true);
            assertEquals(attempt<3?1:0,control.tick());
            assertEquals(attempt<3?"WAITING_DEPENDENCY":"BLOCKED_DEPENDENCY",states(id).get(99));
        }
        assertEquals("FAILED",control.getTask(owner,id).get("state"));
        assertEquals(3,db.queryForObject("select count(*) from attempts",Integer.class));
        assertEquals(99,db.queryForObject("select count(*) from work_units where state='BLOCKED_DEPENDENCY'",Integer.class));
    }
    @Test void dependencyGraphsRejectCyclesAndCanonicalizeIdempotency() throws Exception {
        var inputs=List.of("a","b","c");
        for(var edges:List.of(List.of(new Requests.Dependency(0,0)),List.of(new Requests.Dependency(0,3)),
                List.of(new Requests.Dependency(0,1),new Requests.Dependency(1,0)),
                List.of(new Requests.Dependency(0,1),new Requests.Dependency(0,1)))) {
            assertEquals("INVALID_DEPENDENCY_GRAPH",assertThrows(ApiException.class,()->dag("invalid",inputs,edges)).code);
        }
        assertEquals(0,db.queryForObject("select count(*) from tasks",Integer.class));
        var edges=List.of(new Requests.Dependency(0,1),new Requests.Dependency(1,2));String id=dag("same",inputs,edges);
        assertEquals(id,dag("same",inputs,List.of(edges.get(1),edges.get(0))));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,()->dag("same",inputs,List.of(edges.get(0)))).code);
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").header("Idempotency-Key","http")
            .contentType("application/json").content(json.write(Map.of("agentId",agentId,"zone","sandbox","inputs",inputs,"dependencies",List.of(Map.of("successor",1))))))
            .andExpect(status().isBadRequest());
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").header("Idempotency-Key","http")
            .contentType("application/json").content(json.write(new Requests.Task(agentId,"sandbox",inputs,List.of(),edges))))
            .andExpect(status().isOk());
        String model=(String)control.publishAgent(admin,new Requests.Agent("model-dag","1.0.0","model.read.v1")).get("agentId");
        assertEquals("UNSUPPORTED_DEPENDENCY_CAPABILITY",assertThrows(ApiException.class,()->control.createTask(owner,"model-dag",
            new Requests.Task(model,"sandbox",inputs,List.of(),edges))).code);
    }
    @Test void waitingDependenciesConsumeBacklogAndCancellationAndRevocationFenceThem() {
        control.setAdmissionPolicy(admin,3);var edges=List.of(new Requests.Dependency(0,1),new Requests.Dependency(1,2));
        String id=dag("quota",List.of("a","b","c"),edges);
        assertEquals("QUEUE_CAPACITY_EXCEEDED",assertThrows(ApiException.class,()->task("extra")).code);
        control.cancel(owner,id);assertEquals(List.of("CANCELLED","CANCELLED","CANCELLED"),states(id));
        String revoked=dag("revoked",List.of("a","b","c"),edges);
        auth.disable(admin,owner.id());control.tick();
        assertEquals(List.of("BLOCKED_AUTH","BLOCKED_AUTH","BLOCKED_AUTH"),states(revoked));
        assertEquals(0,db.queryForObject("select count(*) from attempts",Integer.class));
    }
    @Test void completesIndependentShardsWithVerifiedResultsAndAudit() {
        Identity node=node("sandbox",2);String task=task("你好\nworld","a😀b");assertEquals(2,control.tick());
        for(int i=0;i<2;i++){var work=control.poll(node);control.result(node,(String)work.get("attemptId"),result(work));}
        assertEquals("SUCCEEDED",control.getTask(owner,task).get("state"));
        assertEquals(2,db.queryForObject("select count(*) from unit_results",Integer.class));
        assertTrue(control.events(owner,task,0).stream().anyMatch(e->e.get("event_type").equals("RESULT_ACCEPTED")));
        assertFalse(json.write(control.events(owner,task,0)).contains("world"));
    }
    @Test void idempotencyRejectsChangedPayloadAndReusesOriginalTask() {
        var req=new Requests.Task(agentId,"sandbox",List.of("one"));
        var first=control.createTask(owner,"key",req);var second=control.createTask(owner,"key",req);
        assertEquals(first.get("taskId"),second.get("taskId"));assertEquals(true,second.get("reused"));
        var ex=assertThrows(ApiException.class,()->control.createTask(owner,"key",new Requests.Task(agentId,"sandbox",List.of("two"))));
        assertEquals("IDEMPOTENCY_CONFLICT",ex.code);
    }
    @Test void aNodeFromAnotherZoneNeverReceivesData() {
        Identity wrong=node("other",1);task("confidential");assertEquals(0,control.tick());
        assertEquals("NO_WORK",control.poll(wrong).get("status"));
    }
    @Test void otherUsersAndAdminsHaveNoImplicitBusinessReadAccess() {
        String id=task("private");
        assertEquals(404,assertThrows(ApiException.class,()->control.getTask(other,id)).status);
        assertEquals(404,assertThrows(ApiException.class,()->control.getTask(admin,id)).status);
        assertEquals(404,assertThrows(ApiException.class,()->control.events(other,id,0)).status);
    }
    @Test void foreignTenantCannotUseAgentOrReadTask() {
        auth.create("foreign","tenant-b","USER","Foreign",Set.of("OPERATOR","VIEWER"),Set.of("sandbox"),"foreign-secret");
        Identity foreign=auth.authenticate("foreign-secret");String id=task("private");
        assertThrows(ApiException.class,()->control.getTask(foreign,id));
        assertThrows(ApiException.class,()->control.createTask(foreign,"key",new Requests.Task(agentId,"sandbox",List.of("x"))));
    }
    @Test void permissionRevocationStopsRunningResultAndRenewal() {
        Identity node=node("sandbox",1);String id=task("x");control.tick();var work=control.poll(node);
        auth.disable(admin,owner.id());
        assertEquals(403,assertThrows(ApiException.class,()->control.result(node,(String)work.get("attemptId"),result(work))).status);
        assertThrows(ApiException.class,()->control.renew(node,(String)work.get("attemptId"),((Number)work.get("epoch")).longValue()));
        control.tick();assertEquals("BLOCKED_AUTH",db.queryForObject("select state from tasks where id=?",String.class,id));
        assertEquals(0,db.queryForObject("select count(*) from unit_results",Integer.class));
    }
    @Test void permissionRevocationWhileQueuedPreventsDispatch() {
        node("sandbox",1);task("x");auth.disable(admin,owner.id());assertEquals(0,control.tick());
        assertEquals(0,db.queryForObject("select count(*) from attempts",Integer.class));
    }
    @Test void expiredAttemptCannotOverwriteNewAttempt() {
        Identity node=node("sandbox",1);task("x");control.tick();var old=control.poll(node);
        time.advance(90_001);control.heartbeat(node,true);assertEquals(1,control.tick());var fresh=control.poll(node);
        assertTrue(((Number)fresh.get("epoch")).longValue()>((Number)old.get("epoch")).longValue());
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.result(node,(String)old.get("attemptId"),result(old))).code);
        control.result(node,(String)fresh.get("attemptId"),result(fresh));
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.result(node,(String)old.get("attemptId"),result(old))).code);
    }
    @Test void duplicateResultIsIdempotentButChangedResultIsRejected() {
        Identity node=node("sandbox",1);task("x");control.tick();var work=control.poll(node);String id=(String)work.get("attemptId");
        assertEquals(false,control.result(node,id,result(work)).get("duplicate"));
        assertEquals(true,control.result(node,id,result(work)).get("duplicate"));
        assertThrows(ApiException.class,()->control.result(node,id,new Requests.Result(1,AuthService.hash("bad"),0,0)));
        assertEquals(1,db.queryForObject("select count(*) from unit_results",Integer.class));
    }
    @Test void failedVerificationDoesNotReportSuccess() {
        Identity node=node("sandbox",1);String id=task("x");control.tick();var work=control.poll(node);
        var bad=new Requests.Result(1,AuthService.hash("wrong"),1,1);
        assertEquals(422,assertThrows(ApiException.class,()->control.result(node,(String)work.get("attemptId"),bad)).status);
        assertEquals("RUNNING",control.getTask(owner,id).get("state"));
    }
    @Test void cancelFencesRunningWork() {
        Identity node=node("sandbox",1);String id=task("x");control.tick();var work=control.poll(node);control.cancel(owner,id);
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.result(node,(String)work.get("attemptId"),result(work))).code);
        assertEquals("CANCELLED",control.getTask(owner,id).get("state"));
    }
    @Test void lostPollResponseRedeliversSameAttempt() {
        Identity node=node("sandbox",1);task("x");control.tick();
        assertEquals(control.poll(node).get("attemptId"),control.poll(node).get("attemptId"));
    }
    @Test void agentQuotaIsSharedAcrossTasksAndConcurrentSchedulers() throws Exception {
        node("sandbox",8);task("one","two","three");task("four","five");
        control.setAgentSchedulingPolicy(admin,agentId,"sandbox",2);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var futures=executor.invokeAll(List.<Callable<Integer>>of(()->control.tick(),()->control.tick()));
            assertEquals(2,futures.get(0).get()+futures.get(1).get());
        } finally {executor.shutdownNow();}
        assertEquals(2L,control.agentSchedulingPolicy(admin,agentId,"sandbox").get("occupied"));
        String second=(String)control.publishAgent(admin,new Requests.Agent("second","1.0.0")).get("agentId");
        control.createTask(owner,"second",new Requests.Task(second,"sandbox",List.of("other capability")));
        assertEquals(1,control.tick());
        assertEquals(false,control.agentSchedulingPolicy(admin,second,"sandbox").get("configured"));
        assertEquals(3,db.queryForObject("select count(*) from attempts where state='RESERVED'",Integer.class));
    }
    @Test void loweringQuotaDrainsNaturallyAndZonesAndTenantLimitRemainIndependent() {
        Identity node=node("sandbox",8);task("one","two","three","four");
        control.setAgentSchedulingPolicy(admin,agentId,"sandbox",2);assertEquals(2,control.tick());
        var first=control.poll(node);
        control.setAgentSchedulingPolicy(admin,agentId,"sandbox",1);time.advance(1001);assertEquals(0,control.tick());
        control.result(node,(String)first.get("attemptId"),result(first));assertEquals(0,control.tick());
        var second=control.poll(node);control.result(node,(String)second.get("attemptId"),result(second));
        time.advance(1001);assertEquals(1,control.tick());
        node("other",8);
        control.createTask(admin,"other-zone",new Requests.Task(agentId,"other",List.of("a","b")));
        assertEquals(2,control.tick());
        assertEquals(false,control.agentSchedulingPolicy(admin,agentId,"other").get("configured"));
        control.setSchedulingPolicy(admin,3);
        control.setAgentSchedulingPolicy(admin,agentId,"sandbox",null);time.advance(1001);assertEquals(0,control.tick());
        assertEquals(3,control.agentSchedulingPolicy(admin,agentId,"sandbox").get("effectiveLimit"));
        control.setSchedulingPolicy(admin,4);time.advance(1001);assertEquals(1,control.tick());
    }
    @Test void agentQuotaApiEnforcesCurrentZoneTenantAndStrictBounds() throws Exception {
        String url="/api/v1/agent-definitions/"+agentId+"/scheduling-policy?zone=sandbox";
        http.perform(put(url).header("Authorization","Bearer admin-secret").contentType("application/json").content("{\"maxConcurrency\":2}")).andExpect(status().isNoContent());
        http.perform(get(url).header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.maxConcurrency").value(2));
        for(String body:List.of("{}","{\"maxConcurrency\":0}","{\"maxConcurrency\":10001}","{\"maxConcurrency\":2,\"tenant\":\"bad\"}"))http.perform(put(url).header("Authorization","Bearer admin-secret").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(delete(url).header("Authorization","Bearer owner-secret")).andExpect(status().isForbidden());
        assertThrows(ApiException.class,()->control.agentSchedulingPolicy(owner,agentId,"sandbox"));
        auth.create("foreign","tenant-b","USER","B",Set.of("ADMIN"),Set.of("sandbox"),"foreign-secret");
        assertEquals(404,assertThrows(ApiException.class,()->control.setAgentSchedulingPolicy(auth.authenticate("foreign-secret"),agentId,"sandbox",2)).status);
        db.update("update principals set zones=? where id=?",json.write(Set.of("other")),admin.id());
        assertThrows(ApiException.class,()->control.setAgentSchedulingPolicy(admin,agentId,"sandbox",null));
        db.update("update principals set zones=? where id=?",json.write(Set.of("sandbox")),admin.id());
        http.perform(delete(url).header("Authorization","Bearer admin-secret")).andExpect(status().isNoContent());
        assertEquals(false,control.agentSchedulingPolicy(admin,agentId,"sandbox").get("configured"));
        db.update("update principals set enabled=false where id=?",admin.id());
        assertThrows(ApiException.class,()->control.setAgentSchedulingPolicy(admin,agentId,"sandbox",2));
    }
    @Test void concurrentSchedulersDoNotOverbookCapacity() throws Exception {
        node("sandbox",1);task("one","two","three");
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var futures=executor.invokeAll(List.<Callable<Integer>>of(()->control.tick(),()->control.tick()));
            assertEquals(1,futures.get(0).get()+futures.get(1).get());
            assertEquals(1,db.queryForObject("select count(*) from attempts where state='RESERVED'",Integer.class));
        } finally {executor.shutdownNow();}
    }
    @Test void concurrentDuplicateResultsAcceptOnlyOneRow() throws Exception {
        Identity node=node("sandbox",1);task("one");control.tick();var work=control.poll(node);String id=(String)work.get("attemptId");
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            var futures=executor.invokeAll(List.<Callable<Map<String,Object>>>of(()->control.result(node,id,result(work)),()->control.result(node,id,result(work))));
            for(var future:futures) assertEquals(true,future.get().get("accepted"));
            assertEquals(1,db.queryForObject("select count(*) from unit_results",Integer.class));
        } finally {executor.shutdownNow();}
    }
    @Test void adminPauseSurvivesHeartbeatsAndLetsRunningWorkFinish() {
        Identity node=node("sandbox",2);String task=task("running","reserved");assertEquals(2,control.tick());
        var message=control.messages(node,1).get(0);String messageId=(String)message.get("messageId");
        var running=control.acknowledge(node,messageId);String attempt=(String)running.get("attemptId");
        control.nodeAdmission(admin,node.id(),true);control.heartbeat(node,true);
        assertEquals(0,control.tick());
        assertEquals(attempt,control.acknowledge(node,messageId).get("attemptId"));
        assertEquals(attempt,control.poll(node).get("attemptId"));
        assertNotNull(control.renew(node,attempt,((Number)running.get("epoch")).longValue()));
        var row=(Map<?,?>)((List<?>)control.listNodes(admin,"",50).get("items")).get(0);
        assertEquals("ADMIN_PAUSED",row.get("availability"));assertEquals(true,row.get("admin_paused"));
        assertEquals(0L,row.get("availableSlots"));assertEquals(1L,row.get("occupiedSlots"));
        assertTrue(json.write(control.schedulingMetrics(admin)).contains("\"ready_slots\":0"));
        control.result(node,attempt,result(running));
        assertEquals("COMPLETED",control.acknowledge(node,messageId).get("status"));
        assertEquals("NO_WORK",control.poll(node).get("status"));assertTrue(control.messages(node).isEmpty());
        control.heartbeat(node,false,Requests.AdmissionReason.MEMORY_LOW);
        control.nodeAdmission(admin,node.id(),false);time.advance(1001);assertEquals(0,control.tick());
        control.heartbeat(node,true);time.advance(1001);assertEquals(1,control.tick());
        var remaining=control.poll(node);control.result(node,(String)remaining.get("attemptId"),result(remaining));
        assertEquals("SUCCEEDED",control.getTask(owner,task).get("state"));
    }
    @Test void maintenanceYieldDoesNotSpendRetryBudgetAndOldMessagesAreFenced() {
        Identity node=node("sandbox",1);task("pending");
        for(int i=0;i<5;i++) {
            time.advance(1001);assertEquals(1,control.tick());var message=control.messages(node).get(0);
            control.nodeAdmission(admin,node.id(),true);control.nodeAdmission(admin,node.id(),true);
            assertThrows(ApiException.class,()->control.acknowledge(node,(String)message.get("messageId")));
            assertEquals(0,db.queryForObject("select attempts from work_units",Integer.class));
            assertEquals("READY",db.queryForObject("select state from work_units",String.class));
            control.nodeAdmission(admin,node.id(),false);
        }
        time.advance(1001);assertEquals(1,control.tick());var work=control.poll(node);
        assertEquals(6L,work.get("epoch"));control.result(node,(String)work.get("attemptId"),result(work));
    }
    @Test void nodeAdmissionRequiresCurrentTenantZoneAdminAndClosedInput() throws Exception {
        Identity node=node("sandbox",1),elsewhere=node("other",1);
        assertThrows(ApiException.class,()->control.nodeAdmission(owner,node.id(),true));
        assertThrows(ApiException.class,()->control.nodeAdmission(node,node.id(),false));
        auth.create("foreign-admin","tenant-b","USER","B",Set.of("ADMIN"),Set.of("sandbox"),"foreign-token");
        assertEquals(404,assertThrows(ApiException.class,()->control.nodeAdmission(auth.authenticate("foreign-token"),node.id(),true)).status);
        db.update("update principals set zones=? where id=?",json.write(Set.of("sandbox")),admin.id());
        assertEquals(403,assertThrows(ApiException.class,()->control.nodeAdmission(admin,elsewhere.id(),true)).status);
        String url="/api/v1/nodes/"+node.id()+"/admission";
        http.perform(put(url).header("Authorization","Bearer admin-secret").contentType("application/json").content("{\"paused\":true}")).andExpect(status().isNoContent());
        for(String body:List.of("{}","{\"paused\":null}","{\"paused\":false,\"tenant\":\"other\"}"))http.perform(put(url).header("Authorization","Bearer admin-secret").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        db.update("update principals set enabled=false where id=?",admin.id());
        assertThrows(ApiException.class,()->control.nodeAdmission(admin,node.id(),false));
        assertTrue(db.queryForObject("select admin_paused from nodes where id=?",Boolean.class,node.id()));
    }
    @Test void drainingNodeReleasesReservationsWithoutUserDesktopActions() {
        Identity node=node("sandbox",1);task("x");control.tick();control.heartbeat(node,false);
        assertEquals("NO_WORK",control.poll(node).get("status"));assertEquals(0,control.tick());
        assertEquals("READY",db.queryForObject("select state from work_units",String.class));
    }
    @Test void missingHeartbeatPreventsNewScheduling() {
        node("sandbox",1);task("x");time.advance(45_001);assertEquals(0,control.tick());
    }
    @Test void nodeCannotReadAnotherNodesAssignedInputOrSubmitItsResult() {
        Identity first=node("sandbox",1);String task=task("x");control.tick();
        Identity second=node("sandbox",1);var work=control.poll(first);
        assertEquals(404,assertThrows(ApiException.class,()->control.result(second,(String)work.get("attemptId"),result(work))).status);
        assertThrows(ApiException.class,()->control.getTask(first,task));
    }
    @Test void tokensAreHashedAndRevokedTokensAreDenied() {
        assertFalse(db.queryForList("select token_hash from api_tokens").toString().contains("owner-secret"));
        auth.disable(admin,owner.id());assertEquals(401,assertThrows(ApiException.class,()->auth.authenticate("owner-secret")).status);
    }
    @Test void databaseResourceFailuresReturnUnavailableWithoutExposingDetails() throws Exception {
        try {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("private database address"))
                .when(db).queryForList(org.mockito.ArgumentMatchers.startsWith("select p.* from principals"),org.mockito.ArgumentMatchers.any(Object[].class));
            http.perform(get("/api/v1/me").header("Authorization","Bearer admin-secret"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"STORAGE_UNAVAILABLE\"}",true));
        } finally {org.mockito.Mockito.reset(db);}
        try {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("private database address"))
                .when(db).queryForList(org.mockito.ArgumentMatchers.startsWith("select n.id,"),org.mockito.ArgumentMatchers.any(Object[].class));
            http.perform(get("/api/v1/nodes").header("Authorization","Bearer admin-secret"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"STORAGE_UNAVAILABLE\"}",true));
        } finally {org.mockito.Mockito.reset(db);}
        http.perform(get("/api/v1/me").header("Authorization","Bearer invalid-token")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/me").header("Authorization","Bearer admin-secret")).andExpect(status().isOk());
    }
    @Test void httpEnforcesIdentityRolesAndInputSchema() throws Exception {
        http.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/health")).andExpect(status().isOk());
        http.perform(post("/api/v1/nodes").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"name\":\"node\",\"zone\":\"sandbox\",\"capacity\":1}")).andExpect(status().isForbidden());
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"agentId\":\"x\",\"zone\":\"sandbox\",\"inputs\":[],\"tenant\":\"forged\"}")).andExpect(status().isBadRequest());
    }
    @Test void httpRoundTripCreatesAndReadsScopedTask() throws Exception {
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").header("Idempotency-Key","http-test").contentType("application/json").content(json.write(new Requests.Task(agentId,"sandbox",List.of("one"))))).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").isString());
        String id=db.queryForObject("select id from tasks",String.class);
        http.perform(get("/api/v1/tasks/"+id).header("Authorization","Bearer owner-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.units[0].state").value("READY"));
        http.perform(get("/api/v1/tasks/"+id).header("Authorization","Bearer other-secret")).andExpect(status().isNotFound());
    }
    @Test void expiredCredentialsCannotAuthenticate() {
        time.advance(86_400_001);
        assertEquals(401,assertThrows(ApiException.class,()->auth.authenticate("owner-secret")).status);
    }
    @Test void tokensCannotCrossUserAndNodeEndpoints() throws Exception {
        var created=control.registerNode(admin,new Requests.Node("node","sandbox",1));
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer "+created.get("token")).header("Idempotency-Key","node-cannot-create").contentType("application/json").content(json.write(new Requests.Task(agentId,"sandbox",List.of("x"))))).andExpect(status().isForbidden());
        http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"accepting\":true}")).andExpect(status().isForbidden());
    }
    @Test void largerRequestsAreRejectedBeforeBinding() throws Exception {
        http.perform(post("/api/v1/tasks").header("Authorization","Bearer owner-secret").contentType("application/json").content("x".repeat(1_048_577))).andExpect(status().isPayloadTooLarge());
    }
    @Test void retryBudgetTerminatesRepeatedNodeFailures() {
        Identity node=node("sandbox",1);String id=task("x");
        for(int i=0;i<3;i++) {
            control.heartbeat(node,true);assertEquals(1,control.tick());control.poll(node);time.advance(90_001);
        }
        control.heartbeat(node,true);assertEquals(0,control.tick());
        assertEquals("FAILED",control.getTask(owner,id).get("state"));
        assertEquals(3,db.queryForObject("select count(*) from attempts",Integer.class));
    }
    @Test void unmatchedQueueHeadDoesNotStarveAnotherZone() {
        node("other",1);
        control.createTask(owner,"blocked-batch",new Requests.Task(agentId,"sandbox",Collections.nCopies(64,"no eligible node")));
        time.advance(1);
        String runnable=(String)control.createTask(admin,"runnable",new Requests.Task(agentId,"other",List.of("eligible"))).get("taskId");
        assertEquals(0,control.tick());
        assertEquals(1,control.tick());
        assertEquals("RESERVED",db.queryForObject("select state from work_units where task_id=?",String.class,runnable));
    }
    @Test void durableDispatchIsCreatedAtomicallyWithReservation() {
        node("sandbox",1);task("x");
        new TransactionTemplate(transactions).executeWithoutResult(status->{control.tickTenant("tenant-a");status.setRollbackOnly();});
        assertEquals(0,db.queryForObject("select count(*) from attempts",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from node_dispatch",Integer.class));
        assertEquals("READY",db.queryForObject("select state from work_units",String.class));
    }
    @Test void lostNotificationIsRedeliveredWithoutStartingTaskOrExposingInput() {
        Identity node=node("sandbox",1);task("secret-text");control.tick();
        var first=control.messages(node);assertEquals(1,first.size());
        assertFalse(json.write(first).contains("secret-text"));
        assertEquals("RESERVED",db.queryForObject("select state from attempts",String.class));
        assertTrue(control.messages(node).isEmpty());
        time.advance(5001);var second=control.messages(node);
        assertEquals(first.get(0).get("messageId"),second.get(0).get("messageId"));
        assertEquals(2L,second.get(0).get("deliveryCount"));
    }
    @Test void lostAckResponseCanBeReplayedWithoutExtendingLease() {
        Identity node=node("sandbox",1);task("x");control.tick();
        String message=(String)control.messages(node).get(0).get("messageId");
        var first=control.acknowledge(node,message);time.advance(1000);var again=control.acknowledge(node,message);
        assertEquals(first.get("attemptId"),again.get("attemptId"));assertEquals(first.get("expiresAt"),again.get("expiresAt"));
        assertEquals(1,db.queryForObject("select count(*) from node_inbox",Integer.class));
        assertTrue(control.messages(node).isEmpty());
        control.result(node,(String)first.get("attemptId"),result(first));
        assertEquals("COMPLETED",control.acknowledge(node,message).get("status"));
    }
    @Test void anotherNodeCannotAcknowledgeMessage() {
        Identity first=node("sandbox",1);task("x");control.tick();
        String message=(String)control.messages(first).get(0).get("messageId");
        Identity second=node("sandbox",1);
        assertEquals(404,assertThrows(ApiException.class,()->control.acknowledge(second,message)).status);
        assertTrue(control.messages(second).isEmpty());
    }
    @Test void queuedNotificationCannotBypassRevocationBeforeAck() {
        Identity node=node("sandbox",1);task("x");control.tick();
        String message=(String)control.messages(node).get(0).get("messageId");auth.disable(admin,owner.id());
        assertEquals(403,assertThrows(ApiException.class,()->control.acknowledge(node,message)).status);
        assertEquals(0,db.queryForObject("select count(*) from node_inbox",Integer.class));
    }
    @Test void cancelledNotificationIsNeverReplayed() {
        Identity node=node("sandbox",1);String id=task("x");control.tick();
        String message=(String)control.messages(node).get(0).get("messageId");control.cancel(owner,id);
        assertTrue(control.messages(node).isEmpty());
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.acknowledge(node,message)).code);
    }
    @Test void expiredNotificationCannotStartNewAttempt() {
        Identity node=node("sandbox",1);task("x");control.tick();
        String message=(String)control.messages(node).get(0).get("messageId");
        time.advance(30001);control.heartbeat(node,true);control.tick();
        assertEquals("STALE_LEASE",assertThrows(ApiException.class,()->control.acknowledge(node,message)).code);
        assertEquals(1,control.messages(node).size());
    }
    @Test void legacyPollAndNewAckShareOneInboxRecord() {
        Identity node=node("sandbox",1);task("x");control.tick();
        String message=(String)control.messages(node).get(0).get("messageId");var work=control.poll(node);
        assertEquals(work.get("attemptId"),control.acknowledge(node,message).get("attemptId"));
        assertEquals(1,db.queryForObject("select count(*) from node_inbox",Integer.class));
    }
    @Test void tenantQuotaLimitsReservationsAndCannotBeRaisedByTaskOwner() {
        node("sandbox",4);node("sandbox",4);task("a","b","c","d");
        assertThrows(ApiException.class,()->control.setSchedulingPolicy(owner,100));
        control.setSchedulingPolicy(admin,2);assertEquals(2,control.tick());assertEquals(0,control.tick());
        assertEquals(2,db.queryForObject("select count(*) from attempts where state='RESERVED'",Integer.class));
    }
    @Test void independentTenantCanDispatchWhileAnotherTenantIsLocked() throws Exception {
        node("sandbox",1);task("a");
        auth.create("b-admin","tenant-b","USER","B",Set.of("ADMIN","OPERATOR"),Set.of("sandbox"),"b-secret");
        Identity b=auth.authenticate("b-secret");
        String agent=(String)control.publishAgent(b,new Requests.Agent("b-worker","1.0.0")).get("agentId");
        var nr=control.registerNode(b,new Requests.Node("b-node","sandbox",1));
        control.heartbeat(auth.authenticate((String)nr.get("token")),true);
        String bTask=(String)control.createTask(b,"b-task",new Requests.Task(agent,"sandbox",List.of("b"))).get("taskId");
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select tenant_id from tenant_guards where tenant_id='tenant-a' for update")) {lock.executeQuery().close();}
            try {assertEquals(1,pool.submit(control::tick).get(3,TimeUnit.SECONDS));}
            finally {connection.rollback();}
        } finally {pool.shutdownNow();}
        assertEquals("RESERVED",db.queryForObject("select state from work_units where task_id=?",String.class,bTask));
        assertEquals(1,db.queryForObject("select count(*) from work_units where state='READY'",Integer.class));
    }
    @Test void candidateOccupancyIsLoadedOncePerZoneAndBalancedAcrossNodes() {
        for(int i=0;i<4;i++)node("sandbox",16);
        control.createTask(owner,"batch",new Requests.Task(agentId,"sandbox",Collections.nCopies(40,"x")));
        org.mockito.Mockito.clearInvocations(db);
        assertEquals(40,control.tick());
        long candidateQueries=org.mockito.Mockito.mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("queryForList") && i.getArguments().length>0 && i.getArgument(0) instanceof String sql && sql.contains("occupancy.busy")).count();
        assertEquals(1,candidateQueries,"one occupancy query per zone, not one per work unit/node");
        var counts=db.queryForList("select count(*) as amount from attempts group by node_id",Integer.class);
        assertEquals(List.of(10,10,10,10),counts);
    }
    @Test void httpMessageProtocolRequiresNodeIdentity() throws Exception {
        http.perform(post("/api/v1/nodes/messages").header("Authorization","Bearer owner-secret").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(put("/api/v1/tenant/scheduling-policy").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"maxConcurrency\":2}")).andExpect(status().isForbidden());
    }
    @Test void deliveryCreditBoundsOutstandingAndAckReleasesCredit() {
        Identity node=node("sandbox",4);task("a","b","c","d");control.tick();
        var first=control.messages(node,1);assertEquals(1,first.size());
        assertTrue(control.messages(node,1).isEmpty());
        control.acknowledge(node,(String)first.get(0).get("messageId"));
        assertEquals(1,control.messages(node,1).size());
        assertEquals(2,db.queryForObject("select count(*) from node_dispatch where state='PENDING'",Integer.class));
    }
    @Test void zeroCreditDoesNotConsumeAndTimedOutDeliveryCanBeReplayed() {
        Identity node=node("sandbox",2);task("a","b");control.tick();
        assertTrue(control.messages(node,0).isEmpty());
        var first=control.messages(node,1);time.advance(5001);
        assertEquals(first.get(0).get("messageId"),control.messages(node,1).get(0).get("messageId"));
        assertEquals(400,assertThrows(ApiException.class,()->control.messages(node,17)).status);
        assertEquals(400,assertThrows(ApiException.class,()->control.messages(node,-1)).status);
    }
    @Test void metricsRequireAdminAndFilterAuthorizedZonesWithoutTaskContent() throws Exception {
        node("sandbox",1);node("other",2);task("sensitive-payload");time.advance(1000);
        auth.create("limited","tenant-a","USER","Limited",Set.of("ADMIN"),Set.of("sandbox"),"limited-secret");
        var metrics=control.schedulingMetrics(auth.authenticate("limited-secret"));
        String encoded=json.write(metrics);
        assertFalse(encoded.contains("sensitive-payload"));assertFalse(encoded.contains("other"));
        assertTrue(encoded.contains("1000"));
        assertEquals(1,((List<?>)metrics.get("zones")).size());
        assertThrows(ApiException.class,()->control.schedulingMetrics(owner));
        auth.create("isolated","tenant-b","USER","Isolated",Set.of("ADMIN"),Set.of("sandbox"),"isolated-secret");
        assertFalse(json.write(control.schedulingMetrics(auth.authenticate("isolated-secret"))).contains("READY"));
        http.perform(get("/api/v1/tenant/scheduling-metrics").header("Authorization","Bearer owner-secret")).andExpect(status().isForbidden());
        http.perform(get("/api/v1/tenant/scheduling-metrics").header("Authorization","Bearer limited-secret")).andExpect(status().isOk());
    }
    @Test void metricsExcludeStaleAndRevokedNodes() {
        Identity n=node("sandbox",2);
        time.advance(45001);
        String stale=json.write(control.schedulingMetrics(admin));assertTrue(stale.contains("\"ready_slots\":0"));
        control.heartbeat(n,true);auth.disable(admin,n.id());
        assertTrue(json.write(control.schedulingMetrics(admin)).contains("\"ready_slots\":0"));
    }

    @Test void admissionRejectsWithoutPartialWriteAndAllowsIdempotentReplay() {
        control.setAdmissionPolicy(admin,2);
        var req=new Requests.Task(agentId,"sandbox",List.of("a","b"));
        var first=control.createTask(owner,"limited",req);
        assertEquals(first.get("taskId"),control.createTask(owner,"limited",req).get("taskId"));
        assertEquals(429,assertThrows(ApiException.class,()->task("c")).status);
        assertEquals(1,db.queryForObject("select count(*) from tasks",Integer.class));
        control.cancel(owner,(String)first.get("taskId"));
        assertNotNull(task("c"));
        assertThrows(ApiException.class,()->control.setAdmissionPolicy(owner,100));
    }
    @Test void concurrentAdmissionCannotExceedTenantBacklog() throws Exception {
        control.setAdmissionPolicy(admin,1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> create=()->{try{task("a");return 200;}catch(ApiException e){return e.status;}};
            var results=pool.invokeAll(List.of(create,create));
            var codes=new ArrayList<Integer>();for(var f:results)codes.add(f.get());Collections.sort(codes);
            assertEquals(List.of(200,429),codes);
        } finally {pool.shutdownNow();}
    }
    @Test void databaseRejectsCrossTenantOwnerAgentAndNode() {
        auth.create("foreign","tenant-b","USER","B",Set.of("ADMIN"),Set.of("sandbox"),"foreign-secret");
        String id=task("x");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update tasks set owner_id='foreign' where id=?",id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update agent_versions set created_by='foreign' where id=?",agentId));
        Identity n=node("sandbox",1);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update nodes set tenant_id='tenant-b' where id=?",n.id()));
    }

    @Test void rotatingCredentialRevokesOldTokenWithoutChangingTaskDelegation() {
        String id=task("x");
        var rotated=auth.rotate(owner,"owner-secret");String token=(String)rotated.get("token");
        assertEquals(401,assertThrows(ApiException.class,()->auth.authenticate("owner-secret")).status);
        assertEquals(owner.authVersion(),auth.authenticate(token).authVersion());
        assertEquals("RUNNING",control.getTask(auth.authenticate(token),id).get("state"));
        assertFalse(json.write(db.queryForList("select payload_json from events")).contains(token));
        assertEquals(0,db.queryForObject("select count(*) from api_tokens where token_hash=?",Integer.class,token));
        auth.logout(auth.authenticate(token),token);
        assertThrows(ApiException.class,()->auth.authenticate(token));
    }
    @Test void concurrentRotationCreatesOnlyOneReplacement() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> rotate=()->{try{auth.rotate(owner,"owner-secret");return 200;}catch(ApiException e){return e.status;}};
            var codes=new ArrayList<Integer>();for(var f:pool.invokeAll(List.of(rotate,rotate)))codes.add(f.get());Collections.sort(codes);
            assertEquals(List.of(200,401),codes);
            assertEquals(1,db.queryForObject("select count(*) from api_tokens where principal_id='owner' and revoked=false",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void credentialLifecycleCannotTargetAnotherPrincipalAndHttpLogoutWorks() throws Exception {
        assertThrows(ApiException.class,()->auth.rotate(owner,"admin-secret"));
        assertThrows(ApiException.class,()->auth.logout(owner,"admin-secret"));
        assertNotNull(auth.authenticate("admin-secret"));
        http.perform(post("/api/v1/auth/logout").header("Authorization","Bearer owner-secret")).andExpect(status().isNoContent());
        http.perform(get("/api/v1/me").header("Authorization","Bearer owner-secret")).andExpect(status().isUnauthorized());
    }

    @Test void verifiedSlotReleaseWakesDeferredWorkWithoutClockAdvance() {
        Identity n=node("sandbox",1);task("a","b");control.tick();
        var first=control.poll(n);
        assertEquals(0,control.tick());
        control.result(n,(String)first.get("attemptId"),result(first));
        assertEquals(1,control.tick(),"slot release must not wait for one second polling backoff");
        var second=control.poll(n);assertNotEquals(first.get("attemptId"),second.get("attemptId"));
    }

    @Test void viewerCanReadOwnTaskArtifactsButCannotAttach() throws Exception {
        String task=(String)control.createTask(owner,"viewer-artifacts",new Requests.Task(agentId,"sandbox",List.of("read-only"))).get("taskId");
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("result.txt","sandbox","eA==")).get("artifactId");
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));
        db.update("update principals set roles=? where id=?",json.write(Set.of("VIEWER")),owner.id());
        http.perform(get("/api/v1/tasks/"+task+"/artifacts").header("Authorization","Bearer owner-secret")).andExpect(status().isOk()).andExpect(jsonPath("$[0].artifact_id").value(artifact));
        http.perform(get("/api/v1/artifacts/"+artifact+"?version=1").header("Authorization","Bearer owner-secret")).andExpect(status().isOk());
        http.perform(post("/api/v1/tasks/"+task+"/artifacts").header("Authorization","Bearer owner-secret").contentType("application/json").content(json.write(Map.of("artifactId",artifact,"version",1)))).andExpect(status().isForbidden());
        assertThrows(ApiException.class,()->artifacts.taskArtifacts(other,task));
        db.update("update principals set zones='[]' where id=?",owner.id());
        http.perform(get("/api/v1/tasks/"+task+"/artifacts").header("Authorization","Bearer owner-secret")).andExpect(status().isForbidden());
    }
    @Test void artifactVersionsAreImmutableAndConflictsDoNotOverwrite() {
        String id=(String)artifacts.create(owner,new ArtifactService.Create("report.txt","sandbox","aGVsbG8=")).get("artifactId");
        assertEquals(AuthService.hash("hello"),artifacts.read(owner,id,1).get("sha256"));
        artifacts.update(owner,id,new ArtifactService.Revision(1,"bmV3"));
        assertEquals("aGVsbG8=",artifacts.read(owner,id,1).get("content_base64"));
        assertEquals("bmV3",artifacts.read(owner,id,0).get("content_base64"));
        assertEquals("ARTIFACT_VERSION_CONFLICT",assertThrows(ApiException.class,()->artifacts.update(owner,id,new ArtifactService.Revision(1,"eA=="))).code);
        assertThrows(ApiException.class,()->artifacts.update(other,id,new ArtifactService.Revision(2,"eA==")));
    }
    @Test void artifactSharingRequiresOwnerAndRevocationAppliesToHistoricalVersions() {
        String id=(String)artifacts.create(owner,new ArtifactService.Create("report","sandbox","eA==")).get("artifactId");
        assertThrows(ApiException.class,()->artifacts.read(admin,id,0));
        assertThrows(ApiException.class,()->artifacts.read(other,id,0));
        artifacts.grant(owner,id,other.id(),true);assertNotNull(artifacts.read(other,id,1));
        assertThrows(ApiException.class,()->artifacts.grant(other,id,admin.id(),true));
        artifacts.grant(owner,id,other.id(),false);assertThrows(ApiException.class,()->artifacts.read(other,id,1));
        auth.create("b-reader","tenant-b","USER","B",Set.of("VIEWER"),Set.of("sandbox"),"reader-secret");
        assertThrows(ApiException.class,()->artifacts.grant(owner,id,"b-reader",true));
        assertThrows(ApiException.class,()->artifacts.read(auth.authenticate("reader-secret"),id,1));
    }
    @Test void artifactValidationRollsBackAndDeletionCleansAllContent() {
        assertThrows(ApiException.class,()->artifacts.create(owner,new ArtifactService.Create("bad","sandbox","%%")));
        assertEquals(0,db.queryForObject("select count(*) from artifacts",Integer.class));
        String oversized=Base64.getEncoder().encodeToString(new byte[262145]);
        assertEquals(413,assertThrows(ApiException.class,()->artifacts.create(owner,new ArtifactService.Create("big","sandbox",oversized))).status);
        String id=(String)artifacts.create(owner,new ArtifactService.Create("report","sandbox","eA==")).get("artifactId");
        artifacts.grant(owner,id,other.id(),true);artifacts.delete(owner,id);
        assertEquals(0,db.queryForObject("select count(*) from artifact_versions",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from artifact_grants",Integer.class));
        assertThrows(ApiException.class,()->artifacts.read(owner,id,1));
    }
    @Test void artifactHttpSupportsUploadButNeverImplicitAdminRead() throws Exception {
        var response=http.perform(post("/api/v1/artifacts").header("Authorization","Bearer owner-secret").contentType("application/json").content("{\"name\":\"report\",\"zone\":\"sandbox\",\"contentBase64\":\"eA==\"}")).andExpect(status().isOk()).andReturn();
        String id=(String)((Map<?,?>)json.read(response.getResponse().getContentAsString())).get("artifactId");
        http.perform(get("/api/v1/artifacts/"+id).header("Authorization","Bearer admin-secret")).andExpect(status().isNotFound());
        http.perform(get("/api/v1/artifacts/"+id).header("Authorization","Bearer owner-secret")).andExpect(status().isOk());
    }

    @Test void artifactCreateRetryIsIdempotentAndDeletedArtifactCannotResurrect() {
        var body=new ArtifactService.Create("report","sandbox","eA==");
        var first=artifacts.create(owner,body,"upload-key");
        assertEquals(first,artifacts.create(owner,body,"upload-key"));
        assertEquals(1,db.queryForObject("select count(*) from artifacts",Integer.class));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,()->artifacts.create(owner,new ArtifactService.Create("changed","sandbox","eA=="),"upload-key")).code);
        artifacts.delete(owner,(String)first.get("artifactId"));
        assertEquals(410,assertThrows(ApiException.class,()->artifacts.create(owner,body,"upload-key")).status);
    }
    @Test void artifactConcurrentRetriesCreateOneObjectAndKeysAreOwnerScoped() throws Exception {
        var body=new ArtifactService.Create("report","sandbox","eA==");
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> create=()->artifacts.create(owner,body,"key");
            var results=pool.invokeAll(List.of(create,create));
            assertEquals(results.get(0).get(),results.get(1).get());
            assertNotEquals(results.get(0).get(),artifacts.create(other,body,"key"));
        } finally {pool.shutdownNow();}
    }

    String toolTask() {
        String task=task("x");
        String tool=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,tool,true);return task;
    }
    ToolContract writeTool() {
        return new ToolContract("fixture.record.put.v1",ToolContract.Effect.IDEMPOTENT_WRITE,
            Map.of("value",new ToolContract.Field(ToolContract.Kind.STRING,true,100)),"vault://fixture/credential","fixture.receipt.v1");
    }
    @Test void toolContractRejectsUnknownFieldsSecretsAndUnsafeRetries() {
        var tool=writeTool();assertFalse(tool.permitsUnverifiedRetry());
        assertThrows(ApiException.class,()->tool.validate(Map.of("value","x","extra",true)));
        assertThrows(ApiException.class,()->tool.validate(Map.of("value",2)));
        assertThrows(ApiException.class,()->tool.validate(Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ToolContract("fixture.x",ToolContract.Effect.READ_ONLY,Map.of(),"plaintext-secret","fixture.verify"));
    }
    @Test void operationKeyIsStableAcrossPreparationAndInputChangesConflict() {
        String task=toolTask();var tool=writeTool();var input=Map.<String,Object>of("value","x");
        String id=operations.prepare(owner,task,tool,"business-1",input);
        assertEquals(id,operations.prepare(owner,task,tool,"business-1",input));
        assertThrows(ApiException.class,()->operations.prepare(owner,task,tool,"business-1",Map.of("value","changed")));
        var changedVerifier=new ToolContract(tool.name(),tool.effect(),tool.fields(),tool.credentialRef(),"changed.verifier");
        assertThrows(ApiException.class,()->operations.prepare(owner,task,changedVerifier,"business-1",input));
        assertThrows(ApiException.class,()->operations.read(other,id));
        assertThrows(ApiException.class,()->operations.read(admin,id));
    }
    @Test void lostExternalResponseBecomesUnknownAndReconcilesWithoutResending() {
        String task=toolTask();var tool=writeTool();var input=Map.<String,Object>of("value","x");
        String id=operations.prepare(owner,task,tool,"business",input);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String key,Map<String,Object> data) throws Exception {assertEquals(id,key);calls.incrementAndGet();throw new java.io.IOException("response lost");}
            public ExternalOperations.Evidence lookup(String key,String hash) {return new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"fixture/receipt-1");}
        };
        assertEquals("UNKNOWN",operations.execute(owner,id,tool,input,gateway).get("state"));
        assertEquals("UNKNOWN",operations.execute(owner,id,tool,input,gateway).get("state"));
        assertEquals(1,calls.get());
        assertEquals("CONFIRMED",operations.reconcile(owner,id,gateway).get("state"));
        assertEquals(1,calls.get());
    }
    @Test void externalDispatchIsCommittedBeforeNetworkCallAndConcurrentCallsSendOnce() throws Exception {
        var tool=writeTool();var input=Map.<String,Object>of("value","x");
        String id=operations.prepare(owner,toolTask(),tool,"business",input);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String key,Map<String,Object> data) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("DISPATCHED",operations.read(owner,id).get("state"));calls.incrementAndGet();
            }
            public ExternalOperations.Evidence lookup(String key,String hash) {return new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"fixture/receipt");}
        };
        var pool=Executors.newFixedThreadPool(2);
        try {Callable<Object> execute=()->operations.execute(owner,id,tool,input,gateway);for(var f:pool.invokeAll(List.of(execute,execute)))f.get();}
        finally {pool.shutdownNow();}
        assertEquals(1,calls.get());assertEquals("CONFIRMED",operations.read(owner,id).get("state"));
    }
    @Test void unverifiedSuccessStaysUnknownAndCancellationPreventsInitialDispatch() {
        var tool=writeTool();var input=Map.<String,Object>of("value","x");String task=toolTask();
        String id=operations.prepare(owner,task,tool,"business",input);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String key,Map<String,Object> data) {calls.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String key,String hash) {return null;}
        };
        assertEquals("UNKNOWN",operations.execute(owner,id,tool,input,gateway).get("state"));
        String second=operations.prepare(owner,task,tool,"business-2",input);
        control.cancel(owner,task);
        assertThrows(ApiException.class,()->operations.execute(owner,second,tool,input,gateway));
        assertEquals(1,calls.get());
    }

    @Test void unregisteredOrUngrantedToolCannotPrepareExternalWrite() {
        String task=task("x");
        assertThrows(ApiException.class,()->operations.prepare(owner,task,writeTool(),"key",Map.of("value","x")));
        String tool=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")).get("toolVersionId");
        assertThrows(ApiException.class,()->operations.prepare(owner,task,writeTool(),"key",Map.of("value","x")));
        registry.grant(admin,task,tool,true);
        assertNotNull(operations.prepare(owner,task,writeTool(),"key",Map.of("value","x")));
        assertThrows(ApiException.class,()->registry.grant(owner,task,tool,true));
    }
    @Test void toolRevocationBetweenPrepareAndDispatchPreventsNetworkCall() {
        String task=toolTask();var tool=writeTool();var input=Map.<String,Object>of("value","x");
        String id=operations.prepare(owner,task,tool,"business",input);
        String version=(String)operations.read(owner,id).get("tool_version_id");
        registry.grant(admin,task,version,false);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String key,Map<String,Object> data) {calls.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String key,String hash) {return null;}
        };
        assertThrows(ApiException.class,()->operations.execute(owner,id,tool,input,gateway));
        assertEquals(0,calls.get());assertEquals("PREPARED",operations.read(owner,id).get("state"));
        registry.grant(admin,task,version,true);registry.disable(admin,version);
        assertThrows(ApiException.class,()->operations.execute(owner,id,tool,input,gateway));
        assertEquals(0,calls.get());
    }
    @Test void toolRegistryRejectsCrossZoneAndImmutableVersionReplacement() throws Exception {
        String task=task("x");
        String tool=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","other")).get("toolVersionId");
        assertThrows(ApiException.class,()->registry.grant(admin,task,tool,true));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")));
        auth.create("limited-admin","tenant-a","USER","Limited",Set.of("ADMIN"),Set.of("sandbox"),"limited-tool-token");
        assertThrows(ApiException.class,()->registry.disable(auth.authenticate("limited-tool-token"),tool));
        http.perform(post("/api/v1/tool-versions").header("Authorization","Bearer owner-secret").contentType("application/json").content(json.write(new ToolRegistry.Publish(writeTool(),"2.0.0","sandbox")))).andExpect(status().isForbidden());
        http.perform(post("/api/v1/tool-versions").header("Authorization","Bearer admin-secret").contentType("application/json").content(json.write(new ToolRegistry.Publish(writeTool(),"2.0.0","sandbox")))).andExpect(status().isOk());
    }

    @Test void revokedToolCannotPerformExternalReconciliationLookup() {
        String task=toolTask();var input=Map.<String,Object>of("value","x");
        String id=operations.prepare(owner,task,writeTool(),"business",input);
        var lookups=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String key,Map<String,Object> data) throws Exception {throw new java.io.IOException("unknown");}
            public ExternalOperations.Evidence lookup(String key,String hash) {lookups.incrementAndGet();return null;}
        };
        operations.execute(owner,id,writeTool(),input,gateway);
        registry.disable(admin,(String)operations.read(owner,id).get("tool_version_id"));
        assertThrows(ApiException.class,()->operations.reconcile(owner,id,gateway));assertEquals(0,lookups.get());
    }

    @Test void taskToolCatalogExcludesCredentialReferencesAndRevokedTools() throws Exception {
        String task=toolTask();var catalog=registry.catalog(owner,task);
        assertEquals(1,catalog.size());String serialized=json.write(catalog);
        assertTrue(serialized.contains("additionalProperties"));assertFalse(serialized.contains("vault://"));assertFalse(serialized.contains("verifier"));
        assertThrows(ApiException.class,()->registry.catalog(admin,task));assertThrows(ApiException.class,()->registry.catalog(other,task));
        http.perform(get("/api/v1/tasks/"+task+"/tools").header("Authorization","Bearer owner-secret")).andExpect(status().isOk());
        registry.grant(admin,task,(String)catalog.get(0).get("toolVersionId"),false);
        assertTrue(registry.catalog(owner,task).isEmpty());
    }

    @Test void taskArtifactsPinVersionAndRepeatedAttachmentIsIdempotent() throws Exception {
        String task=task("x");String id=(String)artifacts.create(owner,new ArtifactService.Create("report","sandbox","eA==")).get("artifactId");
        var attachment=new ArtifactService.Attachment(id,1);artifacts.attach(owner,task,attachment);artifacts.attach(owner,task,attachment);
        artifacts.update(owner,id,new ArtifactService.Revision(1,"eQ=="));
        var rows=artifacts.taskArtifacts(owner,task);assertEquals(1,rows.size());assertEquals(1,((Number)rows.get(0).get("version")).intValue());
        assertEquals(AuthService.hash("x"),rows.get(0).get("sha256"));
        http.perform(get("/api/v1/tasks/"+task+"/artifacts").header("Authorization","Bearer owner-secret")).andExpect(status().isOk());
        http.perform(post("/api/v1/tasks/"+task+"/artifacts").header("Authorization","Bearer owner-secret").contentType("application/json").content(json.write(attachment))).andExpect(status().isNoContent());
    }
    @Test void taskArtifactLinkDoesNotGrantImplicitReadOrAllowForeignOwner() {
        String task=task("x");String id=(String)artifacts.create(owner,new ArtifactService.Create("report","sandbox","eA==")).get("artifactId");
        assertThrows(ApiException.class,()->artifacts.attach(other,task,new ArtifactService.Attachment(id,1)));
        assertThrows(ApiException.class,()->artifacts.taskArtifacts(admin,task));
        String foreign=(String)artifacts.create(other,new ArtifactService.Create("foreign","sandbox","eA==")).get("artifactId");
        artifacts.grant(other,foreign,owner.id(),true);
        assertThrows(ApiException.class,()->artifacts.attach(owner,task,new ArtifactService.Attachment(foreign,1)));
        assertThrows(ApiException.class,()->artifacts.attach(owner,task,new ArtifactService.Attachment(id,99)));
    }
    @Test void deletingArtifactRemovesTaskLinksAndContents() {
        String task=task("x");String id=(String)artifacts.create(owner,new ArtifactService.Create("report","sandbox","eA==")).get("artifactId");
        artifacts.attach(owner,task,new ArtifactService.Attachment(id,1));artifacts.delete(owner,id);
        assertTrue(artifacts.taskArtifacts(owner,task).isEmpty());
        assertEquals(0,db.queryForObject("select count(*) from artifact_versions",Integer.class));
    }

    @Test void browserUnitsRequireImmutableAdminGrantAndSeparateCapability() {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("browser-agent","1.0.0","browser.write.v1")).get("agentId");
        String task=(String)control.createTask(owner,"browser",new Requests.Task(agent,"sandbox",List.of("upload report"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        var created=control.registerNode(admin,new Requests.Node("Browser","sandbox",1,"browser.write.v1"));
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);
        assertEquals(0,control.tick());
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("report.txt","sandbox","eA==")).get("artifactId");
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));
        var grant=new BrowserExecution.Grant("https://crm.internal/",Set.of("upload"),1,artifact,1);
        assertThrows(ApiException.class,()->browser.grant(owner,task,unit,grant));
        browser.grant(admin,task,unit,grant);browser.grant(admin,task,unit,grant);
        assertEquals(1,control.tick());var work=control.poll(node);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertEquals("https://crm.internal",browser.scope(node,attempt,epoch).get("origin"));
        assertEquals("eA==",browser.attachment(node,attempt,epoch).get("content_base64"));
        assertThrows(ApiException.class,()->browser.scope(modelNode(),attempt,epoch));
        assertThrows(ApiException.class,()->models.catalog(node,attempt,epoch));
        assertThrows(ApiException.class,()->browser.grant(admin,task,unit,new BrowserExecution.Grant("https://other.internal",Set.of("upload"),1,artifact,1)));
        browser.revoke(admin,task,unit);
        assertThrows(ApiException.class,()->browser.scope(node,attempt,epoch));
        assertThrows(ApiException.class,()->browser.attachment(node,attempt,epoch));
        assertThrows(ApiException.class,()->browser.grant(admin,task,unit,grant));
    }
    @Test void browserActionClaimIsDurableBoundedAndCancellationRequiresReconciliation()throws Exception {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("browser-ledger","1.0.0","browser.write.v1")).get("agentId");
        String task=(String)control.createTask(owner,"browser-ledger",new Requests.Task(agent,"sandbox",List.of("submit"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        browser.grant(admin,task,unit,new BrowserExecution.Grant("https://crm.internal",Set.of("click"),1,null,null));
        var created=control.registerNode(admin,new Requests.Node("Browser","sandbox",1,"browser.write.v1"));
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);
        String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var action=new BrowserExecution.Action(epoch,"save","click","a".repeat(64));
        assertEquals("NOT_FOUND",browser.lookup(node,attempt,action).get("status"));
        assertEquals(0,db.queryForObject("select count(*) from browser_actions",Integer.class));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> claim=()->{start.await();return (Boolean)browser.claim(node,attempt,action).get("shouldExecute");};
            var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertThrows(ApiException.class,()->browser.claim(node,attempt,new BrowserExecution.Action(epoch,"another","click","b".repeat(64))));
        browser.dispatched(node,attempt,action);assertEquals(false,browser.claim(node,attempt,action).get("shouldExecute"));
        assertThrows(ApiException.class,()->browser.claim(node,attempt,new BrowserExecution.Action(epoch,"save","click","b".repeat(64))));
        assertThrows(ApiException.class,()->browser.claim(node,attempt,new BrowserExecution.Action(epoch,"second","click","b".repeat(64))));
        time.advance(90001);control.heartbeat(node,true);control.tick();var retry=control.poll(node);
        String next=(String)retry.get("attemptId");long nextEpoch=((Number)retry.get("epoch")).longValue();
        assertNotEquals(attempt,next);
        assertEquals(false,browser.claim(node,next,new BrowserExecution.Action(nextEpoch,"save","click","a".repeat(64))).get("shouldExecute"));
        assertEquals("BROWSER_RECOVERY_REQUIRED",assertThrows(ApiException.class,()->browser.claim(node,next,new BrowserExecution.Action(nextEpoch,"renamed","click","b".repeat(64)))).code);
        control.cancel(owner,task);assertEquals("RECONCILING",control.getTask(owner,task).get("state"));
        assertThrows(ApiException.class,()->browser.dispatched(node,attempt,action));
    }
    @Test void browserReportReleasesAttemptAndPreservesUnknownBusinessOutcome() {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("browser-report","1.0.0","browser.write.v1")).get("agentId");
        String task=(String)control.createTask(owner,"browser-report",new Requests.Task(agent,"sandbox",List.of("submit"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        browser.grant(admin,task,unit,new BrowserExecution.Grant("https://crm.internal",Set.of("click"),2,null,null));
        var created=control.registerNode(admin,new Requests.Node("Browser","sandbox",1,"browser.write.v1"));
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);
        String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var action=new BrowserExecution.Action(epoch,"save","click","a".repeat(64));browser.claim(node,attempt,action);
        var report=new BrowserExecution.Report(epoch,"Page appeared successful; this is not proof.");
        var result=browser.report(node,attempt,report);assertEquals(false,result.get("businessVerified"));
        assertEquals("RECONCILING",control.getTask(owner,task).get("state"));
        assertEquals(0,db.queryForObject("select count(*) from attempts where node_id=? and state in ('RUNNING','RESERVED')",Integer.class,node.id()));
        assertEquals(true,browser.report(node,attempt,report).get("duplicate"));assertEquals(1,artifacts.taskArtifacts(owner,task).size());
        var saved=artifacts.read(owner,(String)result.get("artifactId"),1);
        String manifest=new String(Base64.getDecoder().decode((String)saved.get("content_base64")),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(manifest.contains("UNKNOWN"));assertTrue(manifest.contains("businessVerified"));
        assertThrows(ApiException.class,()->browser.report(node,attempt,new BrowserExecution.Report(epoch,"changed")));
        assertThrows(ApiException.class,()->browser.claim(node,attempt,action));
    }
    @Test void desktopControlInventoryReportsExpiredOccupancyWithoutChangingOwnership() throws Exception {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        computerActions.operate(node,ids[4],new ComputerActions.Action(1,1,"uncertain","fill","a".repeat(64)),"claim");
        var page=computerInventory.page(admin,ids[2],"",1,false);var item=(Map<?,?>)((List<?>)page.get("items")).get(0);
        assertEquals(false,item.get("leaseExpired"));assertEquals(true,item.get("requiresStopConfirmation"));
        assertEquals(1L,((Number)item.get("unknownActions")).longValue());assertFalse(item.containsKey("policy"));
        assertTrue(((List<?>)computerInventory.page(admin,"","",10,true).get("items")).isEmpty());
        time.advance(15000);
        http.perform(get("/api/v1/computer-controls").header("Authorization","Bearer admin-secret").param("expiredOnly","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].attemptId").value(ids[4])).andExpect(jsonPath("$.items[0].leaseExpired").value(true));
        assertEquals(ids[4],db.queryForObject("select current_attempt from computer_sessions where node_id=?",String.class,ids[2]));
        assertTrue(((List<?>)computerInventory.page(admin,"",ids[4],10,false).get("items")).isEmpty());
        computerControl.release(node,ids[4],1,1,true);
        assertTrue(((List<?>)computerInventory.page(admin,"","",10,false).get("items")).isEmpty());
    }
    @Test void desktopControlInventoryRequiresLiveAdminAndBothNodeAndTaskZones() {
        var ids=runningComputerFixture();var node=auth.authenticate(ids[3]);computerControl.acquire(node,ids[4],1);
        assertThrows(ApiException.class,()->computerInventory.page(owner,"","",10,false));
        assertThrows(ApiException.class,()->computerInventory.page(node,"","",10,false));
        auth.create("desk-ops","tenant-a","USER","Ops",Set.of("ADMIN"),Set.of("sandbox"),"desk-ops-token");
        var ops=auth.authenticate("desk-ops-token");
        assertEquals(1,((List<?>)computerInventory.page(ops,"","",10,false).get("items")).size());
        db.update("update nodes set zone='other' where id=?",ids[2]);
        assertTrue(((List<?>)computerInventory.page(ops,"","",10,false).get("items")).isEmpty());
        db.update("update nodes set zone='sandbox' where id=?",ids[2]);db.update("update tasks set zone='other' where id=?",ids[0]);
        assertTrue(((List<?>)computerInventory.page(ops,"","",10,false).get("items")).isEmpty());
        auth.disable(admin,ops.id());assertThrows(ApiException.class,()->computerInventory.page(ops,"","",10,false));
        assertThrows(ApiException.class,()->computerInventory.page(admin,"","",101,false));
    }
    ComputerReconciliation computerReconciler(ComputerVerifiers.Verifier verifier) {
        return new ComputerReconciliation(db,auth,locks,new ComputerVerifiers(List.of(new ComputerVerifiers.Binding("fixture",Set.of("tenant-a"),Set.of("sandbox"),verifier))),control,computerGrants,json,time,transactions);
    }
    String[] stoppedComputer(ComputerReconciliation service,boolean release) {
        var ids=computerUnit();
        assertThrows(ApiException.class,()->service.authorize(admin,ids[1],new ComputerReconciliation.Authorization(computerGrant(ids[2]),new ComputerReconciliation.Bind("absent","key"))));
        assertEquals(0,db.queryForObject("select count(*) from computer_grants where unit_id=?",Integer.class,ids[1]));
        service.authorize(admin,ids[1],new ComputerReconciliation.Authorization(computerGrant(ids[2]),new ComputerReconciliation.Bind("fixture","key")));
        var node=auth.authenticate(ids[3]);control.heartbeat(node,true);String attempt=AuthService.id();
        db.update("update work_units set state='RUNNING',epoch=1,attempts=1 where id=?",ids[1]);
        db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,1,'RUNNING',?,?,?)",attempt,ids[1],ids[2],time.millis()+90000,time.millis(),owner.tenant());
        computerControl.acquire(node,attempt,1);
        computerActions.operate(node,attempt,new ComputerActions.Action(1,1,"save","click","a".repeat(64)),"claim");
        control.cancel(owner,ids[0]);
        if(release)computerControl.release(node,attempt,1,1,true);
        return new String[]{ids[0],ids[1],ids[2],ids[3],attempt};
    }
    @Test void computerIndependentEvidenceSettlesUnknownWithoutReplayingOrRequiringOwnerAccess() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=computerReconciler(q->new ComputerVerifiers.Evidence(calls.incrementAndGet()==1?ComputerVerifiers.Outcome.UNKNOWN:ComputerVerifiers.Outcome.CONFIRMED,q.proofHash(),"source-record-123"));
        var ids=stoppedComputer(service,true);var reviewer=browserReviewer();
        assertThrows(ApiException.class,()->service.reconcile(reviewer,ids[1]));service.grant(admin,ids[1],reviewer.id(),true);
        auth.disable(admin,owner.id());
        assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
        var confirmed=service.reconcile(reviewer,ids[1]);assertEquals(true,confirmed.get("businessVerified"));
        assertEquals(confirmed,service.reconcile(reviewer,ids[1]));assertEquals(2,calls.get());
        assertEquals("RECONCILED",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
        assertEquals("SUCCEEDED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
        assertEquals("UNKNOWN",db.queryForObject("select state from computer_actions where unit_id=?",String.class,ids[1]));
    }
    @Test void computerReconciliationRequiresDriverStopEvenAfterControlExpiry() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=computerReconciler(q->{calls.incrementAndGet();return new ComputerVerifiers.Evidence(ComputerVerifiers.Outcome.REJECTED,q.proofHash(),"source-rejected");});
        var ids=stoppedComputer(service,false);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
        time.advance(20000);
        assertEquals("DRIVER_STOP_REQUIRED",assertThrows(ApiException.class,()->service.reconcile(reviewer,ids[1])).code);assertEquals(0,calls.get());
        computerControl.release(auth.authenticate(ids[3]),ids[4],1,1,true);
        assertEquals("REJECTED",service.reconcile(reviewer,ids[1]).get("state"));
        control.cancel(owner,ids[0]);
        assertEquals("FAILED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
    }
    @Test void computerVerifierProofAndMidLookupRevocationCannotProduceSettlement() {
        var service=computerReconciler(q->new ComputerVerifiers.Evidence(ComputerVerifiers.Outcome.CONFIRMED,"bad-proof","receipt"));
        var ids=stoppedComputer(service,true);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
        assertEquals(false,service.reconcile(reviewer,ids[1]).get("businessVerified"));
        var revoked=computerReconciler(q->{service.grant(admin,ids[1],reviewer.id(),false);return new ComputerVerifiers.Evidence(ComputerVerifiers.Outcome.CONFIRMED,q.proofHash(),"receipt");});
        assertThrows(ApiException.class,()->revoked.reconcile(reviewer,ids[1]));
        assertEquals(0,db.queryForObject("select count(*) from computer_reconciliations",Integer.class));
    }
    @Test void computerVerifierBindingIsImmutableAndRegistryIsTenantZoneScoped() {
        var service=computerReconciler(q->null);var ids=stoppedComputer(service,true);
        service.bind(admin,ids[1],new ComputerReconciliation.Bind("fixture","key"));
        assertThrows(ApiException.class,()->service.bind(admin,ids[1],new ComputerReconciliation.Bind("fixture","changed")));
        assertThrows(ApiException.class,()->service.bind(admin,ids[1],new ComputerReconciliation.Bind("fixture",null)));
        var registry=new ComputerVerifiers(List.of(new ComputerVerifiers.Binding("v",Set.of("tenant-a"),Set.of("sandbox"),q->null)));
        assertThrows(ApiException.class,()->registry.resolve("tenant-b","v","sandbox"));
        assertThrows(ApiException.class,()->registry.resolve("tenant-a","v","other"));
        assertThrows(ApiException.class,()->service.grant(owner,ids[1],owner.id(),true));
    }
    BrowserReconciliation browserReconciler(BrowserVerifiers.Verifier verifier) {
        return new BrowserReconciliation(db,auth,locks,new BrowserVerifiers(List.of(new BrowserVerifiers.Binding("fixture",Set.of("tenant-a"),"https://crm.internal",verifier))),control,browser,json,time,transactions);
    }
    String[] stoppedBrowser(BrowserReconciliation service) {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("browser-proof","1.0.0","browser.write.v1")).get("agentId");
        String task=(String)control.createTask(owner,"browser-proof",new Requests.Task(agent,"sandbox",List.of("submit"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        var grant=new BrowserExecution.Grant("https://crm.internal",Set.of("click"),2,null,null);
        assertThrows(ApiException.class,()->service.authorize(admin,unit,new BrowserReconciliation.Authorization(grant,new BrowserReconciliation.Bind("absent","business-123"))));
        assertEquals(0,db.queryForObject("select count(*) from browser_grants where unit_id=?",Integer.class,unit));
        service.authorize(admin,unit,new BrowserReconciliation.Authorization(grant,new BrowserReconciliation.Bind("fixture","business-123")));
        var created=control.registerNode(admin,new Requests.Node("Browser","sandbox",1,"browser.write.v1"));
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);
        String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        browser.claim(node,attempt,new BrowserExecution.Action(epoch,"save","click","a".repeat(64)));
        browser.report(node,attempt,new BrowserExecution.Report(epoch,"source pending"));
        return new String[]{task,unit};
    }
    Identity browserReviewer() {
        auth.create("browser-reviewer","tenant-a","USER","Reviewer",Set.of("RECONCILER"),Set.of("sandbox"),"review-secret");return auth.authenticate("review-secret");
    }
    @Test void browserTrustedLookupSettlesAfterOwnerRevocationAndNeverDispatchesAgain() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=browserReconciler(query->new BrowserVerifiers.Evidence(calls.incrementAndGet()==1?BrowserVerifiers.Outcome.UNKNOWN:BrowserVerifiers.Outcome.CONFIRMED,query.proofHash(),"source-receipt"));
        var ids=stoppedBrowser(service);var reviewer=browserReviewer();
        assertThrows(ApiException.class,()->service.reconcile(reviewer,ids[1]));service.grant(admin,ids[1],reviewer.id(),true);
        auth.disable(admin,owner.id());
        assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
        assertEquals("RECONCILING",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
        assertEquals("CONFIRMED",service.reconcile(reviewer,ids[1]).get("state"));
        assertEquals("CONFIRMED",service.reconcile(reviewer,ids[1]).get("state"));assertEquals(2,calls.get());
        assertEquals("RECONCILED",db.queryForObject("select state from tasks where id=?",String.class,ids[0]));
        assertEquals("SUCCEEDED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
        assertEquals(1,db.queryForObject("select count(*) from browser_actions",Integer.class));
    }
    @Test void browserProofMismatchRemainsUnknownAndReviewerRevocationBlocksCommit() {
        var service=browserReconciler(query->new BrowserVerifiers.Evidence(BrowserVerifiers.Outcome.CONFIRMED,"0".repeat(64),"mismatched"));
        var ids=stoppedBrowser(service);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
        assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
        var revoked=browserReconciler(query->{service.grant(admin,ids[1],reviewer.id(),false);return new BrowserVerifiers.Evidence(BrowserVerifiers.Outcome.CONFIRMED,query.proofHash(),"receipt");});
        assertThrows(ApiException.class,()->revoked.reconcile(reviewer,ids[1]));
        assertEquals(0,db.queryForObject("select count(*) from browser_reconciliations",Integer.class));
    }
    @Test void browserRejectedEvidenceIsVerifiedFailureNotBusinessSuccess() {
        var service=browserReconciler(query->new BrowserVerifiers.Evidence(BrowserVerifiers.Outcome.REJECTED,query.proofHash(),"rejected-receipt"));
        var ids=stoppedBrowser(service);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
        assertEquals("REJECTED",service.reconcile(reviewer,ids[1]).get("state"));
        assertEquals("RECONCILED",control.getTask(owner,ids[0]).get("state"));
        assertEquals("FAILED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
        control.cancel(owner,ids[0]);assertEquals("FAILED",db.queryForObject("select state from work_units where id=?",String.class,ids[1]));
        assertThrows(ApiException.class,()->service.bind(admin,ids[1],new BrowserReconciliation.Bind("fixture","different-business")));
    }
    @Test void browserHttpSourceEvidenceSettlesOnlyMatchingBusinessReceipt()throws Exception {
        var source=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var response=new java.util.concurrent.atomic.AtomicReference<String>("{}");var calls=new java.util.concurrent.atomic.AtomicInteger();
        source.createContext("/",exchange->{
            calls.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());
            byte[] bytes=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });source.start();
        try {
            var verifier=new HttpBrowserVerifier(java.net.URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/"),()->"readonly-fixture",new com.fasterxml.jackson.databind.ObjectMapper(),1000);
            var service=browserReconciler(verifier);var ids=stoppedBrowser(service);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
            assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
            var binding=db.queryForMap("select * from browser_verification_bindings where unit_id=?",ids[1]);
            response.set(json.write(Map.of("unitId",ids[1],"businessKeyHash",binding.get("key_hash"),"proofHash",binding.get("proof_hash"),"status","CONFIRMED","receipt","fixture-source-result")));
            assertEquals("CONFIRMED",service.reconcile(reviewer,ids[1]).get("state"));
            assertEquals("RECONCILED",control.getTask(owner,ids[0]).get("state"));
            service.reconcile(reviewer,ids[1]);assertEquals(2,calls.get());
        } finally {source.stop(0);}
    }
    @Test void desktopHttpSourceLookupRejectsBadProofThenSettlesFromIndependentReceipt() throws Exception {
        var response=new java.util.concurrent.atomic.AtomicReference<>("{}");var calls=new java.util.concurrent.atomic.AtomicInteger();
        var source=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/computer-results/",exchange->{
            calls.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());
            assertEquals("Bearer desktop-source-token",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });source.start();
        try {
            var verifier=new HttpComputerVerifier(java.net.URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/"),()->"desktop-source-token",new com.fasterxml.jackson.databind.ObjectMapper(),1000);
            var service=computerReconciler(verifier);var ids=stoppedComputer(service,true);var reviewer=browserReviewer();service.grant(admin,ids[1],reviewer.id(),true);
            assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
            var binding=db.queryForMap("select * from computer_verification_bindings where unit_id=?",ids[1]);
            var reply=new LinkedHashMap<String,Object>(Map.of("unitId",ids[1],"businessKeyHash",binding.get("key_hash"),"proofHash","a".repeat(64),"status","CONFIRMED","receipt","source-record"));
            response.set(json.write(reply));assertEquals("UNKNOWN",service.reconcile(reviewer,ids[1]).get("state"));
            reply.put("proofHash",binding.get("proof_hash"));response.set(json.write(reply));
            assertEquals("CONFIRMED",service.reconcile(reviewer,ids[1]).get("state"));
            assertEquals("RECONCILED",control.getTask(owner,ids[0]).get("state"));
            service.reconcile(reviewer,ids[1]);assertEquals(3,calls.get());
        }finally{source.stop(0);}
    }
    @Test void desktopHttpVerifierDoesNotFollowRedirectsOrAcceptMalformedSourceDocuments() throws Exception {
        var response=new java.util.concurrent.atomic.AtomicReference<>("{}");var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var forwarded=new java.util.concurrent.atomic.AtomicInteger();
        var source=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/computer-results/",exchange->{
            exchange.getResponseHeaders().set("Location","/forbidden");byte[] body=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);try{exchange.getResponseBody().write(body);}finally{exchange.close();}
        });source.createContext("/forbidden",exchange->{forwarded.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});source.start();
        try {
            var verifier=new HttpComputerVerifier(java.net.URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/"),()->"token",new com.fasterxml.jackson.databind.ObjectMapper(),1000);
            var query=new ComputerVerifiers.Query("unit","key","a".repeat(64),"{}");
            String valid=json.write(Map.of("unitId","unit","businessKeyHash",AuthService.hash("key"),"proofHash",query.proofHash(),"status","CONFIRMED","receipt","source"));
            for(String invalid:List.of(valid+" {}",valid.replace("\"status\":\"CONFIRMED\"","\"status\":\"UNKNOWN\""),valid.substring(0,valid.length()-1)+",\"unitId\":\"unit\"}","x".repeat(16385))){
                response.set(invalid);assertEquals(ComputerVerifiers.Outcome.UNKNOWN,verifier.lookup(query).outcome());
            }
            response.set(valid);status.set(302);assertEquals(ComputerVerifiers.Outcome.UNKNOWN,verifier.lookup(query).outcome());assertEquals(0,forwarded.get());
            status.set(503);assertEquals(ComputerVerifiers.Outcome.UNKNOWN,verifier.lookup(query).outcome());
        }finally{source.stop(0);}
    }
    @Test void desktopHttpVerifierConfigurationAndMissingCredentialFailClosed() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String url:List.of("http://crm.internal/","https://user@crm.internal/","https://crm.internal/?x=1","https://crm.internal/%2e/","https://crm.internal:0/"))
            assertThrows(IllegalArgumentException.class,()->new HttpComputerVerifier(java.net.URI.create(url),()->"token",mapper,1000));
        var source=new HttpComputerVerifier(java.net.URI.create("https://crm.internal/"),()->{throw new IllegalStateException("vault unavailable");},mapper,1000);
        assertEquals(ComputerVerifiers.Outcome.UNKNOWN,source.lookup(new ComputerVerifiers.Query("unit","key","a".repeat(64),"{}")).outcome());
    }
    @Test void browserOriginPolicyRejectsPathsCredentialsAndInsecureRemoteDestinations() {
        assertEquals("https://crm.internal",BrowserExecution.origin("https://CRM.internal:443/"));
        assertEquals("http://127.0.0.1:9000",BrowserExecution.origin("http://127.0.0.1:9000"));
        for(String origin:List.of("http://crm.internal","https://user@crm.internal","https://crm.internal/path","https://crm.internal?x=y","https://crm.internal#fragment","https://crm.internal:0"))
            assertThrows(ApiException.class,()->BrowserExecution.origin(origin));
    }
    Identity modelNode() {
        var created=control.registerNode(admin,new Requests.Node("Model node","sandbox",1,"model.read.v1"));
        var node=auth.authenticate((String)created.get("token"));control.heartbeat(node,true);return node;
    }
    String modelTask() {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("model-agent","1.0.0","model.read.v1")).get("agentId");
        return (String)control.createTask(owner,"model-task",new Requests.Task(agent,"sandbox",List.of("model prompt"))).get("taskId");
    }
    @Test void documentBindingsAreImmutableAndRequireLiveLeaseAndToolGrant() throws Exception {
        Identity model=modelNode();String task=modelTask();
        var read=new ToolContract("document.fixed.read.v1",ToolContract.Effect.READ_ONLY,Map.of(),"vault://document/delegated","document.etag.v1");
        String tool=(String)registry.publish(admin,new ToolRegistry.Publish(read,"1.0.0","sandbox")).get("toolVersionId");
        var binding=new DocumentBindings.Binding("document-1","a".repeat(64));
        assertThrows(ApiException.class,()->documents.bind(admin,task,tool,binding));
        registry.grant(admin,task,tool,true);
        assertThrows(ApiException.class,()->documents.bind(owner,task,tool,binding));
        http.perform(put("/api/v1/tasks/"+task+"/documents/"+tool).header("Authorization","Bearer admin-secret").contentType("application/json").content(json.write(binding))).andExpect(status().isOk());
        documents.bind(admin,task,tool,binding);
        control.reportSourceDeployments(model,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),binding.sourceId(),binding.deploymentHash()))));
        assertThrows(ApiException.class,()->documents.bind(admin,task,tool,new DocumentBindings.Binding("document-1","b".repeat(64))));
        control.tick();var work=control.poll(model);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var scope=documents.scope(model,attempt,epoch);
        assertEquals(owner.id(),scope.get("executionPrincipalId"));assertEquals(owner.tenant(),scope.get("tenant"));
        assertEquals(1,((List<?>)scope.get("bindings")).size());
        assertFalse(json.write(scope).contains("vault://"));
        assertThrows(ApiException.class,()->documents.scope(modelNode(),attempt,epoch));
        assertThrows(ApiException.class,()->documents.scope(model,attempt,epoch+1));
        registry.grant(admin,task,tool,false);
        assertEquals(List.of(),documents.scope(model,attempt,epoch).get("bindings"));
        registry.grant(admin,task,tool,true);documents.revoke(admin,task,tool);
        assertEquals(List.of(),documents.scope(model,attempt,epoch).get("bindings"));
        assertThrows(ApiException.class,()->documents.bind(admin,task,tool,binding));
        auth.disable(admin,owner.id());
        assertThrows(ApiException.class,()->documents.scope(model,attempt,epoch));
    }
    @Test void taskCreationAttachesInputsAtomicallyAndHashesVersions() {
        Identity model=modelNode();
        String agent=(String)control.publishAgent(admin,new Requests.Agent("atomic-model","1.0.0","model.read.v1")).get("agentId");
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("source.txt","sandbox","eA==")).get("artifactId");
        var link=new ArtifactService.Attachment(artifact,1);
        var request=new Requests.Task(agent,"sandbox",List.of("read attached"),List.of(link));
        String task=(String)control.createTask(owner,"atomic",request).get("taskId");
        assertEquals(true,control.createTask(owner,"atomic",request).get("reused"));
        assertThrows(ApiException.class,()->control.createTask(owner,"atomic",new Requests.Task(agent,"sandbox",List.of("read attached"))));
        control.tick();var work=control.poll(model);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertEquals("eA==",models.attachment(model,attempt,epoch,artifact,1).get("content_base64"));
        assertThrows(ApiException.class,()->control.createTask(owner,"invalid-links",new Requests.Task(agent,"sandbox",List.of("x"),List.of(link,new ArtifactService.Attachment(artifact,2)))));
        assertEquals(0,db.queryForObject("select count(*) from tasks where request_key='invalid-links'",Integer.class));
        assertEquals(1,artifacts.taskArtifacts(owner,task).size());
        assertThrows(ApiException.class,()->control.createTask(owner,"duplicates",new Requests.Task(agent,"sandbox",List.of("x"),List.of(link,link))));
        assertEquals(false,control.createTask(owner,"invalid-links",request).get("reused"));
        var legacy=new Requests.Task(agent,"sandbox",List.of("legacy"));
        String old=(String)control.createTask(owner,"legacy-hash",legacy).get("taskId");
        assertEquals(AuthService.hash("{\"agentId\":\""+agent+"\",\"zone\":\"sandbox\",\"inputs\":[\"legacy\"]}"),db.queryForObject("select request_hash from tasks where id=?",String.class,old));
    }
    @Test void modelAttachmentDirectoryIsPagedAndLeaseBound() {
        Identity model=modelNode();String task=modelTask();
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("source.txt","sandbox","eA==")).get("artifactId");
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));
        artifacts.update(owner,artifact,new ArtifactService.Revision(1,"eQ=="));
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,2));
        artifacts.create(owner,new ArtifactService.Create("unlinked.txt","sandbox","eg=="));
        control.tick();var work=control.poll(model);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var first=models.attachments(model,attempt,epoch,"",0,1);
        assertEquals(true,first.get("hasMore"));assertEquals(1,((Number)first.get("afterVersion")).intValue());
        assertFalse(first.toString().contains("content_base64"));assertFalse(first.toString().contains("unlinked.txt"));
        var second=models.attachments(model,attempt,epoch,artifact,1,1);
        assertEquals(false,second.get("hasMore"));assertEquals(2,((Number)second.get("afterVersion")).intValue());
        assertEquals(List.of(),models.attachments(model,attempt,epoch,artifact,2,1).get("items"));
        assertThrows(ApiException.class,()->models.attachments(model,attempt,epoch,"",0,21));
        assertThrows(ApiException.class,()->models.attachments(modelNode(),attempt,epoch,"",0,20));
        assertThrows(ApiException.class,()->models.attachments(model,attempt,epoch+1,"",0,20));
        auth.disable(admin,owner.id());
        assertThrows(ApiException.class,()->models.attachments(model,attempt,epoch,"",0,20));
    }
    @Test void modelAttachmentReadsOnlyLinkedImmutableVersionUnderLiveLease()throws Exception {
        Identity model=modelNode();String task=modelTask();
        String encoded=Base64.getEncoder().encodeToString("original".getBytes());
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("source.txt","sandbox",encoded)).get("artifactId");
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));
        artifacts.update(owner,artifact,new ArtifactService.Revision(1,Base64.getEncoder().encodeToString("changed".getBytes())));
        control.tick();var work=control.poll(model);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertEquals(encoded,models.attachment(model,attempt,epoch,artifact,1).get("content_base64"));
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch,artifact,0));
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch,artifact,2));
        assertThrows(ApiException.class,()->models.attachment(modelNode(),attempt,epoch,artifact,1));
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch+1,artifact,1));
        models.output(model,attempt,new Requests.ModelResult(epoch,"done"));
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch,artifact,1));
    }
    @Test void modelAttachmentRequiresLinkAndCurrentOwnerPermission() {
        Identity model=modelNode();String task=modelTask();
        String artifact=(String)artifacts.create(owner,new ArtifactService.Create("source.txt","sandbox","eA==")).get("artifactId");
        control.tick();var work=control.poll(model);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch,artifact,1));
        artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));
        assertEquals("eA==",models.attachment(model,attempt,epoch,artifact,1).get("content_base64"));
        auth.disable(admin,owner.id());
        assertThrows(ApiException.class,()->models.attachment(model,attempt,epoch,artifact,1));
    }
    @Test void taskResultReadsOriginalModelVersionAndKeepsOwnerBoundary() throws Exception {
        Identity node=modelNode();String task=modelTask();control.tick();var work=control.poll(node);
        String unit=(String)work.get("unitId");
        var output=models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"原始正文 <script>not executable</script>"));
        String artifact=(String)output.get("artifactId");
        artifacts.update(owner,artifact,new ArtifactService.Revision(1,Base64.getEncoder().encodeToString("later edit".getBytes())));
        var read=artifacts.taskResult(owner,task,unit);
        assertEquals("原始正文 <script>not executable</script>",new String(Base64.getDecoder().decode((String)read.get("contentBase64")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1,read.get("version"));assertEquals(false,read.get("businessVerified"));assertEquals("UNREVIEWED",read.get("reviewState"));
        artifacts.grant(owner,artifact,other.id(),true);
        assertThrows(ApiException.class,()->artifacts.taskResult(other,task,unit));
        assertThrows(ApiException.class,()->artifacts.taskResult(admin,task,unit));
        assertThrows(ApiException.class,()->artifacts.taskResult(owner,task,"unknown"));
        http.perform(get("/api/v1/tasks/"+task+"/units/"+unit+"/result-content").header("Authorization","Bearer owner-secret"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.businessVerified").value(false));
        artifacts.delete(owner,artifact);assertThrows(ApiException.class,()->artifacts.taskResult(owner,task,unit));
    }
    @Test void taskResultRejectsMismatchedDigestAndRevokedOwner() {
        Identity node=modelNode();String task=modelTask();control.tick();var work=control.poll(node);String unit=(String)work.get("unitId");
        var output=models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"draft"));
        db.update("update artifact_versions set sha256=? where artifact_id=?",AuthService.hash("changed"),output.get("artifactId"));
        assertThrows(ApiException.class,()->artifacts.taskResult(owner,task,unit));
        auth.disable(admin,owner.id());assertThrows(ApiException.class,()->artifacts.taskResult(owner,task,unit));
    }
    @Test void modelDispatchIsCapabilityMatchedAndGeneratedOutputIsNotBusinessSuccess() {
        Identity text=node("sandbox",1);Identity model=modelNode();task("text");String task=modelTask();
        assertEquals(2,control.tick());assertEquals("text.stats.v1",control.poll(text).get("capability"));
        var work=control.poll(model);assertEquals("model.read.v1",work.get("capability"));
        assertThrows(ApiException.class,()->control.result(model,(String)work.get("attemptId"),result(work)));
        var output=new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"draft output");
        var accepted=models.output(model,(String)work.get("attemptId"),output);
        assertEquals("UNREVIEWED",accepted.get("reviewState"));assertEquals("OUTPUT_READY",control.getTask(owner,task).get("state"));
        control.cancel(owner,task);assertEquals("OUTPUT_READY",control.getTask(owner,task).get("state"));
        assertEquals(1,artifacts.taskArtifacts(owner,task).size());
        assertEquals(true,models.output(model,(String)work.get("attemptId"),output).get("duplicate"));
        assertEquals(1,db.queryForObject("select count(*) from artifacts",Integer.class));
        assertThrows(ApiException.class,()->models.output(model,(String)work.get("attemptId"),new Requests.ModelResult(output.epoch(),"changed")));
    }
    @Test void modelLeaseCannotBeUsedByOtherNodeOrAfterExpiryOrCancellation() {
        Identity model=modelNode();String task=modelTask();control.tick();var work=control.poll(model);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertThrows(ApiException.class,()->models.catalog(node("sandbox",1),id,epoch));
        assertThrows(ApiException.class,()->models.catalog(owner,id,epoch));
        assertThrows(ApiException.class,()->models.catalog(model,id,epoch+1));
        assertTrue(models.catalog(model,id,epoch).isEmpty());
        control.cancel(owner,task);
        assertThrows(ApiException.class,()->models.output(model,id,new Requests.ModelResult(epoch,"late output")));
        assertEquals(0,db.queryForObject("select count(*) from artifacts",Integer.class));
    }
    @Test void modelCatalogExposesOnlyGrantedReadToolsAndRevocationApplies() {
        Identity model=modelNode();String task=modelTask();
        var write=writeTool();String writeId=(String)registry.publish(admin,new ToolRegistry.Publish(write,"1.0.0","sandbox")).get("toolVersionId");
        var read=new ToolContract("fixture.read.v1",ToolContract.Effect.READ_ONLY,write.fields(),write.credentialRef(),write.verifier());
        String readId=(String)registry.publish(admin,new ToolRegistry.Publish(read,"1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,writeId,true);registry.grant(admin,task,readId,true);control.tick();var work=control.poll(model);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var catalog=models.catalog(model,id,epoch);assertEquals(1,catalog.size());assertEquals(readId,catalog.get(0).get("toolVersionId"));
        assertFalse(json.write(catalog).contains("vault://"));registry.grant(admin,task,readId,false);assertTrue(models.catalog(model,id,epoch).isEmpty());
        time.advance(90001);assertThrows(ApiException.class,()->models.catalog(model,id,epoch));
        assertThrows(ApiException.class,()->models.output(model,id,new Requests.ModelResult(epoch,"late")));
    }
    @Test void revokedOwnerCannotBeImpersonatedThroughModelLease() {
        Identity model=modelNode();modelTask();control.tick();var work=control.poll(model);
        auth.disable(admin,owner.id());String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertThrows(ApiException.class,()->models.catalog(model,id,epoch));assertThrows(ApiException.class,()->models.output(model,id,new Requests.ModelResult(epoch,"text")));
    }

    @Test void modelFailureReleasesSlotAndRetriesWithBoundedBackoff() {
        Identity model=modelNode();String task=modelTask();control.tick();var work=control.poll(model);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var failure=new Requests.ModelFailure(epoch,Requests.ModelFailureCode.RUNTIME_FAILED);
        assertEquals(false,control.modelFailure(model,id,failure).get("duplicate"));
        assertEquals(true,control.modelFailure(model,id,failure).get("duplicate"));
        assertEquals(0,control.tick());
        assertThrows(ApiException.class,()->control.modelFailure(model,id,new Requests.ModelFailure(epoch,Requests.ModelFailureCode.OUTPUT_TOO_LARGE)));
        assertThrows(ApiException.class,()->models.output(model,id,new Requests.ModelResult(epoch,"late success")));
        time.advance(3001);assertEquals(1,control.tick());var retry=control.poll(model);
        assertNotEquals(id,retry.get("attemptId"));
        assertEquals(true,control.modelFailure(model,id,failure).get("duplicate"));
        assertEquals("RUNNING",control.getTask(owner,task).get("state"));
        assertEquals(0,db.queryForObject("select count(*) from artifacts",Integer.class));
    }
    @Test void modelFailuresReachAttemptLimitAndFailedAckCanBeReplayed() {
        Identity model=modelNode();String task=modelTask();
        for(int i=0;i<3;i++) {
            control.heartbeat(model,true);assertEquals(1,control.tick());
            var messages=control.messages(model,1);
            String message=(String)messages.get(0).get("messageId");
            var work=control.acknowledge(model,message);
            control.modelFailure(model,(String)work.get("attemptId"),new Requests.ModelFailure(((Number)work.get("epoch")).longValue(),Requests.ModelFailureCode.RUNTIME_FAILED));
            assertEquals("FAILED",control.acknowledge(model,message).get("outcome"));
            time.advance(30001);
        }
        assertEquals("FAILED",control.getTask(owner,task).get("state"));
        assertEquals(0,control.tick());
    }
    @Test void failureReportingRequiresLiveOwnedModelLease() {
        Identity model=modelNode();String task=modelTask();control.tick();var work=control.poll(model);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var failure=new Requests.ModelFailure(epoch,Requests.ModelFailureCode.RUNTIME_FAILED);
        assertThrows(ApiException.class,()->control.modelFailure(owner,id,failure));
        assertThrows(ApiException.class,()->control.modelFailure(node("sandbox",1),id,failure));
        assertThrows(ApiException.class,()->control.modelFailure(model,id,new Requests.ModelFailure(epoch+1,Requests.ModelFailureCode.RUNTIME_FAILED)));
        control.cancel(owner,task);
        assertThrows(ApiException.class,()->control.modelFailure(model,id,failure));
        assertEquals(0,db.queryForObject("select count(*) from attempts where failure_code is not null",Integer.class));
    }

    ExternalOperations boundOperations(List<GatewayRegistry.Binding> bindings) {
        return new ExternalOperations(db,auth,locks,transactions,json,time,registry,new GatewayRegistry(registry,bindings));
    }
    GatewayRegistry.Binding binding(String id,ExternalOperations.Gateway gateway) {
        return new GatewayRegistry.Binding(id,Set.of("tenant-a"),registry.fingerprint(writeTool()),gateway);
    }
    @Test void boundGatewayPersistsIdentityAndUnknownIsOnlyReconciled() {
        String task=toolTask();String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        AtomicLong sends=new AtomicLong(),lookups=new AtomicLong();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input) {sends.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String id,String hash) {
                return lookups.incrementAndGet()>1?new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"source-receipt"):
                    new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);
            }
        };
        var bound=boundOperations(List.of(binding("crm.v1",gateway)));
        var input=Map.<String,Object>of("value","x");
        String id=bound.prepareBound(owner,task,version,"crm.v1","stable",input);
        assertEquals("crm.v1",bound.read(owner,id).get("gateway_binding_id"));
        assertEquals(id,bound.prepareBound(owner,task,version,"crm.v1","stable",input));
        assertEquals("UNKNOWN",bound.executeBound(owner,id,input).get("state"));
        assertEquals("UNKNOWN",bound.executeBound(owner,id,input).get("state"));
        assertEquals(1,sends.get());assertEquals(1,lookups.get());
        assertEquals("CONFIRMED",bound.reconcileBound(owner,id).get("state"));assertEquals(2,lookups.get());
        assertThrows(ApiException.class,()->bound.executeBound(owner,id,Map.of("value","changed")));
    }
    @Test void boundGatewayCannotBeSubstitutedOrAttachToLegacyOperation() {
        String task=toolTask();String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input) {fail("Must not dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash) {throw new AssertionError();}
        };
        var bound=boundOperations(List.of(binding("crm.v1",gateway),binding("crm.v2",gateway)));
        var input=Map.<String,Object>of("value","x");
        bound.prepareBound(owner,task,version,"crm.v1","stable",input);
        assertThrows(ApiException.class,()->bound.prepareBound(owner,task,version,"crm.v2","stable",input));
        operations.prepare(owner,task,writeTool(),"legacy",input);
        assertThrows(ApiException.class,()->bound.prepareBound(owner,task,version,"crm.v1","legacy",input));
        String second=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"2.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,second,true);
        assertThrows(ApiException.class,()->bound.prepareBound(owner,task,second,"crm.v1","stable",input));
    }
    @Test void gatewayRemovalOrToolRevocationBlocksBoundExecution() {
        String task=toolTask();String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input) {fail("Must not dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash) {throw new AssertionError();}
        };
        var bound=boundOperations(List.of(binding("crm.v1",gateway)));
        var input=Map.<String,Object>of("value","x");
        String id=bound.prepareBound(owner,task,version,"crm.v1","stable",input);
        assertThrows(ApiException.class,()->boundOperations(List.of()).executeBound(owner,id,input));
        assertThrows(ApiException.class,()->bound.executeBound(other,id,input));
        registry.disable(admin,version);
        assertThrows(ApiException.class,()->bound.executeBound(owner,id,input));
        assertEquals("PREPARED",bound.read(owner,id).get("state"));
    }
    @Test void gatewayConfigurationChecksTenantContractAndDuplicateIdentity() {
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input) {}
            public ExternalOperations.Evidence lookup(String id,String hash) {return null;}
        };
        var configured=binding("crm.v1",gateway);
        assertThrows(IllegalArgumentException.class,()->new GatewayRegistry(registry,List.of(configured,configured)));
        var catalog=new GatewayRegistry(registry,List.of(configured));
        assertThrows(ApiException.class,()->catalog.resolve("tenant-b","crm.v1",writeTool()));
        var different=new ToolContract(writeTool().name(),writeTool().effect(),writeTool().fields(),"vault://different",writeTool().verifier());
        assertThrows(ApiException.class,()->catalog.resolve("tenant-a","crm.v1",different));
        assertSame(gateway,catalog.resolve("tenant-a","crm.v1",writeTool()));
    }

    @Test void realHttpCommittedWriteWithFailedReplyReconcilesWithoutResend() throws Exception {
        String task=toolTask();String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        var source=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var writes=new AtomicLong();
        var receipt=new java.util.concurrent.atomic.AtomicReference<Map<?,?>>();
        source.createContext("/operations/",exchange->{
            if(!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401,-1);exchange.close();return;
            }
            if("POST".equals(exchange.getRequestMethod())) {
                receipt.set((Map<?,?>)json.read(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
                writes.incrementAndGet();
                exchange.sendResponseHeaders(503,-1); // Commit happened, acknowledgement failed.
            } else {
                var saved=receipt.get();
                byte[] body=json.write(Map.of("operationId",saved.get("operationId"),"inputHash",saved.get("inputHash"),
                    "status","CONFIRMED","receipt","source-receipt-1")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
            exchange.close();
        });source.start();
        try {
            var gateway=new HttpReceiptGateway(java.net.URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/"),
                ()->"fixture-token",json,new com.fasterxml.jackson.databind.ObjectMapper(),1000);
            var bound=boundOperations(List.of(binding("http-source.v1",gateway)));
            var input=Map.<String,Object>of("value","x");
            String id=bound.prepareBound(owner,task,version,"http-source.v1","business-key",input);
            assertEquals("UNKNOWN",bound.executeBound(owner,id,input).get("state"));
            assertEquals("UNKNOWN",bound.executeBound(owner,id,input).get("state"));
            assertEquals(1,writes.get());
            assertEquals("CONFIRMED",bound.reconcileBound(owner,id).get("state"));
            assertEquals(1,writes.get());assertEquals("source-receipt-1",bound.read(owner,id).get("receipt_ref"));
        } finally {source.stop(0);}
    }

    String connectorTask() {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("connector-agent","1.0.0","connector.write.v1")).get("agentId");
        String task=(String)control.createTask(owner,"connector-task",new Requests.Task(agent,"sandbox",List.of("opaque connector work"))).get("taskId");
        String version=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,version,true);return task;
    }
    Identity connectorNode() {
        var node=control.registerNode(admin,new Requests.Node("connector","sandbox",1,"connector.write.v1"));
        Identity identity=auth.authenticate((String)node.get("token"));control.heartbeat(identity,true);return identity;
    }
    ConnectorExecution connector(ExternalOperations.Gateway gateway) {
        return new ConnectorExecution(control,boundOperations(List.of(binding("connector.v1",gateway))),registry,locks,db,json,auth,time,transactions);
    }
    Map<String,Object> bindConnector(ConnectorExecution execution,String task) {
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        return execution.prepare(owner,task,unit,new ConnectorExecution.Prepare(version,"connector.v1","business",Map.of("value","x")));
    }
    @Test void connectorNodeExecutesOnlyPreboundOperationAndCompletesVerifiedResult() {
        String task=connectorTask();Identity node=connectorNode();assertEquals(0,control.tick());
        AtomicLong calls=new AtomicLong();
        var execution=connector(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){assertEquals(Map.of("value","x"),input);calls.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String id,String hash){return new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"receipt");}
        });
        var bound=bindConnector(execution,task);assertEquals(bound,bindConnector(execution,task));
        assertEquals(1,control.tick());var work=control.poll(node);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertEquals("CONFIRMED",execution.execute(node,id,epoch).get("state"));
        assertEquals("CONFIRMED",execution.execute(node,id,epoch).get("state"));
        assertEquals(1,calls.get());assertEquals("SUCCEEDED",control.getTask(owner,task).get("state"));
    }
    @Test void connectorLeaseRejectsForeignNodeEpochAndReadOnlyCapability() {
        String task=connectorTask();Identity node=connectorNode();
        var execution=connector(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Unauthorized dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash){throw new AssertionError();}
        });
        bindConnector(execution,task);control.tick();var work=control.poll(node);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertThrows(ApiException.class,()->execution.execute(owner,id,epoch));
        assertThrows(ApiException.class,()->execution.execute(modelNode(),id,epoch));
        assertThrows(ApiException.class,()->execution.execute(node,id,epoch+1));
        time.advance(90001);assertThrows(ApiException.class,()->execution.execute(node,id,epoch));
    }
    @Test void unknownConnectorSurvivesNewAttemptWithoutSecondDispatch() {
        String task=connectorTask();Identity node=connectorNode();AtomicLong calls=new AtomicLong();
        var execution=connector(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){calls.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String id,String hash){return new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);}
        });
        bindConnector(execution,task);control.tick();var first=control.poll(node);
        assertEquals("UNKNOWN",execution.execute(node,(String)first.get("attemptId"),((Number)first.get("epoch")).longValue()).get("state"));
        time.advance(90001);control.heartbeat(node,true);control.tick();var second=control.poll(node);
        assertNotEquals(first.get("attemptId"),second.get("attemptId"));
        assertEquals("UNKNOWN",execution.execute(node,(String)second.get("attemptId"),((Number)second.get("epoch")).longValue()).get("state"));
        assertEquals(1,calls.get());
        control.cancel(owner,task);assertEquals("RECONCILING",control.getTask(owner,task).get("state"));
    }
    @Test void executionAuthorityRunsInsideClaimAndDenialLeavesPrepared() {
        String task=toolTask();String version=(String)registry.catalog(owner,task).get(0).get("toolVersionId");
        var bound=boundOperations(List.of(binding("connector.v1",new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Must not dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash){throw new AssertionError();}
        })));
        var input=Map.<String,Object>of("value","x");
        String id=bound.prepareBound(owner,task,version,"connector.v1","key",input);
        assertThrows(ApiException.class,()->bound.executeBound(owner,id,input,()->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            throw ApiException.forbidden();
        }));
        assertEquals("PREPARED",bound.read(owner,id).get("state"));
    }

    @Test void ownReadOnlyAttemptCannotTriggerConnectorAndHttpCannotSpoofInput() throws Exception {
        Identity model=modelNode();modelTask();control.tick();var work=control.poll(model);
        String id=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        assertEquals("CAPABILITY_MISMATCH",assertThrows(ApiException.class,()->connectors.execute(model,id,epoch)).code);
        http.perform(post("/api/v1/attempts/"+id+"/connector-execute").header("Authorization","Bearer owner-secret")
            .contentType("application/json").content(json.write(Map.of("epoch",epoch)))).andExpect(status().isForbidden());
        http.perform(post("/api/v1/attempts/"+id+"/connector-execute").header("Authorization","Bearer owner-secret")
            .contentType("application/json").content(json.write(Map.of("epoch",epoch,"input",Map.of("value","spoof"))))).andExpect(status().isBadRequest());
    }

    @Test void confirmedSourceWithoutAppliedUnitResultStillNeedsReconciliationOnCancel() {
        String task=connectorTask();
        var execution=connector(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){}
            public ExternalOperations.Evidence lookup(String id,String hash){return null;}
        });
        String operation=(String)bindConnector(execution,task).get("operationId");
        db.update("update external_operations set state='CONFIRMED',receipt_ref='receipt' where id=?",operation);
        control.cancel(owner,task);
        assertEquals("RECONCILING",control.getTask(owner,task).get("state"));
        assertEquals(0,db.queryForObject("select count(*) from unit_results",Integer.class));
    }

    Identity reviewer() {
        var user=auth.addUser(admin,"Reviewer",Set.of("RECONCILER"),Set.of("sandbox"));
        return auth.authenticate((String)user.get("token"));
    }
    OperationReconciliation reconciler(ExternalOperations.Gateway gateway) {
        return new OperationReconciliation(db,auth,locks,registry,new GatewayRegistry(registry,List.of(binding("connector.v1",gateway))),control,json,time,transactions);
    }
    @Test void organizationCanSettleAfterOwnerRevokedWithoutAnyNewDispatch() {
        String task=connectorTask();Identity node=connectorNode(),reviewer=reviewer();
        var outcome=new java.util.concurrent.atomic.AtomicReference<>(ExternalOperations.Outcome.UNKNOWN);
        AtomicLong sends=new AtomicLong();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){sends.incrementAndGet();}
            public ExternalOperations.Evidence lookup(String id,String hash){return new ExternalOperations.Evidence(outcome.get(),outcome.get()==ExternalOperations.Outcome.UNKNOWN?null:"source-proof");}
        };
        var execution=connector(gateway);String id=(String)bindConnector(execution,task).get("operationId");
        control.tick();var work=control.poll(node);execution.execute(node,(String)work.get("attemptId"),((Number)work.get("epoch")).longValue());
        var service=reconciler(gateway);
        assertThrows(ApiException.class,()->service.reconcile(reviewer,id));
        service.grant(admin,id,reviewer.id(),true);
        assertThrows(ApiException.class,()->service.read(admin,id));
        assertFalse(json.write(service.read(reviewer,id)).contains("input"));
        auth.disable(admin,owner.id());control.tick();
        outcome.set(ExternalOperations.Outcome.CONFIRMED);
        assertEquals("CONFIRMED",service.reconcile(reviewer,id).get("state"));
        assertEquals("RECONCILED",db.queryForObject("select state from tasks where id=?",String.class,task));
        assertEquals(1,db.queryForObject("select count(*) from unit_reconciliations",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from unit_results",Integer.class));
        assertEquals(1,sends.get());
        service.reconcile(reviewer,id);
        assertEquals(1,db.queryForObject("select count(*) from unit_reconciliations",Integer.class));
        assertThrows(ApiException.class,()->control.getTask(reviewer,task));
    }
    @Test void reviewerGrantRevokedDuringLookupCannotPersistEvidence() {
        String task=connectorTask();Identity reviewer=reviewer();
        final OperationReconciliation[] service=new OperationReconciliation[1];
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Lookup only");}
            public ExternalOperations.Evidence lookup(String id,String hash){
                service[0].grant(admin,id,reviewer.id(),false);
                return new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"receipt");
            }
        };
        String id=(String)bindConnector(connector(gateway),task).get("operationId");
        db.update("update external_operations set state='UNKNOWN' where id=?",id);
        control.cancel(owner,task);service[0]=reconciler(gateway);service[0].grant(admin,id,reviewer.id(),true);
        assertThrows(ApiException.class,()->service[0].reconcile(reviewer,id));
        assertEquals("UNKNOWN",db.queryForObject("select state from external_operations where id=?",String.class,id));
        assertEquals(0,db.queryForObject("select count(*) from unit_reconciliations",Integer.class));
    }
    @Test void unknownReviewCannotCloseTaskAndRejectionIsNotSuccess() {
        String task=connectorTask();Identity reviewer=reviewer();
        var outcome=new java.util.concurrent.atomic.AtomicReference<>(ExternalOperations.Outcome.UNKNOWN);
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Must not write");}
            public ExternalOperations.Evidence lookup(String id,String hash){return new ExternalOperations.Evidence(outcome.get(),outcome.get()==ExternalOperations.Outcome.UNKNOWN?null:"rejected-proof");}
        };
        String id=(String)bindConnector(connector(gateway),task).get("operationId");
        db.update("update external_operations set state='UNKNOWN' where id=?",id);
        control.cancel(owner,task);var service=reconciler(gateway);service.grant(admin,id,reviewer.id(),true);
        assertEquals("UNKNOWN",service.reconcile(reviewer,id).get("state"));
        assertEquals("RECONCILING",control.getTask(owner,task).get("state"));
        time.advance(2001);outcome.set(ExternalOperations.Outcome.REJECTED);service.reconcile(reviewer,id);
        assertEquals("RECONCILED",control.getTask(owner,task).get("state"));
        assertEquals("FAILED",db.queryForObject("select state from work_units where task_id=?",String.class,task));
        control.cancel(owner,task);assertEquals("RECONCILED",control.getTask(owner,task).get("state"));
    }
    @Test void reviewRequiresExplicitRoleZoneAndOperationGrant() {
        String task=connectorTask();Identity reviewer=reviewer();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Must not write");}
            public ExternalOperations.Evidence lookup(String id,String hash){throw new AssertionError();}
        };
        String id=(String)bindConnector(connector(gateway),task).get("operationId");var service=reconciler(gateway);
        assertThrows(ApiException.class,()->service.grant(admin,id,owner.id(),true));
        assertThrows(ApiException.class,()->service.grant(owner,id,reviewer.id(),true));
        service.grant(admin,id,reviewer.id(),true);
        assertEquals("NOT_RECONCILABLE",assertThrows(ApiException.class,()->service.reconcile(reviewer,id)).code);
        var different=auth.addUser(admin,"Wrong zone",Set.of("RECONCILER"),Set.of("other"));
        assertThrows(ApiException.class,()->service.grant(admin,id,(String)different.get("principalId"),true));
        auth.disable(admin,reviewer.id());
        assertThrows(ApiException.class,()->service.read(reviewer,id));
    }

    @Test void reconciliationQueueIsGrantScopedAndCursorBounded() {
        String task=toolTask();Identity reviewer=reviewer();
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("Listing cannot dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash){throw new AssertionError();}
        };
        var service=reconciler(gateway);List<String> ids=new ArrayList<>();
        for(int n=0;n<4;n++) {
            String id=operations.prepare(owner,task,writeTool(),"key-"+n,Map.of("value","x"));
            db.update("update external_operations set state='UNKNOWN' where id=?",id);
            if(n<3){service.grant(admin,id,reviewer.id(),true);ids.add(id);}
        }
        Collections.sort(ids);
        var page=service.list(reviewer,"",2,false);
        assertEquals(ids.get(1),page.get("nextCursor"));
        var items=(List<?>)page.get("items");assertEquals(2,items.size());
        var last=service.list(reviewer,(String)page.get("nextCursor"),2,false);
        assertEquals(1,((List<?>)last.get("items")).size());assertEquals("",last.get("nextCursor"));
        assertFalse(json.write(page).contains("input_hash"));assertFalse(json.write(page).contains("value"));
        service.grant(admin,ids.get(2),reviewer.id(),false);
        assertTrue(((List<?>)service.list(reviewer,ids.get(1),2,false).get("items")).isEmpty());
        assertThrows(ApiException.class,()->service.list(admin,"",2,true));
        assertThrows(ApiException.class,()->service.list(reviewer,"",101,false));
        assertThrows(ApiException.class,()->service.list(reviewer,"invalid/cursor",2,false));
    }
    @Test void queueRechecksCurrentZoneAndFiltersResolvedOperations() {
        String task=toolTask();Identity reviewer=reviewer();
        var service=reconciler(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){}
            public ExternalOperations.Evidence lookup(String id,String hash){return null;}
        });
        String id=operations.prepare(owner,task,writeTool(),"key",Map.of("value","x"));
        service.grant(admin,id,reviewer.id(),true);
        db.update("update external_operations set state='CONFIRMED',receipt_ref='proof' where id=?",id);
        assertTrue(((List<?>)service.list(reviewer,"",10,false).get("items")).isEmpty());
        assertEquals(1,((List<?>)service.list(reviewer,"",10,true).get("items")).size());
        db.update("update tasks set zone='other' where id=?",task);
        assertTrue(((List<?>)service.list(reviewer,"",10,true).get("items")).isEmpty());
    }

    @Test void concurrentReconciliationUsesOneSharedQueryLease() throws Exception {
        String task=toolTask();Identity reviewer=reviewer();AtomicLong lookups=new AtomicLong();
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var gateway=new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("No dispatch");}
            public ExternalOperations.Evidence lookup(String id,String hash)throws Exception {
                lookups.incrementAndGet();entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));
                return new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);
            }
        };
        String id=operations.prepare(owner,task,writeTool(),"key",Map.of("value","x"));
        db.update("update external_operations set state='UNKNOWN',gateway_binding_id='connector.v1' where id=?",id);
        var first=reconciler(gateway);var second=reconciler(gateway);
        first.grant(admin,id,reviewer.id(),true);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->first.reconcile(reviewer,id));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(429,assertThrows(ApiException.class,()->second.reconcile(reviewer,id)).status);
            finish.countDown();assertEquals("UNKNOWN",pending.get(5,TimeUnit.SECONDS).get("state"));
            assertEquals(429,assertThrows(ApiException.class,()->second.reconcile(reviewer,id)).status);
            assertEquals(1,lookups.get());
        } finally {finish.countDown();pool.shutdownNow();}
    }
    @Test void scanSkipsBackoffRowsInsteadOfBlockingLaterWork() {
        String task=toolTask();Identity reviewer=reviewer();AtomicLong queries=new AtomicLong();
        var service=reconciler(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("No writes");}
            public ExternalOperations.Evidence lookup(String id,String hash){queries.incrementAndGet();return null;}
        });
        for(int n=0;n<3;n++) {
            String id=operations.prepare(owner,task,writeTool(),"key-"+n,Map.of("value","x"));
            db.update("update external_operations set state='UNKNOWN',gateway_binding_id='connector.v1' where id=?",id);
            service.grant(admin,id,reviewer.id(),true);
        }
        assertEquals(2,((List<?>)service.scan(reviewer,2).get("items")).size());
        assertEquals(1,((List<?>)service.scan(reviewer,2).get("items")).size());
        assertTrue(((List<?>)service.scan(reviewer,2).get("items")).isEmpty());assertEquals(3,queries.get());
        time.advance(2001);assertEquals(2,((List<?>)service.scan(reviewer,2).get("items")).size());
        assertThrows(ApiException.class,()->service.scan(reviewer,11));
    }
    @Test void lateQueryEvidenceIsFencedAndExpiredLeaseCanRecover() {
        String task=toolTask();Identity reviewer=reviewer();AtomicLong calls=new AtomicLong();
        var service=reconciler(new ExternalOperations.Gateway() {
            public void dispatch(String id,Map<String,Object> input){fail("No writes");}
            public ExternalOperations.Evidence lookup(String id,String hash) {
                if(calls.incrementAndGet()==1)time.advance(90001);
                return new ExternalOperations.Evidence(ExternalOperations.Outcome.CONFIRMED,"source-proof");
            }
        });
        String id=operations.prepare(owner,task,writeTool(),"key",Map.of("value","x"));
        db.update("update external_operations set state='UNKNOWN',gateway_binding_id='connector.v1' where id=?",id);
        service.grant(admin,id,reviewer.id(),true);
        assertEquals("STALE_RECONCILIATION",assertThrows(ApiException.class,()->service.reconcile(reviewer,id)).code);
        assertEquals("UNKNOWN",db.queryForObject("select state from external_operations where id=?",String.class,id));
        time.advance(2001);assertEquals("CONFIRMED",service.reconcile(reviewer,id).get("state"));
        assertEquals(2,db.queryForObject("select generation from reconciliation_runs where operation_id=?",Integer.class,id));
    }

    @Test void defaultReadToolsAreAdminScopedAndAtomicWithTaskCreation() throws Exception {
        String agent=(String)control.publishAgent(admin,new Requests.Agent("default-model","1.0.0","model.read.v1")).get("agentId");
        var contract=new ToolContract("artifact.list.v1",ToolContract.Effect.READ_ONLY,Map.of(),"vault://platform/lease","artifact.metadata.v1");
        String tool=(String)registry.publish(admin,new ToolRegistry.Publish(contract,"1.0.0","sandbox")).get("toolVersionId");
        String otherZone=(String)registry.publish(admin,new ToolRegistry.Publish(contract,"1.0.1","other")).get("toolVersionId");
        assertThrows(ApiException.class,()->registry.agentDefault(owner,agent,tool,true));
        assertThrows(ApiException.class,()->registry.agentDefault(admin,agentId,tool,true));
        String write=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")).get("toolVersionId");
        assertThrows(ApiException.class,()->registry.agentDefault(admin,agent,write,true));
        http.perform(put("/api/v1/agent-definitions/"+agent+"/tools/"+tool).header("Authorization","Bearer admin-secret")).andExpect(status().isNoContent());
        registry.agentDefault(admin,agent,tool,true);
        registry.agentDefault(admin,agent,otherZone,true);
        var request=new Requests.Task(agent,"sandbox",List.of("read attachments"));
        String task=(String)control.createTask(owner,"defaults",request).get("taskId");
        assertEquals(List.of(tool),registry.catalog(owner,task).stream().map(r->r.get("toolVersionId")).toList());
        registry.agentDefault(admin,agent,tool,false);
        assertEquals(task,control.createTask(owner,"defaults",request).get("taskId"));
        assertEquals(1,registry.catalog(owner,task).size());
        String next=(String)control.createTask(owner,"defaults-next",request).get("taskId");
        assertTrue(registry.catalog(owner,next).isEmpty());
        registry.agentDefault(admin,agent,tool,true);
        registry.disable(admin,tool);
        assertTrue(registry.catalog(owner,task).isEmpty());
        String disabled=(String)control.createTask(owner,"defaults-disabled",request).get("taskId");
        assertTrue(registry.catalog(owner,disabled).isEmpty());
    }

    @Test void defaultToolPagesAreScopedAndExposeNoCredentialContracts() throws Exception {
        auth.create("config-admin","tenant-a","USER","Config admin",Set.of("ADMIN"),Set.of("sandbox"),"config-secret");
        assertFalse(control.availableAgents(auth.authenticate("config-secret")).isEmpty());
        String agent=(String)control.publishAgent(admin,new Requests.Agent("page-model","1.0.0","model.read.v1")).get("agentId");
        var read=new ToolContract("fixture.default.read",ToolContract.Effect.READ_ONLY,Map.of(),"vault://hidden/credential","hidden.verifier");
        String a=(String)registry.publish(admin,new ToolRegistry.Publish(read,"1.0.0","sandbox")).get("toolVersionId");
        String b=(String)registry.publish(admin,new ToolRegistry.Publish(read,"1.0.1","sandbox")).get("toolVersionId");
        String c=(String)registry.publish(admin,new ToolRegistry.Publish(writeTool(),"1.0.0","sandbox")).get("toolVersionId");
        registry.publish(admin,new ToolRegistry.Publish(read,"1.0.2","other"));
        registry.agentDefault(admin,agent,a,true);registry.disable(admin,a);
        var expected=new HashSet<>(List.of(a,b,c));String after="";int pages=0;
        do {
            var page=registry.agentTools(admin,agent,"sandbox",after,1);
            var rows=(List<Map<String,Object>>)page.get("items");assertEquals(1,rows.size());
            var item=rows.get(0);assertTrue(expected.remove(item.get("toolVersionId")));
            assertEquals(item.get("toolVersionId").equals(a),item.get("configured"));
            assertEquals(item.get("toolVersionId").equals(b),item.get("eligible"));
            assertFalse(json.write(page).contains("vault://"));assertFalse(json.write(page).contains("verifier"));
            after=(String)page.get("nextCursor");pages++;
        } while(after!=null);
        assertEquals(3,pages);assertTrue(expected.isEmpty());
        assertThrows(ApiException.class,()->registry.agentTools(owner,agent,"sandbox","",10));
        assertThrows(ApiException.class,()->registry.agentTools(admin,agent,"unauthorized","",10));
        assertThrows(ApiException.class,()->registry.agentTools(admin,agent,"sandbox","x&y",10));
        assertThrows(ApiException.class,()->registry.agentTools(admin,agent,"sandbox","",101));
        auth.create("foreign-admin","tenant-b","USER","Foreign",Set.of("ADMIN"),Set.of("sandbox"),"foreign-secret");
        assertThrows(ApiException.class,()->registry.agentTools(auth.authenticate("foreign-secret"),agent,"sandbox","",10));
        http.perform(get("/api/v1/agent-definitions/"+agent+"/tools?zone=sandbox&limit=1").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test void capabilityPagesDiscoverBeyondLegacyLimitAndRecheckAccess() throws Exception {
        Set<Object> ids=new HashSet<>();
        for(int i=0;i<105;i++)ids.add(control.publishAgent(admin,new Requests.Agent("model-"+i,"1.0.0","model.read.v1")).get("agentId"));
        String hidden=(String)control.publishAgent(admin,new Requests.Agent("hidden","1.0.0","model.read.v1")).get("agentId");
        db.update("update agent_versions set status='DISABLED' where id=?",hidden);
        assertEquals(100,control.availableAgents(owner).size());
        String cursor="";int count=0;
        do {
            var page=control.agentPage(owner,cursor,50,"model.read.v1");
            var items=(List<Map<String,Object>>)page.get("items");
            for(var item:items){assertTrue(ids.remove(item.get("id")));count++;assertEquals("model.read.v1",item.get("capability"));}
            cursor=(String)page.get("nextCursor");
        } while(cursor!=null);
        assertEquals(105,count);assertTrue(ids.isEmpty());
        assertEquals(1,((List<?>)control.agentPage(owner,"",100,"text.stats.v1").get("items")).size());
        assertThrows(ApiException.class,()->control.agentPage(owner,"x&tenant=y",50,""));
        assertThrows(ApiException.class,()->control.agentPage(owner,"",101,""));
        assertThrows(ApiException.class,()->control.agentPage(owner,"",50,"unknown"));
        auth.create("page-other","tenant-b","USER","Other",Set.of("VIEWER"),Set.of("sandbox"),"page-other-token");
        assertTrue(((List<?>)control.agentPage(auth.authenticate("page-other-token"),"",50,"").get("items")).isEmpty());
        auth.create("page-reviewer","tenant-a","USER","Reviewer",Set.of("RECONCILER"),Set.of("sandbox"),"page-reviewer-token");
        assertThrows(ApiException.class,()->control.agentPage(auth.authenticate("page-reviewer-token"),"",50,""));
        http.perform(get("/api/v1/agent-definitions/page?capability=model.read.v1&limit=1").header("Authorization","Bearer owner-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        auth.disable(admin,owner.id());
        assertThrows(ApiException.class,()->control.agentPage(owner,"",50,""));
    }

    @Test void builtinTemplatesPublishFixedReadOnlyContractsWithoutResurrectingDisabledVersions() throws Exception {
        assertEquals(10,registry.templates(admin).size());
        assertThrows(ApiException.class,()->registry.templates(owner));
        var request=new ToolRegistry.TemplatePublish("1.0.0","sandbox");
        assertThrows(ApiException.class,()->registry.publishTemplate(owner,"text-read",request));
        assertThrows(ApiException.class,()->registry.publishTemplate(admin,"unknown",request));
        assertThrows(ApiException.class,()->registry.publishTemplate(admin,"text-read",new ToolRegistry.TemplatePublish("1.0.0","outside")));
        for(var template:BuiltinToolTemplates.ALL) {
            var first=registry.publishTemplate(admin,template.id(),request);
            assertEquals(false,first.get("reused"));
            var again=registry.publishTemplate(admin,template.id(),request);
            assertEquals(first.get("toolVersionId"),again.get("toolVersionId"));assertEquals(true,again.get("reused"));
            assertEquals(BuiltinToolTemplates.isOfficeWrite(template.contract())?ToolContract.Effect.ARTIFACT_WRITE:ToolContract.Effect.READ_ONLY,template.contract().effect());
            assertEquals(registry.fingerprint(template.contract()),db.queryForObject("select contract_hash from tool_versions where id=?",String.class,first.get("toolVersionId")));
        }
        assertEquals(Set.of("leftId","rightId","leftVersion","rightVersion","leftKey","rightKey","leftColumn","rightColumn","offset","limit"),BuiltinToolTemplates.get("csv-compare").contract().fields().keySet());
        String id=(String)registry.publishTemplate(admin,"text-read",request).get("toolVersionId");
        registry.disable(admin,id);
        assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(ApiException.class,()->registry.publishTemplate(admin,"text-read",request)).code);
        assertThrows(ApiException.class,()->registry.publishTemplate(admin,"csv-read",new ToolRegistry.TemplatePublish("1.0.0","other")));
        var collision=new ToolContract("artifact.read_text.v1",ToolContract.Effect.READ_ONLY,Map.of(),"vault://different/ref","different.verify");
        registry.publish(admin,new ToolRegistry.Publish(collision,"2.0.0","sandbox"));
        assertThrows(ApiException.class,()->registry.publishTemplate(admin,"text-read",new ToolRegistry.TemplatePublish("2.0.0","sandbox")));
        http.perform(post("/api/v1/tool-templates/text-read/publish").header("Authorization","Bearer admin-secret").contentType("application/json").content("{\"version\":\"3.0.0\",\"zone\":\"sandbox\"}")).andExpect(status().isOk());
        http.perform(post("/api/v1/tool-templates/text-read/publish").header("Authorization","Bearer admin-secret").contentType("application/json").content("{\"version\":\"bad\",\"zone\":\"sandbox\"}")).andExpect(status().isBadRequest());
    }

    @Test void knowledgeTemplatesKeepSearchAndCitationReadAsSeparateExplicitGrants() {
        var publish=new ToolRegistry.TemplatePublish("1.0.0","sandbox");
        String search=(String)registry.publishTemplate(admin,"knowledge-search",publish).get("toolVersionId");
        String read=(String)registry.publishTemplate(admin,"knowledge-read",publish).get("toolVersionId");
        assertNotEquals(search,read);
        var searchContract=BuiltinToolTemplates.get("knowledge-search").contract();
        var readContract=BuiltinToolTemplates.get("knowledge-read").contract();
        assertEquals(Set.of("query","limit"),searchContract.fields().keySet());
        assertEquals(Set.of("sourceId","sha256","offset","limit"),readContract.fields().keySet());
        assertEquals(128,searchContract.fields().get("query").maxLength());
        assertEquals(120,readContract.fields().get("sourceId").maxLength());
        assertEquals(64,readContract.fields().get("sha256").maxLength());
        assertThrows(ApiException.class,()->searchContract.validate(Map.of("query","x".repeat(129),"limit",1)));
        assertThrows(ApiException.class,()->readContract.validate(Map.of("sourceId","doc","sha256","a".repeat(64),"offset",0,"limit",1,"url","https://other")));
        assertEquals(0,db.queryForObject("select count(*) from task_tool_grants where tool_version_id in (?,?)",Integer.class,search,read));
        assertEquals(0,db.queryForObject("select count(*) from task_document_bindings where tool_version_id in (?,?)",Integer.class,search,read));
        registry.disable(admin,read);
        assertThrows(ApiException.class,()->registry.publishTemplate(admin,"knowledge-read",publish));
        assertEquals(true,registry.publishTemplate(admin,"knowledge-search",publish).get("reused"));
    }

    @Test void documentCandidatesUseTaskGrantsAndScopedNodeManifests() throws Exception {
        String task=modelTask();Identity first=modelNode(),second=modelNode();
        String read=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,read,true);
        String ungranted=(String)registry.publishTemplate(admin,"knowledge-read",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        var tools=(List<?>)documents.toolCandidates(admin,task,"",50).get("items");assertEquals(1,tools.size());assertEquals(read,((Map<?,?>)tools.get(0)).get("id"));assertFalse(json.write(tools).contains(ungranted));assertFalse(json.write(tools).contains("vault://"));
        control.reportSourceDeployments(first,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","a".repeat(64)),new Requests.NodeSource(other.id(),"private-other","c".repeat(64)))));
        control.reportSourceDeployments(second,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","b".repeat(64)))));
        var firstPage=documents.sourceCandidates(admin,task,"","",1);assertEquals(1,((List<?>)firstPage.get("items")).size());assertEquals("a".repeat(64),firstPage.get("nextHash"));
        var secondPage=documents.sourceCandidates(admin,task,(String)firstPage.get("nextSource"),(String)firstPage.get("nextHash"),1);assertEquals(1,((List<?>)secondPage.get("items")).size());assertFalse(secondPage.containsKey("nextSource"));
        assertFalse(json.write(firstPage).contains("private-other"));
        assertThrows(ApiException.class,()->documents.sourceCandidates(owner,task,"","",50));
        assertThrows(ApiException.class,()->documents.sourceCandidates(admin,task,"kb","",50));
        auth.create("outside-candidates","tenant-b","USER","Outside",Set.of("ADMIN"),Set.of("sandbox"),"outside-candidates-token");
        assertThrows(ApiException.class,()->documents.sourceCandidates(auth.authenticate("outside-candidates-token"),task,"","",50));
        db.update("update nodes set zone='other' where id=?",second.id());
        assertEquals(1,((List<?>)documents.sourceCandidates(admin,task,"","",50).get("items")).size());
        auth.disable(admin,first.id());assertEquals(List.of(),documents.sourceCandidates(admin,task,"","",50).get("items"));
        documents.bind(admin,task,read,new DocumentBindings.Binding("kb","a".repeat(64)));assertEquals(List.of(),documents.toolCandidates(admin,task,"",50).get("items"));
        http.perform(get("/api/v1/tasks/"+task+"/documents/tool-candidates").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void documentInspectionRetainsRevokedMetadataWithoutReturningTaskOrCredentials() throws Exception {
        String task=modelTask();
        String tool=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,tool,true);
        documents.bind(admin,task,tool,new DocumentBindings.Binding("kb","a".repeat(64)));
        var snapshot=documents.inspect(admin,task);
        assertEquals(owner.id(),snapshot.get("executionPrincipalId"));
        assertEquals("RUNNING",snapshot.get("taskState"));
        var item=(Map<?,?>)((List<?>)snapshot.get("items")).get(0);
        assertEquals(true,item.get("bindingEnabled"));assertEquals(true,item.get("taskGranted"));assertEquals(true,item.get("toolEnabled"));
        assertFalse(json.write(snapshot).contains("model prompt"));assertFalse(json.write(snapshot).contains("vault://"));
        registry.grant(admin,task,tool,false);documents.revoke(admin,task,tool);registry.disable(admin,tool);
        control.cancel(owner,task);
        var after=documents.inspect(admin,task);var revoked=(Map<?,?>)((List<?>)after.get("items")).get(0);
        assertEquals(false,revoked.get("bindingEnabled"));assertEquals(false,revoked.get("taskGranted"));assertEquals(false,revoked.get("toolEnabled"));
        assertEquals("kb",revoked.get("sourceId"));assertEquals("CANCELLED",after.get("taskState"));
        http.perform(get("/api/v1/tasks/"+task+"/documents").header("Authorization","Bearer admin-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void documentInspectionRequiresCurrentAdministratorTenantAndZone() {
        String task=modelTask();
        assertThrows(ApiException.class,()->documents.inspect(owner,task));
        assertThrows(ApiException.class,()->documents.inspect(modelNode(),task));
        auth.create("outside-admin","tenant-b","USER","Outside",Set.of("ADMIN"),Set.of("sandbox"),"outside-secret");
        auth.create("zone-admin","tenant-a","USER","Zone",Set.of("ADMIN"),Set.of("other"),"zone-secret");
        assertThrows(ApiException.class,()->documents.inspect(auth.authenticate("outside-secret"),task));
        assertThrows(ApiException.class,()->documents.inspect(auth.authenticate("zone-secret"),task));
        assertThrows(ApiException.class,()->documents.inspect(admin,"missing"));
        auth.disable(admin,admin.id());assertThrows(ApiException.class,()->documents.inspect(admin,task));
    }

    @Test void initiallyPausedTaskCannotDispatchBeforeSourceSetupAndResumeDoesNotChangeCreateReceipt() {
        Identity node=modelNode();
        String agent=(String)control.publishAgent(admin,new Requests.Agent("held-model","1.0.0","model.read.v1")).get("agentId");
        var request=new Requests.Task(agent,"sandbox",List.of("wait for knowledge"),List.of(),List.of(),true);
        String task=(String)control.createTask(owner,"held-create",request).get("taskId");
        control.tick();assertEquals(0,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id where u.task_id=?",Integer.class,task));
        assertEquals(true,db.queryForObject("select dispatch_paused from tasks where id=?",Boolean.class,task));
        String tool=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,tool,true);documents.bind(admin,task,tool,new DocumentBindings.Binding("kb","a".repeat(64)));
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","a".repeat(64)))));
        control.taskAdmission(owner,task,false);control.tick();var work=control.poll(node);
        assertEquals("WORK",work.get("status"));
        assertEquals(task,db.queryForObject("select task_id from work_units where id=?",String.class,work.get("unitId")));
        var scope=documents.scope(node,(String)work.get("attemptId"),((Number)work.get("epoch")).longValue());
        assertEquals(1,((List<?>)scope.get("bindings")).size());
        assertEquals(true,control.createTask(owner,"held-create",request).get("reused"));
        assertEquals(false,db.queryForObject("select dispatch_paused from tasks where id=?",Boolean.class,task));
        assertThrows(ApiException.class,()->control.createTask(owner,"held-create",new Requests.Task(agent,"sandbox",List.of("wait for knowledge"))));
    }

    @Test void nodeSourceManifestIsAtomicIdentityScopedAndCannotGrantAccess() throws Exception {
        Identity node=modelNode(),another=modelNode();
        var source=new Requests.NodeSource(owner.id(),"kb","a".repeat(64));
        var body=new Requests.NodeSources(List.of(source));
        assertEquals(true,control.reportSourceDeployments(node,body).get("updated"));
        assertEquals(false,control.reportSourceDeployments(node,body).get("updated"));
        assertEquals(List.of(),control.sourceDeployments(another).get("sources"));
        assertEquals(0,db.queryForObject("select count(*) from task_tool_grants",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from task_document_bindings",Integer.class));
        assertThrows(ApiException.class,()->control.reportSourceDeployments(node,new Requests.NodeSources(List.of(source,source))));
        assertThrows(ApiException.class,()->control.reportSourceDeployments(node,new Requests.NodeSources(List.of(new Requests.NodeSource("missing","kb","b".repeat(64))))));
        assertEquals(1,((List<?>)control.sourceDeployments(node).get("sources")).size());
        assertThrows(ApiException.class,()->control.reportSourceDeployments(admin,body));
        assertThrows(ApiException.class,()->control.sourceDeployments(owner));
        http.perform(put("/api/v1/nodes/source-deployments").header("Authorization","Bearer "+auth.newToken()).contentType("application/json").content(json.write(body))).andExpect(status().isUnauthorized());
        assertEquals(true,control.reportSourceDeployments(node,new Requests.NodeSources(List.of())).get("updated"));
        assertEquals(List.of(),control.sourceDeployments(node).get("sources"));
        auth.disable(admin,node.id());assertThrows(ApiException.class,()->control.reportSourceDeployments(node,body));
    }
    @Test void sourceManifestRejectsOtherTenantAndWrongNodeCapability() {
        Identity model=modelNode();
        auth.create("foreign-user","tenant-b","USER","Foreign",Set.of("OPERATOR"),Set.of("sandbox"),"foreign-token");
        assertThrows(ApiException.class,()->control.reportSourceDeployments(model,new Requests.NodeSources(List.of(new Requests.NodeSource("foreign-user","kb","a".repeat(64))))));
        var created=control.registerNode(admin,new Requests.Node("text-only","sandbox",1));
        Identity text=auth.authenticate((String)created.get("token"));
        assertThrows(ApiException.class,()->control.reportSourceDeployments(text,new Requests.NodeSources(List.of())));
    }

    @Test void sourceRoutingMatchesAllDeploymentsAndSharesCapacityAcrossTaskPools() {
        Identity prepared=modelNode(),wrong=modelNode();
        control.reportSourceDeployments(prepared,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","a".repeat(64)))));
        control.reportSourceDeployments(wrong,new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","b".repeat(64)))));
        String agent=(String)control.publishAgent(admin,new Requests.Agent("source-routing","1.0.0","model.read.v1")).get("agentId");
        String tool=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        var bound=new ArrayList<String>();
        for(int i=0;i<3;i++) {
            String task=(String)control.createTask(owner,"source-"+i,new Requests.Task(agent,"sandbox",List.of("source task"))).get("taskId");
            registry.grant(admin,task,tool,true);documents.bind(admin,task,tool,new DocumentBindings.Binding("kb","a".repeat(64)));bound.add(task);time.advance(1);
        }
        String plain=(String)control.createTask(owner,"plain",new Requests.Task(agent,"sandbox",List.of("plain task"))).get("taskId");
        control.tick();
        assertEquals(1,db.queryForObject("select count(*) from attempts where node_id=? and state='RESERVED'",Integer.class,prepared.id()));
        assertEquals(1,db.queryForObject("select count(*) from attempts where node_id=? and state='RESERVED'",Integer.class,wrong.id()));
        assertEquals(plain,db.queryForObject("select u.task_id from attempts a join work_units u on u.id=a.unit_id where a.node_id=? and a.state='RESERVED'",String.class,wrong.id()));
        assertTrue(bound.contains(db.queryForObject("select u.task_id from attempts a join work_units u on u.id=a.unit_id where a.node_id=? and a.state='RESERVED'",String.class,prepared.id())));
        assertEquals(2,db.queryForObject("select count(*) from work_units where state='READY'",Integer.class));
    }
    @Test void sourceManifestRemovalYieldsUnstartedWorkButDoesNotCancelRunningWork() {
        Identity node=modelNode();String task=modelTask();
        String tool=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,tool,true);documents.bind(admin,task,tool,new DocumentBindings.Binding("kb","a".repeat(64)));
        var manifest=new Requests.NodeSources(List.of(new Requests.NodeSource(owner.id(),"kb","a".repeat(64))));
        control.reportSourceDeployments(node,manifest);control.tick();
        String old=db.queryForObject("select id from attempts where state='RESERVED'",String.class);
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of()));
        assertEquals("YIELDED",db.queryForObject("select state from attempts where id=?",String.class,old));
        assertEquals(0,db.queryForObject("select attempts from work_units where task_id=?",Integer.class,task));
        assertEquals("CANCELLED",db.queryForObject("select state from node_dispatch where attempt_id=?",String.class,old));
        time.advance(1001);control.reportSourceDeployments(node,manifest);control.tick();var work=control.poll(node);
        assertEquals("WORK",work.get("status"));assertNotEquals(old,work.get("attemptId"));
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of()));
        assertEquals("RUNNING",db.queryForObject("select state from attempts where id=?",String.class,work.get("attemptId")));
        control.renew(node,(String)work.get("attemptId"),((Number)work.get("epoch")).longValue());
    }
    @Test void lateSourceGrantCannotStartOnAnUnpreparedNodeAndNextTickRefundsReservation() {
        Identity node=modelNode();String task=modelTask();
        String tool=(String)registry.publishTemplate(admin,"knowledge-search",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,tool,true);documents.bind(admin,task,tool,new DocumentBindings.Binding("kb","a".repeat(64)));
        registry.grant(admin,task,tool,false);control.tick();
        registry.grant(admin,task,tool,true);
        assertEquals("SOURCE_DEPLOYMENT_MISMATCH",assertThrows(ApiException.class,()->control.poll(node)).code);
        control.tick();assertEquals(0,db.queryForObject("select attempts from work_units where task_id=?",Integer.class,task));
        assertEquals(0,db.queryForObject("select count(*) from attempts where state in ('RESERVED','RUNNING')",Integer.class));
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of(new Requests.NodeSource(other.id(),"kb","a".repeat(64)))));time.advance(1001);control.tick();
        assertEquals("NO_WORK",control.poll(node).get("status"));
        String read=(String)registry.publishTemplate(admin,"knowledge-read",new ToolRegistry.TemplatePublish("1.0.0","sandbox")).get("toolVersionId");
        registry.grant(admin,task,read,true);documents.bind(admin,task,read,new DocumentBindings.Binding("kb2","c".repeat(64)));
        var first=new Requests.NodeSource(owner.id(),"kb","a".repeat(64));
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of(first)));time.advance(1001);control.tick();
        assertEquals("NO_WORK",control.poll(node).get("status"));
        control.reportSourceDeployments(node,new Requests.NodeSources(List.of(first,new Requests.NodeSource(owner.id(),"kb2","c".repeat(64)))));time.advance(1001);control.tick();
        assertEquals("WORK",control.poll(node).get("status"));
    }

}
