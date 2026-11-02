package com.funnywork;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
 "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:recoverygeneration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
 "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
 "funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.recovery-generation=test-a"})
@AutoConfigureMockMvc
class RecoveryGenerationTest {
 @Autowired JdbcTemplate db;
 @Autowired PlatformTransactionManager transactions;
 @Autowired MockMvc http;
 @AfterEach void reset(){db.update("update recovery_generation set generation='initial' where id=1");}
 @Test void healthAndApiRejectStaleInstanceWithoutRestart() throws Exception {
  db.update("update recovery_generation set generation='initial' where id=1");
  http.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.code").value("RECOVERY_GENERATION_MISMATCH"));
  db.update("update recovery_generation set generation='test-a' where id=1");
  http.perform(get("/actuator/health")).andExpect(status().isOk());
  db.update("update recovery_generation set generation='test-b' where id=1");
  http.perform(post("/api/v1/nodes/heartbeat").header("Authorization","Bearer old").contentType("application/json").content("{}"))
      .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOVERY_GENERATION_MISMATCH"));
 }
 @Test void schedulerRechecksGenerationAndMissingRowFailsClosed() {
  var control=mock(ControlService.class);
  var gate=new RecoveryGate(db,false,"test-a");
  var scheduler=new Scheduler(control,true,gate);
  db.update("update recovery_generation set generation='test-b' where id=1");
  scheduler.changed(new TenantWorkChanged("demo"));scheduler.tick();
  verifyNoInteractions(control);
  db.update("update recovery_generation set generation='test-a' where id=1");
  scheduler.tick();verify(control).tickTenant("demo");
  db.update("delete from recovery_generation where id=1");
  try {assertEquals("RECOVERY_GENERATION_MISMATCH",gate.failure());}
  finally {db.update("insert into recovery_generation(id,generation) values(1,'initial')");}
  assertThrows(IllegalArgumentException.class,()->new RecoveryGate(db,false,""));
 }
 @Test void generationUpdateWaitsForAdmittedTransactionAndThenRejectsOldGeneration() throws Exception {
  db.update("update recovery_generation set generation='test-a' where id=1");
  var gate=new RecoveryGate(db,false,"test-a");
  var tx=new TransactionTemplate(transactions);
  var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);
  var executor=Executors.newFixedThreadPool(2);
  try {
   var reader=executor.submit(()->tx.execute(status->{
    assertTrue(gate.lockTransaction(false));admitted.countDown();
    try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
    return true;
   }));
   assertTrue(admitted.await(5,TimeUnit.SECONDS));
   var updateStarted=new CountDownLatch(1);
   var writer=executor.submit(()->{updateStarted.countDown();return db.update("update recovery_generation set generation='test-b' where id=1");});
   assertTrue(updateStarted.await(5,TimeUnit.SECONDS));
   assertThrows(TimeoutException.class,()->writer.get(200,TimeUnit.MILLISECONDS));
   release.countDown();assertTrue(reader.get(5,TimeUnit.SECONDS));assertEquals(1,writer.get(5,TimeUnit.SECONDS));
   var failure=assertThrows(ApiException.class,()->tx.execute(status->gate.lockTransaction(false)));
   assertEquals("RECOVERY_GENERATION_MISMATCH",failure.code);
   assertEquals(Boolean.FALSE,tx.execute(status->gate.lockTransaction(true)));
  } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void recoveryTransactionLockCannotEscapeAutocommitAndRollbackReleasesIt() {
  db.update("update recovery_generation set generation='test-a' where id=1");
  var gate=new RecoveryGate(db,false,"test-a");var tx=new TransactionTemplate(transactions);
  assertThrows(IllegalStateException.class,()->gate.lockTransaction(false));
  assertThrows(IllegalArgumentException.class,()->tx.execute(status->{gate.lockTransaction(false);throw new IllegalArgumentException("rollback");}));
  assertEquals(1,db.update("update recovery_generation set generation='test-b' where id=1"));
 }

}
