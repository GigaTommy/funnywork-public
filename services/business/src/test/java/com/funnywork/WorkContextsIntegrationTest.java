package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:work-context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class WorkContextsIntegrationTest {
 @Autowired JdbcTemplate db;@Autowired WorkContexts contexts;@Autowired EmployeeChats chats;@Autowired AuthService auth;@Autowired ControlService control;@Autowired ArtifactService artifacts;@Autowired ModelExecution models;
 Identity admin,owner,other;String agent,chat,task,artifact;
 Identity user(String tenant,String role){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),token);return auth.authenticate(token);}
 @BeforeEach void setup(){String tenant=AuthService.id();admin=user(tenant,"ADMIN");owner=user(tenant,"OPERATOR");other=user(tenant,"OPERATOR");agent=(String)control.publishAgent(admin,new Requests.Agent("context","1.0.0","model.read.v1")).get("agentId");var first=chats.send(owner,null,"first",new Requests.Task(agent,"sandbox",List.of("Initial goal")));chat=(String)first.get("chatId");task=(String)first.get("taskId");artifact=(String)artifacts.create(owner,new ArtifactService.Create("material.txt","sandbox",Base64.getEncoder().encodeToString("version one".getBytes(StandardCharsets.UTF_8)))).get("artifactId");artifacts.attach(owner,task,new ArtifactService.Attachment(artifact,1));}
 WorkContexts.Body body(int version){return new WorkContexts.Body(version,"Reviewed goal",List.of("Use the approved template"),List.of("Confirm deadline"),List.of(task),List.of(new WorkContexts.Source(task,artifact,1)));}
 @Test void savedContextIsVersionedIdempotentAndUsesOnlyFixedSelectedMaterials(){
  assertEquals(0,contexts.get(owner,chat).get("version"));assertEquals(1,contexts.save(owner,chat,"save",body(0)).get("version"));assertEquals(true,contexts.save(owner,chat,"save",body(0)).get("reused"));
  assertEquals(409,assertThrows(ApiException.class,()->contexts.save(owner,chat,"stale",body(0))).status);
  artifacts.update(owner,artifact,new ArtifactService.Revision(1,Base64.getEncoder().encodeToString("version two".getBytes(StandardCharsets.UTF_8))));
  control.cancel(owner,task);var second=chats.send(owner,chat,"second",new Requests.Task(agent,"sandbox",List.of("Continue")));String next=(String)second.get("taskId");
  String input=db.queryForObject("select input_text from work_units where task_id=?",String.class,next);assertTrue(input.contains("Reviewed goal"));assertTrue(input.contains("Confirm deadline"));assertTrue(input.contains("不构成新的操作授权"));assertFalse(input.contains("Initial goal"));
  assertEquals(1,db.queryForObject("select version from task_artifacts where task_id=? and artifact_id=?",Integer.class,next,artifact));
  assertEquals(1,db.queryForObject("select context_version from employee_chat_turns where task_id=?",Integer.class,next));
  assertEquals("version one",new String(Base64.getDecoder().decode((String)artifacts.read(owner,artifact,1).get("content_base64")),StandardCharsets.UTF_8));
  assertEquals(2,contexts.clear(owner,chat,"clear",1).get("version"));assertNull(db.queryForObject("select context_json from employee_work_contexts where chat_id=?",String.class,chat));assertEquals(false,contexts.get(owner,chat).get("active"));assertEquals(true,contexts.clear(owner,chat,"clear",1).get("reused"));
  assertEquals(input,db.queryForObject("select input_text from work_units where task_id=?",String.class,next));
 }
 @Test void unavailableSourcesAreHiddenAndPreventDispatchButCanBeCleared(){
  contexts.save(owner,chat,"save",body(0));artifacts.delete(owner,artifact);
  var view=contexts.get(owner,chat);assertEquals(false,view.get("available"));assertFalse(view.containsKey("data"));assertEquals(1,view.get("version"));
  assertEquals(404,assertThrows(ApiException.class,()->contexts.save(owner,chat,"save",body(0))).status);
  control.cancel(owner,task);assertEquals("CONTEXT_SOURCE_UNAVAILABLE",assertThrows(ApiException.class,()->chats.send(owner,chat,"next",new Requests.Task(agent,"sandbox",List.of("Continue")))).code);
  assertEquals(1,db.queryForObject("select count(*) from employee_chat_turns where chat_id=?",Integer.class,chat));contexts.clear(owner,chat,"clear",1);assertNotNull(chats.send(owner,chat,"next",new Requests.Task(agent,"sandbox",List.of("Continue"))).get("taskId"));
 }
 @Test void crossOwnerCrossChatAndMissingFixedVersionsCannotBecomeSources(){
  assertEquals(404,assertThrows(ApiException.class,()->contexts.get(other,chat)).status);assertEquals(404,assertThrows(ApiException.class,()->contexts.save(other,chat,"save",body(0))).status);
  var another=chats.send(owner,null,"another",new Requests.Task(agent,"sandbox",List.of("Other work")));var invalid=new WorkContexts.Body(0,"goal",List.of(),List.of(),List.of((String)another.get("taskId")),List.of());assertEquals(404,assertThrows(ApiException.class,()->contexts.save(owner,chat,"bad",invalid)).status);
  var noVersion=new WorkContexts.Body(0,"goal",List.of(),List.of(),List.of(task),List.of(new WorkContexts.Source(task,artifact,2)));assertEquals(404,assertThrows(ApiException.class,()->contexts.save(owner,chat,"bad",noVersion)).status);
  auth.disable(admin,owner.id());assertEquals(403,assertThrows(ApiException.class,()->contexts.get(owner,chat)).status);
 }
 @Test void excludingAllMaterialsPreventsAutomaticCarryAndMaximumTextRemainsBounded(){
  contexts.save(owner,chat,"save",new WorkContexts.Body(0,"g".repeat(400),Collections.nCopies(5,"d".repeat(120)),Collections.nCopies(5,"q".repeat(120)),List.of(task),List.of()));control.cancel(owner,task);
  var next=chats.send(owner,chat,"next",new Requests.Task(agent,"sandbox",List.of("x".repeat(6000))));assertEquals(0,db.queryForObject("select count(*) from task_artifacts where task_id=?",Integer.class,next.get("taskId")));assertTrue(db.queryForObject("select length(input_text) from work_units where task_id=?",Integer.class,next.get("taskId"))<=10000);
  assertEquals(400,assertThrows(ApiException.class,()->contexts.save(owner,chat,"invalid",new WorkContexts.Body(1,"x".repeat(401),List.of(),List.of(),List.of(task),List.of()))).status);
 }
 @Test void sourceRevocationAfterTaskCreationBlocksDispatchDespiteLaterContextClear(){
  contexts.save(owner,chat,"save",body(0));control.cancel(owner,task);var next=chats.send(owner,chat,"next",new Requests.Task(agent,"sandbox",List.of("Continue")));String nextTask=(String)next.get("taskId");
  contexts.clear(owner,chat,"clear",1);artifacts.delete(owner,artifact);
  var issued=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));var node=auth.authenticate((String)issued.get("token"));control.heartbeat(node,true);control.tick();assertEquals("NO_WORK",control.poll(node).get("status"));assertEquals("BLOCKED_AUTH",control.getTask(owner,nextTask).get("state"));
 }

 @Test void sourceRevocationDuringExecutionRejectsOutputWithoutReplayingWork(){
  contexts.save(owner,chat,"save",body(0));control.cancel(owner,task);var next=chats.send(owner,chat,"next",new Requests.Task(agent,"sandbox",List.of("Continue")));String nextTask=(String)next.get("taskId");
  var issued=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));var node=auth.authenticate((String)issued.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);assertEquals("WORK",work.get("status"));
  artifacts.delete(owner,artifact);assertEquals(403,assertThrows(ApiException.class,()->models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"Late output"))).status);
  control.tick();assertEquals("BLOCKED_AUTH",control.getTask(owner,nextTask).get("state"));assertEquals(0,db.queryForObject("select count(*) from unit_results r join work_units u on u.id=r.unit_id where u.task_id=?",Integer.class,nextTask));
 }

 @Test void fiftyTurnContinuationCarriesConfirmedContextAndFixedVersionsWithoutReplayingTasks(){
  control.cancel(owner,task);for(int i=1;i<50;i++){var turn=chats.send(owner,chat,"turn-"+i,new Requests.Task(agent,"sandbox",List.of("Message "+i)));control.cancel(owner,(String)turn.get("taskId"));}
  contexts.save(owner,chat,"save",body(0));var request=new EmployeeChats.Continuation(1,50);var receipt=chats.continueChat(owner,chat,"continue",request);String child=(String)receipt.get("chatId");
  assertEquals(true,chats.continueChat(owner,chat,"continue",request).get("reused"));assertEquals(0,chats.inspect(owner,child).get("turnCount"));assertEquals(50,chats.inspect(owner,chat).get("turnCount"));assertEquals(50,db.queryForObject("select count(*) from tasks where owner_id=?",Integer.class,owner.id()));
  assertEquals(chat,((Map<?,?>)chats.inspect(owner,child).get("chat")).get("parent_chat_id"));assertEquals("Reviewed goal",((WorkContexts.Body)contexts.get(owner,child).get("data")).goal());
  contexts.clear(owner,chat,"clear",1);assertEquals(true,contexts.get(owner,child).get("active"));
  var next=chats.send(owner,child,"child-first",new Requests.Task(agent,"sandbox",List.of("Continue here")));assertTrue(db.queryForObject("select input_text from work_units where task_id=?",String.class,next.get("taskId")).contains("Reviewed goal"));assertEquals(1,db.queryForObject("select version from task_artifacts where task_id=? and artifact_id=?",Integer.class,next.get("taskId"),artifact));
  assertEquals(413,assertThrows(ApiException.class,()->chats.send(owner,chat,"overflow",new Requests.Task(agent,"sandbox",List.of("Too many")))).status);
  artifacts.delete(owner,artifact);assertEquals(false,contexts.get(owner,child).get("available"));control.tick();assertEquals("BLOCKED_AUTH",control.getTask(owner,(String)next.get("taskId")).get("state"));
 }
 @Test void continuationRejectsBusyMissingSummaryStaleVersionsAndCrossOwner(){
  var request=new EmployeeChats.Continuation(1,1);assertEquals(404,assertThrows(ApiException.class,()->chats.continueChat(other,chat,"continue",request)).status);
  assertEquals("CHAT_TURN_PENDING",assertThrows(ApiException.class,()->chats.continueChat(owner,chat,"continue",request)).code);control.cancel(owner,task);
  assertEquals("CONTINUATION_CONTEXT_REQUIRED",assertThrows(ApiException.class,()->chats.continueChat(owner,chat,"continue",request)).code);contexts.save(owner,chat,"save",body(0));
  assertEquals(409,assertThrows(ApiException.class,()->chats.continueChat(owner,chat,"bad-version",new EmployeeChats.Continuation(2,1))).status);assertEquals(409,assertThrows(ApiException.class,()->chats.continueChat(owner,chat,"bad-turns",new EmployeeChats.Continuation(1,2))).status);
  chats.continueChat(owner,chat,"continue",request);assertEquals(409,assertThrows(ApiException.class,()->chats.continueChat(owner,chat,"continue",new EmployeeChats.Continuation(2,1))).status);
 }

}
