package com.funnywork;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:computerhttp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
    "funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","server.address=127.0.0.1"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ComputerHttpIntegrationTest {
    @Autowired AuthService auth;
    @Autowired ControlService control;
    @Autowired ComputerGrants grants;
    @Autowired JdbcTemplate db;
    @Autowired Json json;
    @Autowired Clock clock;
    @LocalServerPort int port;

    @Test void pythonDesktopUsesCentralLedgerAndReleasesAfterStop() throws Exception {scenario("success");}
    @Test void lostCommittedClaimResponseNeverReachesNativeInput() throws Exception {scenario("lost-claim");}
    @Test void revocationBetweenNativeBatchesRetainsUnknown() throws Exception {scenario("revoke");}

    @Test void pythonDesktopRenewsCentralLeaseAndRefreshesObservation() throws Exception {scenario("renew");}

    @Test void durablePythonNodeConsumesDispatchAndRecoversLostReportResponse() throws Exception {scenario("worker");}
    @Test void killedPythonNodeReopensJournalWithoutInputOrControlRelease() throws Exception {scenario("worker-crash");}

    void scenario(String mode) throws Exception {
        String tenant="computer-http-"+AuthService.id(),token=UUID.randomUUID().toString();
        auth.create(AuthService.id(),tenant,"USER","Fixture owner",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("sandbox"),token);
        var admin=auth.authenticate(token);
        String agent=(String)control.publishAgent(admin,new Requests.Agent("desktop-http","1.0.0",Capabilities.COMPUTER)).get("agentId");
        String task=(String)control.createTask(admin,"desktop-http",new Requests.Task(agent,"sandbox",List.of("fixture"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        var node=control.registerNode(admin,new Requests.Node("Fixture node","sandbox",1,Capabilities.COMPUTER));
        String nodeId=(String)node.get("nodeId"),nodeToken=(String)node.get("token");
        grants.grant(admin,task,unit,new ComputerGrants.Grant(nodeId,"1","123:456","789",Set.of("click","fill"),8,60));
        control.heartbeat(auth.authenticate(nodeToken),true);
        // Deliberate test fixture: production scheduling still excludes computer.write.v1.
        String attempt=AuthService.id();long now=clock.millis();
        db.update("update work_units set state='RUNNING',epoch=1,attempts=1 where id=?",unit);
        db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,1,'RUNNING',?,?,?)",attempt,unit,nodeId,now+90000,now,tenant);
        if(mode.startsWith("worker"))db.update("insert into node_dispatch(id,tenant_id,node_id,attempt_id,state,deliveries,available_at,created_at) values(?,?,?,?,'PENDING',0,?,?)",AuthService.id(),tenant,nodeId,attempt,now,now);
        Path stateDir=Files.createTempDirectory("desktop-worker-state-");
        Path secret=Files.createTempFile("computer-http-",".json"),log=Files.createTempFile("computer-http-",".log");
        Process process=null;
        try {
            Files.writeString(secret,json.write(Map.of("adminToken",token,"nodeToken",nodeToken,"task",task,"unit",unit,"attempt",attempt,"mode",mode,"stateDir",stateDir.toString())));
            Path script=Path.of(System.getProperty("funnywork.http-scenarios","../scripts"),mode.startsWith("worker")?"computer_worker_http_scenario.py":"computer_http_scenario.py").toAbsolutePath();
            process=new ProcessBuilder("python3",script.toString(),"http://127.0.0.1:"+port,secret.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(25,TimeUnit.SECONDS),"Desktop HTTP scenario timed out");
            if(mode.equals("worker-crash")){
                assertEquals(73,process.exitValue(),Files.readString(log));
                process=new ProcessBuilder("python3",script.toString(),"http://127.0.0.1:"+port,secret.toString(),"--recover")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Desktop recovery timed out");
                assertEquals(0,process.exitValue(),Files.readString(log));
                assertEquals("ACTIVE",db.queryForObject("select state from computer_controls where attempt_id=?",String.class,attempt));
                assertEquals(attempt,db.queryForObject("select current_attempt from computer_sessions where tenant_id=?",String.class,tenant));
                assertEquals("DRAINING",db.queryForObject("select status from nodes where id=?",String.class,nodeId));
                assertEquals(1,db.queryForObject("select count(*) from computer_actions where unit_id=?",Integer.class,unit));
                assertEquals(0,db.queryForObject("select count(*) from task_artifacts where task_id=?",Integer.class,task));
                return;
            }
            assertEquals(0,process.exitValue(),Files.readString(log));
            assertEquals("RELEASED",db.queryForObject("select state from computer_controls where attempt_id=?",String.class,attempt));
            assertNull(db.queryForObject("select current_attempt from computer_sessions where tenant_id=?",String.class,tenant));
            assertEquals(1,db.queryForObject("select count(*) from computer_actions where unit_id=?",Integer.class,unit));
            assertEquals(Set.of("success","renew","worker").contains(mode)?"DISPATCHED":"UNKNOWN",db.queryForObject("select state from computer_actions where unit_id=?",String.class,unit));
            assertEquals("RECONCILING",db.queryForObject("select state from tasks where id=?",String.class,task));
            assertEquals("GENERATED",db.queryForObject("select state from work_units where id=?",String.class,unit));
            assertEquals(1,db.queryForObject("select count(*) from task_artifacts where task_id=?",Integer.class,task));
        } finally {
            if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor();}
            Files.deleteIfExists(secret);Files.deleteIfExists(log);
            for(String name:List.of("node.db","node.db-journal","node.db-wal","node.db-shm"))Files.deleteIfExists(stateDir.resolve(name));
            Files.deleteIfExists(stateDir);
        }
    }
}
