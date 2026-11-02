package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:employee-chat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class EmployeeChatsIntegrationTest {
 @Autowired org.springframework.jdbc.core.JdbcTemplate db;@Autowired ModelExecution models;@Autowired EmployeeChats chats;@Autowired AuthService auth;@Autowired ControlService control;@Autowired ClientPolicyController policies;
 Identity admin,owner,other;String agent;
 @BeforeEach void setup(){String tenant=AuthService.id(),token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),token);admin=auth.authenticate(token);token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Owner",Set.of("OPERATOR","VIEWER"),Set.of("sandbox"),token);owner=auth.authenticate(token);token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Other",Set.of("OPERATOR","VIEWER"),Set.of("sandbox"),token);other=auth.authenticate(token);agent=(String)control.publishAgent(admin,new Requests.Agent("chat","1.0.0","model.read.v1")).get("agentId");}
 Requests.Task body(String prompt){return new Requests.Task(agent,"sandbox",List.of(prompt));}
 @Test void persistentChatOwnershipAndIdempotentFirstTurn(){var first=chats.send(owner,null,"first",body("员工指令"));String id=(String)first.get("chatId");assertEquals(first.get("taskId"),chats.send(owner,null,"first",body("员工指令")).get("taskId"));assertEquals(1,((List<?>)chats.inspect(owner,id).get("items")).size());assertEquals(1,((List<?>)chats.list(owner,"").get("items")).size());assertEquals(404,assertThrows(ApiException.class,()->chats.inspect(other,id)).status);assertEquals(403,assertThrows(ApiException.class,()->chats.list(admin,"")).status);assertEquals(409,assertThrows(ApiException.class,()->chats.send(owner,id,"first",body("different"))).status);assertEquals(409,assertThrows(ApiException.class,()->chats.send(owner,id,"second",body("followup"))).status);control.cancel(owner,(String)first.get("taskId"));assertNotNull(chats.send(owner,id,"second",body("followup")).get("taskId"));assertEquals(2,((List<?>)chats.inspect(owner,id).get("items")).size());}
 @Test void invalidChatCannotCreateTask(){assertEquals(400,assertThrows(ApiException.class,()->chats.send(owner,null,"bad",body("x".repeat(6001)))).status);assertEquals(0,((List<?>)chats.list(owner,"").get("items")).size());assertEquals(400,assertThrows(ApiException.class,()->chats.send(owner,null,"bad",new Requests.Task(agent,"sandbox",List.of("one","two")))).status);}
 @Test void reservedPolicyAndPublishedBindingStayServerManaged(){var defaultPolicy=(ClientPolicyController.Policy)policies.get(owner).get("policy");assertEquals("MICROSOFT_MANAGED",defaultPolicy.signInMode());assertTrue(defaultPolicy.entries().stream().anyMatch(entry->entry.name().equals("北京软虹公文系统")));assertEquals(403,assertThrows(ApiException.class,()->policies.save(owner,defaultPolicy)).status);var entries=List.of(new ClientPolicyController.Entry("wps","WPS","OFFICE","AVAILABLE",agent,"已部署能力"));var next=new ClientPolicyController.Policy(0,"MICROSOFT_MANAGED","microsoft",entries);assertEquals(1,policies.save(admin,next).get("version"));assertEquals(409,assertThrows(ApiException.class,()->policies.save(admin,next)).status);assertEquals(400,assertThrows(ApiException.class,()->policies.save(admin,new ClientPolicyController.Policy(1,"MICROSOFT_MANAGED","",List.of(new ClientPolicyController.Entry("bad","bad","MCP","AVAILABLE","unknown",""))))).status);assertEquals(false,policies.get(owner).get("identityReady"));}
 @Test void clientDirectoryRejectsForeignAndUnpublishedBindingsWithoutReplacingSavedPolicy(){
  var good=new ClientPolicyController.Entry("wps","WPS","OFFICE","AVAILABLE",agent,"");
  policies.save(admin,new ClientPolicyController.Policy(0,"MICROSOFT_MANAGED","",List.of(good)));
  String saved=db.queryForObject("select policy_json from employee_client_policy where tenant_id=?",String.class,admin.tenant());
  String token=AuthService.id();auth.create(AuthService.id(),AuthService.id(),"USER","Foreign admin",Set.of("ADMIN"),Set.of("sandbox"),token);
  String foreign=(String)control.publishAgent(auth.authenticate(token),new Requests.Agent("foreign","1.0.0","text.stats.v1")).get("agentId");
  for(String invalid:List.of(foreign,agent)){
   if(invalid.equals(agent))db.update("update agent_versions set status='DRAFT' where id=?",agent);
   var rejected=new ClientPolicyController.Entry("wps","WPS","OFFICE","AVAILABLE",invalid,"");
   var error=assertThrows(ApiException.class,()->policies.save(admin,new ClientPolicyController.Policy(1,"MICROSOFT_MANAGED","",List.of(rejected))));
   assertEquals(400,error.status);assertEquals(1,policies.get(owner).get("version"));
   assertEquals(saved,db.queryForObject("select policy_json from employee_client_policy where tenant_id=?",String.class,admin.tenant()));
  }
  db.update("update agent_versions set status='PUBLISHED' where id=?",agent);
  var duplicate=assertThrows(ApiException.class,()->policies.save(admin,new ClientPolicyController.Policy(1,"MICROSOFT_MANAGED","",List.of(good,good))));
  assertEquals(400,duplicate.status);assertEquals(1,policies.get(owner).get("version"));
 }
 @Test void completedModelReplyBecomesBoundedFollowupContext(){var first=chats.send(owner,null,"model-first",body("First question"));var issued=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));var node=auth.authenticate((String)issued.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"Earlier generated answer"));var second=chats.send(owner,(String)first.get("chatId"),"model-second",body("Followup question"));String input=db.queryForObject("select input_text from work_units where task_id=?",String.class,second.get("taskId"));assertTrue(input.contains("Earlier generated answer"));assertTrue(input.contains("当前员工指令：\nFollowup question"));assertTrue(input.length()<=10000);}

 @Test void conversationPagesRemainBoundedAndCanRecoverEarlierTurns(){String id=null;for(int i=0;i<25;i++){var turn=chats.send(owner,id,"turn-"+i,body("Message "+i));id=(String)turn.get("chatId");control.cancel(owner,(String)turn.get("taskId"));}var latest=chats.inspect(owner,id);var rows=(List<Map<String,Object>>)latest.get("items");assertEquals(20,rows.size());assertEquals(5,rows.get(0).get("ordinal"));assertEquals(true,latest.get("hasEarlier"));var earlier=chats.inspect(owner,id,5);assertEquals(5,((List<?>)earlier.get("items")).size());assertEquals(false,earlier.get("hasEarlier"));}

 @Test void supplementsWaitForOutputWithoutReplayingLeasedWorkAndRetryAfterCompletion(){
  var first=chats.send(owner,null,"start",body("Draft the report"));String id=(String)first.get("chatId"),task=(String)first.get("taskId");
  var issued=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));var node=auth.authenticate((String)issued.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);
  var instruction=new EmployeeChats.Instruction(task,1,"Use three sections");var receipt=chats.supplement(owner,id,"supplement",instruction);
  assertEquals(2,receipt.get("planVersion"));assertEquals("WAITING_DEPENDENCY",db.queryForObject("select state from work_units where task_id=? and ordinal=1",String.class,task));
  assertEquals(work.get("epoch"),db.queryForObject("select epoch from work_units where id=?",Long.class,work.get("unitId")));
  assertEquals(true,chats.supplement(owner,id,"supplement",instruction).get("reused"));
  assertEquals(409,assertThrows(ApiException.class,()->chats.supplement(owner,id,"other",instruction)).status);
  models.output(node,(String)work.get("attemptId"),new Requests.ModelResult(((Number)work.get("epoch")).longValue(),"Original report"));control.tick();var next=control.poll(node);
  assertNotEquals(work.get("unitId"),next.get("unitId"));assertTrue(((String)next.get("input")).contains("Use three sections"));
  var inputs=(List<Map<String,Object>>)models.inputs(node,(String)next.get("attemptId"),((Number)next.get("epoch")).longValue()).get("items");assertEquals(1,inputs.size());
  assertEquals("Original report",new String(Base64.getDecoder().decode((String)inputs.get(0).get("contentBase64")),java.nio.charset.StandardCharsets.UTF_8));
  models.output(node,(String)next.get("attemptId"),new Requests.ModelResult(((Number)next.get("epoch")).longValue(),"Revised report"));
  assertEquals(true,chats.supplement(owner,id,"supplement",instruction).get("reused"));
  assertEquals(2,db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id where u.task_id=?",Integer.class,task));
  var turn=((List<Map<String,Object>>)chats.inspect(owner,id).get("items")).get(0);assertEquals("GENERATED",((List<Map<String,Object>>)turn.get("instructions")).get(0).get("state"));
 }
 @Test void supplementOwnershipConflictsAndCancellationDoNotCreateNewWork(){
  var first=chats.send(owner,null,"start",body("Draft"));String id=(String)first.get("chatId"),task=(String)first.get("taskId");var instruction=new EmployeeChats.Instruction(task,1,"Change title");
  assertEquals(404,assertThrows(ApiException.class,()->chats.supplement(other,id,"one",instruction)).status);
  assertEquals(404,assertThrows(ApiException.class,()->chats.supplement(owner,id,"one",new EmployeeChats.Instruction("missing",1,"Change"))).status);
  chats.supplement(owner,id,"one",instruction);
  assertEquals(409,assertThrows(ApiException.class,()->chats.supplement(owner,id,"one",new EmployeeChats.Instruction(task,1,"Different"))).status);
  control.cancel(owner,task);assertEquals(409,assertThrows(ApiException.class,()->chats.supplement(owner,id,"two",new EmployeeChats.Instruction(task,2,"Change again"))).status);
  assertEquals(2,db.queryForObject("select count(*) from work_units where task_id=?",Integer.class,task));
  auth.disable(admin,owner.id());assertEquals(403,assertThrows(ApiException.class,()->chats.supplement(owner,id,"one",instruction)).status);
 }

 @Test void chatEventsRecoverOrderedPagesWithoutOtherOwnersOrChats(){
  var first=chats.send(owner,null,"events",body("Work"));String chat=(String)first.get("chatId"),task=(String)first.get("taskId");
  var otherChat=chats.send(owner,null,"other-chat",body("Unrelated"));
  for(int i=0;i<205;i++)db.update("insert into events(tenant_id,task_id,actor_id,event_type,payload_json,created_at) values(?,?,?,'FIXTURE','{}',?)",owner.tenant(),task,owner.id(),i+1);
  var page=chats.events(owner,chat,0);assertEquals(200,page.size());long cursor=0;for(var event:page){assertEquals(task,event.get("task_id"));assertTrue(((Number)event.get("id")).longValue()>cursor);cursor=((Number)event.get("id")).longValue();}
  var remainder=chats.events(owner,chat,cursor);assertEquals(6,remainder.size());assertTrue(chats.events(owner,chat,((Number)remainder.get(5).get("id")).longValue()).isEmpty());
  assertEquals(404,assertThrows(ApiException.class,()->chats.events(other,chat,0)).status);assertEquals(400,assertThrows(ApiException.class,()->chats.events(owner,chat,-1)).status);
  assertEquals(1,chats.events(owner,(String)otherChat.get("chatId"),0).size());auth.disable(admin,owner.id());assertEquals(403,assertThrows(ApiException.class,()->chats.events(owner,chat,0)).status);
 }

}
