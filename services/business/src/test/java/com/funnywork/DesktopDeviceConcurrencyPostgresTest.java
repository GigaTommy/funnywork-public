package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PostgreSQL row/FK locks cannot be reproduced by H2's locking model. Use an owned test database. */
@EnabledIfEnvironmentVariable(named="FUNNYWORK_TEST_POSTGRES_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(properties={
    "spring.datasource.url=${FUNNYWORK_TEST_POSTGRES_URL}",
    "spring.datasource.username=${FUNNYWORK_TEST_POSTGRES_USER:postgres}",
    "spring.datasource.password=${FUNNYWORK_TEST_POSTGRES_PASSWORD:}",
    "funnywork.bootstrap-token=", "funnywork.scheduling-enabled=false",
    "funnywork.model-inference-enabled=false"})
class DesktopDeviceConcurrencyPostgresTest {
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired DesktopHistorySync sync;
    @Autowired DesktopInteractions interactions;
    @Autowired DesktopAttachments attachments;
    @Autowired MaintenanceDesktopChannel desktops;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean DesktopNotifications notifications;
    Identity owner;
    String device;

    @BeforeEach void setup(){
        String tenant=AuthService.id(),token=AuthService.id();
        auth.create(AuthService.id(),tenant,"USER","Concurrent fixture",Set.of("ADMIN","VIEWER"),Set.of("sandbox"),token);
        owner=auth.authenticate(token);device=AuthService.id();
        sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Desktop","win32"));
    }

    void await(CountDownLatch latch){
        try{assertTrue(latch.await(10,TimeUnit.SECONDS),"Concurrent transaction did not reach checkpoint");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
    }

    void awaitBlockedBy(int blocked,int blocker){
        assertTimeoutPreemptively(Duration.ofSeconds(10),()->{
            while(!Boolean.TRUE.equals(db.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,blocker,blocked)))
                Thread.sleep(10);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"publish","heartbeat","exchange"})
    void registrationAndStatePublicationUseTheSameOwnerDeviceOrder(String entry)throws Exception {
        var deviceLocked=new CountDownLatch(1);var releaseState=new CountDownLatch(1);
        var registrationStarted=new CountDownLatch(1);var statePid=new AtomicInteger();var registrationPid=new AtomicInteger();
        doAnswer(call->{deviceLocked.countDown();await(releaseState);return call.callRealMethod();})
            .when(notifications).observeState(eq(owner),eq(device),anyString(),any(),anyMap());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var state=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                statePid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                var body=new DesktopInteractions.State("concurrent-state",json.createObjectNode());
                switch(entry){case "publish"->interactions.publish(owner,device,body);case "heartbeat"->interactions.heartbeat(owner,device,body);case "exchange"->interactions.exchange(owner,device,body);default->throw new AssertionError(entry);}
                return true;
            }));
            await(deviceLocked);
            var registration=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                registrationPid.set(db.queryForObject("select pg_backend_pid()",Integer.class));registrationStarted.countDown();
                return sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Renamed","win32"));
            }));
            await(registrationStarted);awaitBlockedBy(registrationPid.get(),statePid.get());
            releaseState.countDown();
            assertTrue(state.get(15,TimeUnit.SECONDS));assertEquals(device,registration.get(15,TimeUnit.SECONDS).get("deviceId"));
            var row=db.queryForMap("select name,runtime_epoch,revoked from sync_devices where id=?",device);
            assertEquals("Renamed",row.get("name"));assertEquals("concurrent-state",row.get("runtime_epoch"));assertEquals(false,row.get("revoked"));
        } finally {releaseState.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @ParameterizedTest
    @ValueSource(strings={"registerSession","rename","archive"})
    void historyWritesSerializeBeforeTheirDeviceAndSessionLocks(String entry)throws Exception {
        var header=json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false);
        var body=new DesktopHistorySync.Registration(device,header,0);
        String session=entry.equals("registerSession")?null:(String)sync.registerSession(owner,body).get("id");
        var deviceLocked=new CountDownLatch(1);var releaseWrite=new CountDownLatch(1);
        var registrationStarted=new CountDownLatch(1);var writerPid=new AtomicInteger();var registrationPid=new AtomicInteger();
        var gatedDb=new JdbcTemplate(db.getDataSource()){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... arguments){
                var rows=super.queryForList(sql,arguments);
                if(sql.contains("for update")&&(sql.startsWith("select * from sync_devices")||sql.startsWith("select s.*,d.name"))){deviceLocked.countDown();await(releaseWrite);}
                return rows;
            }
        };
        var history=new DesktopHistorySync(gatedDb,auth,json,java.time.Clock.systemUTC(),attachments,notifications);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var write=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                writerPid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                return switch(entry){case "registerSession"->history.registerSession(owner,body);case "rename"->history.rename(owner,session,new DesktopHistorySync.Title("Changed",0L));case "archive"->history.archive(owner,session,new DesktopHistorySync.Archive(true,0L));default->throw new AssertionError(entry);};
            }));
            await(deviceLocked);
            var registration=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                registrationPid.set(db.queryForObject("select pg_backend_pid()",Integer.class));registrationStarted.countDown();
                return sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Renamed","win32"));
            }));
            await(registrationStarted);awaitBlockedBy(registrationPid.get(),writerPid.get());releaseWrite.countDown();
            assertNotNull(write.get(15,TimeUnit.SECONDS));assertEquals(device,registration.get(15,TimeUnit.SECONDS).get("deviceId"));
            assertEquals(1,db.queryForObject("select count(*) from sync_sessions where device_id=?",Integer.class,device));
            if(entry.equals("rename"))assertEquals("Changed",db.queryForObject("select title from sync_sessions where id=?",String.class,session));
            if(entry.equals("archive"))assertEquals(true,db.queryForObject("select archived from sync_sessions where id=?",Boolean.class,session));
        } finally {releaseWrite.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @ParameterizedTest
    @ValueSource(strings={"publish","registerDevice","registerSession","rename","archive"})
    void authorizationIsRecheckedAfterWaitingForOwnerLock(String entry)throws Exception {
        var header=json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false);
        String session=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,header,0)).get("id");
        var disabled=new CountDownLatch(1);var commitDisable=new CountDownLatch(1);var writerStarted=new CountDownLatch(1);
        var disablePid=new AtomicInteger();var writerPid=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        try {
            var disable=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                disablePid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                db.update("update principals set enabled=false,auth_version=auth_version+1 where id=?",owner.id());
                disabled.countDown();await(commitDisable);return true;
            }));
            await(disabled);
            var write=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                writerPid.set(db.queryForObject("select pg_backend_pid()",Integer.class));writerStarted.countDown();
                switch(entry){case "publish"->interactions.publish(owner,device,new DesktopInteractions.State("must-not-commit",json.createObjectNode()));case "registerDevice"->sync.registerDevice(owner,new DesktopHistorySync.Device(device,"must-not-commit","win32"));case "registerSession"->sync.registerSession(owner,new DesktopHistorySync.Registration(device,header.deepCopy().put("id","must-not-commit"),0));case "rename"->sync.rename(owner,session,new DesktopHistorySync.Title("must-not-commit",0L));case "archive"->sync.archive(owner,session,new DesktopHistorySync.Archive(true,0L));default->throw new AssertionError(entry);}
                return true;
            }));
            await(writerStarted);awaitBlockedBy(writerPid.get(),disablePid.get());commitDisable.countDown();assertTrue(disable.get(15,TimeUnit.SECONDS));
            var error=assertThrows(ExecutionException.class,()->write.get(15,TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class,error.getCause());assertEquals(403,((ApiException)error.getCause()).status);
            assertEquals("Desktop",db.queryForObject("select name from sync_devices where id=?",String.class,device));
            assertNull(db.queryForObject("select runtime_epoch from sync_devices where id=?",String.class,device));
            var row=db.queryForMap("select title,archived from sync_sessions where id=?",session);assertEquals("",row.get("title"));assertEquals(false,row.get("archived"));
            assertEquals(1,db.queryForObject("select count(*) from sync_sessions where device_id=?",Integer.class,device));
        } finally {commitDisable.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @ParameterizedTest
    @ValueSource(strings={"revoke","bind"})
    void statePublicationRemainsConsistentWithAccessAndMaintenanceBinding(String action)throws Exception {
        var deviceLocked=new CountDownLatch(1);var releaseState=new CountDownLatch(1);var otherStarted=new CountDownLatch(1);
        var statePid=new AtomicInteger();var otherPid=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        doAnswer(call->{deviceLocked.countDown();await(releaseState);return call.callRealMethod();})
            .when(notifications).observeState(eq(owner),eq(device),anyString(),any(),anyMap());
        try {
            var state=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                statePid.set(db.queryForObject("select pg_backend_pid()",Integer.class));interactions.publish(owner,device,new DesktopInteractions.State("active",json.createObjectNode()));return true;
            }));
            await(deviceLocked);
            var other=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                otherPid.set(db.queryForObject("select pg_backend_pid()",Integer.class));otherStarted.countDown();
                if(action.equals("revoke")){sync.revokeDevice(owner,device,new DesktopHistorySync.DeviceAccess(0L));return true;}
                return desktops.bind(owner,new MaintenanceDesktopChannel.Binding(device,"sandbox"));
            }));
            await(otherStarted);awaitBlockedBy(otherPid.get(),statePid.get());releaseState.countDown();
            assertTrue(state.get(15,TimeUnit.SECONDS));assertNotNull(other.get(15,TimeUnit.SECONDS));
            if(action.equals("revoke")){
                assertEquals(true,db.queryForObject("select revoked from sync_devices where id=?",Boolean.class,device));
                assertEquals(1L,db.queryForObject("select access_revision from sync_devices where id=?",Long.class,device));
                assertEquals(404,assertThrows(ApiException.class,()->interactions.publish(owner,device,new DesktopInteractions.State("revoked",json.createObjectNode()))).status);
                sync.restoreDevice(owner,device,new DesktopHistorySync.DeviceAccess(1L));
                assertNull(db.queryForObject("select runtime_epoch from sync_devices where id=?",String.class,device));
            }else assertEquals(1,db.queryForObject("select count(*) from maintenance_desktop_bindings where device_id=? and enabled=true",Integer.class,device));
        } finally {releaseState.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
