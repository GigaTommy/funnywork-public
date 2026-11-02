package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:desktop-sync;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class DesktopHistorySyncTest {
 @Autowired DesktopHistorySearch search;
 @Autowired HistoryTextProjection projection;
 @Autowired DesktopHistorySync sync;@Autowired AuthService auth;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;
 @Autowired MockMvc http;
 @Autowired DesktopInteractions interactions;
 @Autowired DesktopAttachments attachments;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 Identity owner,other;String device;
 Identity user(String tenant){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","User",Set.of("VIEWER"),Set.of("sandbox"),token);return auth.authenticate(token);}
 @BeforeEach void setup(){String tenant=AuthService.id();owner=user(tenant);other=user(tenant);device=AuthService.id();sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));}
 JsonNode json(String value){try{return mapper.readTree(value);}catch(Exception e){throw new RuntimeException(e);}}
 DesktopHistorySync.Registration registration(){return new DesktopHistorySync.Registration(device,json("{\"id\":\"native\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0);}
 JsonNode event(long seq,String text){return json(mapper.valueToTree(Map.of("seq",seq,"time",1,"type","user/message","data",Map.of("content",List.of(Map.of("type","text","text",text))))).toString());}
 String session(){return (String)sync.registerSession(owner,registration()).get("id");}
 long notificationCount(){return db.queryForObject("select count(*) from sync_notification_events where principal_id=?",Long.class,owner.id());}

 @Test void oneEventReplayReadsOnlyTheRequestedRecord(){
  String chat=session();var events=new ArrayList<JsonNode>();for(int i=0;i<100;i++)events.add(event(i,"record "+i));
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,events));
  var spyDb=org.mockito.Mockito.spy(db);var fetched=new java.util.concurrent.atomic.AtomicInteger();
  org.mockito.Mockito.doAnswer(call->{var rows=(List<?>)call.callRealMethod();fetched.set(rows.size());return rows;}).when(spyDb).queryForList(org.mockito.ArgumentMatchers.startsWith("select seq,event_json"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong());
  var service=new DesktopHistorySync(spyDb,auth,mapper,java.time.Clock.systemUTC(),attachments,org.mockito.Mockito.mock(DesktopNotifications.class));
  var receipt=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->service.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(events.get(0)))));
  assertEquals(1,fetched.get());assertEquals(100L,receipt.get("nextSeq"));
 }

 @Test void batchedAppendPreservesReplayAndRollsBackLateConflicts(){
  String chat=session();var initial=new ArrayList<JsonNode>();
  for(int i=0;i<100;i++)initial.add(event(i,"record "+i));
  assertEquals(100L,sync.append(owner,chat,new DesktopHistorySync.Batch(device,initial)).get("nextSeq"));
  assertEquals(100L,sync.append(owner,chat,new DesktopHistorySync.Batch(device,initial)).get("nextSeq"));
  var mixed=new ArrayList<JsonNode>(initial.subList(90,100));mixed.add(event(100,"next"));
  assertEquals(101L,sync.append(owner,chat,new DesktopHistorySync.Batch(device,mixed)).get("nextSeq"));
  assertEquals(101,db.queryForObject("select count(*) from sync_session_events where session_id=?",Integer.class,chat));
  assertEquals(101,db.queryForObject("select count(*) from sync_session_events where session_id=? and projection_version=2",Integer.class,chat));
  assertEquals(400,assertThrows(ApiException.class,()->sync.append(owner,chat,new DesktopHistorySync.Batch(device,Arrays.asList((JsonNode)null)))).status);
  assertThrows(ApiException.class,()->sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(event(101,"not committed"),event(103,"gap")))));
  assertEquals(101L,((Number)sync.conversation(owner,chat).get("next_seq")).longValue());
  assertEquals(0,db.queryForObject("select count(*) from sync_session_events where session_id=? and seq>=101",Integer.class,chat));
 }

 @Test void legacyTextProjectionBackfillsBoundedBatchesWithoutExposingHiddenContent(){
  // Other tests only write projected events; isolate legacy rows here.
  String chat=session();var records=new ArrayList<JsonNode>();
  for(int i=0;i<35;i++)records.add(event(i,"Legacy Needle "+i));
  records.add(json("{\"seq\":35,\"time\":1,\"type\":\"tool/call\",\"data\":{\"text\":\"private-secret\"}}"));
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,records));
  db.update("update sync_session_events set visible_text=null,projection_version=0 where session_id=?",chat);
  var before=search.search(owner,"needle","",-1,false,"");
  assertEquals(32,projection.runBatch());
  assertEquals(4,db.queryForObject("select count(*) from sync_session_events where session_id=? and projection_version=0",Integer.class,chat));
  assertEquals(4,projection.runBatch());assertEquals(0,projection.runBatch());
  assertEquals(before,search.search(owner,"needle","",-1,false,""));
  assertEquals("",db.queryForObject("select visible_text from sync_session_events where session_id=? and seq=35",String.class,chat));
  assertTrue(((List<?>)search.search(owner,"private-secret","",-1,false,"").get("items")).isEmpty());
 }


 @Test void deviceAccessRejectsStaleChangesAndDuplicateRestorePreservesRuntime() {
  String chat=session();runtime("access-one");
  assertEquals(0L,((Number)interactions.devices(owner,"").get(0).get("accessRevision")).longValue());
  assertEquals(404,assertThrows(ApiException.class,()->sync.revokeDevice(other,device,new DesktopHistorySync.DeviceAccess(0L))).status);
  sync.revokeDevice(owner,device,new DesktopHistorySync.DeviceAccess(0L));
  sync.revokeDevice(owner,device); // Legacy repeats are harmless.
  assertEquals(1L,((Number)interactions.devices(owner,"").get(0).get("accessRevision")).longValue());
  assertEquals(409,assertThrows(ApiException.class,()->sync.restoreDevice(owner,device,new DesktopHistorySync.DeviceAccess(0L))).status);
  sync.restoreDevice(owner,device,new DesktopHistorySync.DeviceAccess(1L));
  assertEquals(false,interactions.state(owner,chat).get("online"));
  runtime("access-two");
  sync.restoreDevice(owner,device,new DesktopHistorySync.DeviceAccess(2L));
  sync.restoreDevice(owner,device);
  assertEquals(true,interactions.state(owner,chat).get("online"));
  assertEquals(409,assertThrows(ApiException.class,()->sync.revokeDevice(owner,device,new DesktopHistorySync.DeviceAccess(0L))).status);
  assertEquals(400,assertThrows(ApiException.class,()->sync.revokeDevice(owner,device,new DesktopHistorySync.DeviceAccess(-1L))).status);
  assertEquals(2L,((Number)interactions.devices(owner,"").get(0).get("accessRevision")).longValue());
 }

 @Test void overviewPersistsLatestVisibleContentAcrossReplayAndRollback() {
  String chat=session();
  var first=event(0,"  visible\n summary  ");var tool=mapper.valueToTree(Map.of("seq",1,"time",1,"type","tool/end","data",Map.of("text","private tool detail")));
  var batch=new DesktopHistorySync.Batch(device,List.of(first,tool));sync.append(owner,chat,batch);
  var row=sync.list(owner,"",100).get(0);assertEquals("visible summary",row.get("preview_text"));assertEquals(0L,((Number)row.get("preview_seq")).longValue());
  sync.append(owner,chat,batch);assertEquals("visible summary",sync.conversation(owner,chat).get("preview_text"));
  assertThrows(ApiException.class,()->sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(event(2,"Must roll back"),event(4,"Gap")))));
  assertEquals("visible summary",sync.conversation(owner,chat).get("preview_text"));
  assertTrue(sync.list(other,"",100).isEmpty());
 }
 @Test void overviewTaskStatesAreCurrentOwnedAndNeverInferCompletion() {
  String chat=session();
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":true}}")));
  var row=sync.conversation(owner,chat);assertEquals("running",row.get("task_state"));assertTrue(((Number)row.get("task_state_remaining_ms")).longValue()<=30000);
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":true,\"pending\":[{\"eventId\":\"a\",\"event\":\"approval/request\",\"request\":{}},{\"eventId\":\"q\",\"event\":\"user-questions/request\",\"request\":{}}]}}")));
  assertEquals("approval",sync.conversation(owner,chat).get("task_state"));
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":false,\"projections\":{\"userQuestions\":{\"active\":[{\"state\":\"continued\",\"callId\":\"call\"}]}}}}")));
  assertEquals("question",sync.list(owner,"",100).get(0).get("task_state"));
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":false}}")));
  assertEquals("idle",sync.conversation(owner,chat).get("task_state"));
  db.update("update sync_devices set state_seen=0 where id=?",device);assertFalse(sync.conversation(owner,chat).containsKey("task_state"));
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":true,\"controllable\":false}}")));
  assertFalse(sync.conversation(owner,chat).containsKey("task_state"));
  interactions.publish(owner,device,new DesktopInteractions.State("overview",json("{\"native\":{\"seq\":0,\"running\":true}}")));sync.revokeDevice(owner,device);
  assertFalse(sync.conversation(owner,chat).containsKey("task_state"));assertEquals(false,sync.conversation(owner,chat).get("online"));
 }
 @Test void notificationConversationLookupIsOwnedAndIncludesArchivedConversations() throws Exception {
  String chat=session();db.update("update sync_sessions set archived=true where id=?",chat);
  var row=sync.conversation(owner,chat);assertEquals(chat,row.get("id"));assertEquals(true,row.get("archived"));assertFalse(row.containsKey("native_id"));
  assertEquals(404,assertThrows(ApiException.class,()->sync.conversation(other,chat)).status);
  assertEquals(404,assertThrows(ApiException.class,()->sync.conversation(user(AuthService.id()),chat)).status);
  http.perform(get("/api/account/sync/sessions/"+chat)).andExpect(status().isUnauthorized());
 }
 @Test void notificationFactsDeduplicatePendingRequestsAndSuppressInitialSnapshots() {
  session();
  var baseline=json("{\"native\":{\"seq\":0,\"running\":true,\"pending\":[{\"eventId\":\"old\",\"event\":\"approval/request\",\"request\":{}}]}}");
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",baseline));assertEquals(0,notificationCount());
  var next=json("{\"native\":{\"seq\":1,\"running\":true,\"pending\":[{\"eventId\":\"new\",\"event\":\"approval/request\",\"request\":{\"secret\":\"not-in-notification\"}},{\"eventId\":\"question\",\"event\":\"user-questions/request\",\"request\":{}}],\"projections\":{\"userQuestions\":{\"active\":[{\"state\":\"continued\",\"callId\":\"call\"}]}}}}");
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",next));assertEquals(3,notificationCount());
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",next));assertEquals(3,notificationCount());
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":2,\"running\":false}}")));
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",next));assertEquals(3,notificationCount());
  interactions.publish(owner,device,new DesktopInteractions.State("new-runtime",next));assertEquals(3,notificationCount());
  assertTrue(db.queryForList("select * from sync_notification_events where principal_id=?",owner.id()).stream().noneMatch(row->row.toString().contains("not-in-notification")));
  assertEquals(0L,db.queryForObject("select count(*) from sync_notification_events where principal_id=?",Long.class,other.id()));
 }
 @Test void notificationFactsRequireDurableRecentTurnEndAndIgnoreHistoryReplay() {
  String chat=session();long now=System.currentTimeMillis();
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":1,\"running\":true}}")));
  var end=mapper.valueToTree(Map.of("seq",1,"time",now,"type","turn/end","data",Map.of("reason",Map.of("kind","error"))));
  var batch=new DesktopHistorySync.Batch(device,List.of(event(0,"hello"),end));sync.append(owner,chat,batch);
  assertEquals(1,notificationCount());assertEquals("turn-ended",db.queryForObject("select kind from sync_notification_events where principal_id=?",String.class,owner.id()));
  sync.append(owner,chat,batch);assertEquals(1,notificationCount());
  var old=mapper.valueToTree(Map.of("seq",2,"time",1,"type","turn/end","data",Map.of()));
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(old)));assertEquals(1,notificationCount());
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":3,\"running\":false}}")));assertEquals(1,notificationCount());
 }
 @Test void notificationInitialHistoryAndUnavailableExecutionDoNotGenerateAlerts() {
  String chat=session();long now=System.currentTimeMillis();
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":2,\"running\":false}}")));
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(event(0,"old"),mapper.valueToTree(Map.of("seq",1,"time",now,"type","turn/end","data",Map.of())))));
  assertEquals(0,notificationCount());
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":3,\"running\":true,\"controllable\":false,\"pending\":[{\"eventId\":\"subagent\",\"event\":\"approval/request\",\"request\":{}}]}}")));
  assertEquals(0,notificationCount());
  db.update("update sync_devices set state_seen=0 where id=?",device);
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(mapper.valueToTree(Map.of("seq",2,"time",now,"type","turn/end","data",Map.of())))));
  assertEquals(0,notificationCount());
 }
 @Test void notificationFactsRollbackWithRejectedHistoryBatch() {
  String chat=session();long now=System.currentTimeMillis();
  interactions.publish(owner,device,new DesktopInteractions.State("notifications",json("{\"native\":{\"seq\":0,\"running\":true}}")));
  var end=mapper.valueToTree(Map.of("seq",1,"time",now,"type","turn/end","data",Map.of()));
  assertThrows(ApiException.class,()->sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(event(0,"hello"),end,event(4,"gap")))));
  assertEquals(0,notificationCount());
  assertEquals(0L,db.queryForObject("select next_seq from sync_sessions where id=?",Long.class,chat));
 }
 @Test void pendingInboxIsOwnedFreshAndCountsOnlyActionableItems() {
  String chat=session();
  var pending=json("{\"native\":{\"seq\":1,\"running\":true,\"pending\":[{\"eventId\":\"a\",\"event\":\"approval/request\",\"request\":{}},{\"eventId\":\"q\",\"event\":\"user-questions/request\",\"request\":{}},{\"eventId\":\"ignored\",\"event\":\"other\",\"request\":{}}],\"projections\":{\"userQuestions\":{\"active\":[{\"state\":\"continued\",\"callId\":\"c\"}]}}}}");
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",pending));
  db.update("update sync_sessions set archived=true where id=?",chat);
  var page=interactions.pending(owner,"");var items=(List<?>)page.get("items");assertEquals(1,items.size());
  var item=(Map<?,?>)items.get(0);assertEquals(1,item.get("approvals"));assertEquals(2,item.get("questions"));
  var conversation=(Map<?,?>)item.get("conversation");assertEquals(chat,conversation.get("id"));assertEquals(true,conversation.get("archived"));assertFalse(conversation.containsKey("native_id"));
  assertTrue(((List<?>)interactions.pending(other,"").get("items")).isEmpty());
  assertTrue(((List<?>)interactions.pending(user(AuthService.id()),"").get("items")).isEmpty());
  ((com.fasterxml.jackson.databind.node.ObjectNode)pending.path("native")).put("controllable",false);
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",pending));
  assertTrue(((List<?>)interactions.pending(owner,"").get("items")).isEmpty());
  ((com.fasterxml.jackson.databind.node.ObjectNode)pending.path("native")).put("controllable",true);
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",pending));
  db.update("update sync_devices set state_seen=0 where id=?",device);
  assertTrue(((List<?>)interactions.pending(owner,"").get("items")).isEmpty());
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",pending));sync.revokeDevice(owner,device);
  assertTrue(((List<?>)interactions.pending(owner,"").get("items")).isEmpty());
  assertEquals(400,assertThrows(ApiException.class,()->interactions.pending(owner,"invalid/cursor")).status);
 }
 @Test void pendingInboxHttpRequiresAccountAndDisablesCaching() throws Exception {
  http.perform(get("/api/account/sync/pending")).andExpect(status().isUnauthorized());
  String token=AuthService.id();auth.create(AuthService.id(),owner.tenant(),"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),token);
  http.perform(get("/api/account/sync/pending").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.hasMore").value(false));
 }
 @Test void pendingInboxAdvancesAcrossEmptyPagesAndRefreshesResolvedItems() {
  var state=mapper.createObjectNode();List<String> ids=new ArrayList<>();
  for(int i=0;i<35;i++) {
   String nativeId="inbox-"+i;
   String chat=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,mapper.valueToTree(Map.of("id",nativeId,"version",4,"createdAt",1,"isSeeded",false)),0)).get("id");
   ids.add(chat);state.set(nativeId,json("{\"seq\":1,\"running\":false}"));
  }
  Collections.sort(ids);String last=ids.get(34);String nativeId=db.queryForObject("select native_id from sync_sessions where id=?",String.class,last);
  ((com.fasterxml.jackson.databind.node.ObjectNode)state.path(nativeId)).set("pending",json("[{\"eventId\":\"a\",\"event\":\"approval/request\",\"request\":{}}]"));
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",state));
  var first=interactions.pending(owner,"");assertEquals(true,first.get("hasMore"));assertTrue(((List<?>)first.get("items")).isEmpty());assertEquals(ids.get(31),first.get("after"));
  var second=interactions.pending(owner,(String)first.get("after"));assertEquals(false,second.get("hasMore"));assertEquals(1,((List<?>)second.get("items")).size());
  ((com.fasterxml.jackson.databind.node.ObjectNode)state.path(nativeId)).remove("pending");interactions.publish(owner,device,new DesktopInteractions.State("epoch-inbox",state));
  assertTrue(((List<?>)interactions.pending(owner,(String)first.get("after")).get("items")).isEmpty());
 }
 @Test void messageSearchIsAccountScopedAndPaginatesSparseVisibleMatches(){
  String session=session();
  List<JsonNode> records=new ArrayList<>();
  for(int i=0;i<70;i++)records.add(event(i,i==69?"prefix Needle 中文 suffix":"ordinary"));
  sync.append(owner,session,new DesktopHistorySync.Batch(device,records));
  var first=search.search(owner,"needle","",-1,false,"");
  assertEquals(true,first.get("hasMore"));assertTrue(((List<?>)first.get("items")).isEmpty());
  assertEquals(63L,first.get("afterSeq"));
  var second=search.search(owner,"NEEDLE",(String)first.get("afterSession"),((Number)first.get("afterSeq")).longValue(),false,"");
  assertEquals(false,second.get("hasMore"));
  var matches=(List<?>)second.get("items");assertEquals(1,matches.size());
  var match=(Map<?,?>)matches.get(0);assertEquals(69L,match.get("seq"));
  assertTrue(match.get("excerpt").toString().contains("Needle 中文"));
  assertTrue(((List<?>)search.search(other,"Needle","",-1,false,"").get("items")).isEmpty());
  assertTrue(((List<?>)search.search(user(AuthService.id()),"Needle","",-1,false,"").get("items")).isEmpty());
  assertTrue(((List<?>)search.search(owner,"Needle","",-1,false,"different-device").get("items")).isEmpty());
  sync.archive(owner,session,new DesktopHistorySync.Archive(true,0L));
  assertTrue(((List<?>)search.search(owner,"ordinary","",-1,false,"").get("items")).isEmpty());
  sync.revokeDevice(owner,device);
  assertEquals(64,((List<?>)search.search(owner,"ordinary","",-1,true,device).get("items")).size());
 }
 @Test void messageSearchExcludesHiddenFieldsAndTreatsQueryLiterally(){
  String session=session();
  var visible=event(0,"Literal 100% _needle_ [x] "+"😀".repeat(200));
  var hidden=json("{\"seq\":1,\"time\":1,\"type\":\"assistant/message\",\"data\":{\"content\":[{\"type\":\"reasoning\",\"text\":\"secret\"}]}}");
  var tool=json("{\"seq\":2,\"time\":1,\"type\":\"tool/call\",\"data\":{\"arguments\":\"secret\"}}");
  var file=json("{\"seq\":3,\"time\":1,\"type\":\"user/message\",\"data\":{\"content\":[{\"type\":\"file\",\"attachment\":{\"name\":\"Report.pdf\"}}]}}");
  sync.append(owner,session,new DesktopHistorySync.Batch(device,List.of(visible,hidden,tool,file)));
  assertEquals(1,((List<?>)search.search(owner,"100% _needle_ [x]","",-1,false,"").get("items")).size());
  assertEquals(1,((List<?>)search.search(owner,"Report.pdf","",-1,false,"").get("items")).size());
  assertTrue(((List<?>)search.search(owner,"secret","",-1,false,"").get("items")).isEmpty());
  for(String query:List.of(" ","x".repeat(161)))assertEquals(400,assertThrows(ApiException.class,()->search.search(owner,query,"",-1,false,"")).status);
  assertEquals(400,assertThrows(ApiException.class,()->search.search(owner,"x","",0,false,"")).status);
 }
 @Test void archiveKeepsHistoryCommandsAndTitlesAndCanBeRestored(){
  String session=session();runtime("archive-epoch");
  interactions.enqueue(owner,session,command(AuthService.id(),"archive-epoch"));
  Object originalTime=sync.list(owner,"",100).get(0).get("updated_at");
  assertEquals(404,assertThrows(ApiException.class,()->sync.archive(other,session,new DesktopHistorySync.Archive(true,0L))).status);
  assertEquals(Map.of("archived",true,"revision",1L),sync.archive(owner,session,new DesktopHistorySync.Archive(true,0L)));
  assertEquals(originalTime,sync.list(owner,"",100).get(0).get("updated_at"));
  assertEquals(409,assertThrows(ApiException.class,()->sync.archive(owner,session,new DesktopHistorySync.Archive(false,0L))).status);
  assertEquals(400,assertThrows(ApiException.class,()->sync.archive(owner,session,new DesktopHistorySync.Archive(null,1L))).status);
  assertEquals(1,interactions.claim(owner,device,"archive-epoch").size());
  sync.rename(owner,session,new DesktopHistorySync.Title("Archived name",0L));
  sync.append(owner,session,new DesktopHistorySync.Batch(device,List.of(event(0,"Still running"))));
  var row=sync.list(owner,"",100).get(0);
  assertEquals(true,row.get("archived"));assertEquals("Archived name",row.get("title"));
  assertEquals(1L,((Number)row.get("archive_revision")).longValue());
  assertEquals(1,((List<?>)sync.page(owner,session,0,100).get("events")).size());
  sync.revokeDevice(owner,device);
  assertEquals(Map.of("archived",false,"revision",2L),sync.archive(owner,session,new DesktopHistorySync.Archive(false,1L)));
  assertEquals(false,sync.list(owner,"",100).get(0).get("archived"));
 }
 @Test void titlesAreAccountOwnedVersionedAndSurviveFurtherSynchronization(){
  String session=session();
  assertEquals(404,assertThrows(ApiException.class,()->sync.rename(other,session,new DesktopHistorySync.Title("Other",0L))).status);
  assertEquals(Map.of("title","Updated","revision",1L),sync.rename(owner,session,new DesktopHistorySync.Title(" Updated ",0L)));
  assertEquals(409,assertThrows(ApiException.class,()->sync.rename(owner,session,new DesktopHistorySync.Title("Stale",0L))).status);
  sync.append(owner,session,new DesktopHistorySync.Batch(device,List.of(event(0,"New message"))));
  var row=sync.list(owner,"",100).get(0);
  assertEquals("Updated",row.get("title"));assertEquals(1L,((Number)row.get("title_revision")).longValue());
  assertEquals(400,assertThrows(ApiException.class,()->sync.rename(owner,session,new DesktopHistorySync.Title(" ",1L))).status);
  assertEquals(400,assertThrows(ApiException.class,()->sync.rename(owner,session,new DesktopHistorySync.Title("a\nb",1L))).status);
  assertEquals(400,assertThrows(ApiException.class,()->sync.rename(owner,session,new DesktopHistorySync.Title("x".repeat(161),1L))).status);
  sync.revokeDevice(owner,device);
  assertEquals(2L,sync.rename(owner,session,new DesktopHistorySync.Title("Offline",1L)).get("revision"));
 }
 void runtime(String epoch){interactions.publish(owner,device,new DesktopInteractions.State(epoch,json("{\"native\":{\"seq\":1,\"running\":true,\"pending\":[{\"eventId\":\"decision-1\",\"event\":\"approval/request\"}]}}")));}
 @Test void liveReplySnapshotsStayAccountScopedAndAreReplacedWithRuntimeState(){
  String session=session();
  var states=json("{\"native\":{\"seq\":5,\"running\":true,\"turnStart\":1,\"liveAssistant\":{\"attemptId\":\"reply\",\"startedAfterSeq\":4,\"turnStart\":1,\"text\":\"Hello\",\"truncated\":false}}}");
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-1",states));
  assertEquals("Hello",((JsonNode)interactions.state(owner,session).get("state")).path("liveAssistant").path("text").asText());
  assertEquals(404,assertThrows(ApiException.class,()->interactions.state(other,session)).status);
  db.update("update sync_devices set state_seen=0 where id=?",device);
  assertEquals(false,interactions.state(owner,session).get("online"));
  runtime("epoch-2");
  assertFalse(((JsonNode)interactions.state(owner,session).get("state")).has("liveAssistant"));
 }
 DesktopInteractions.Command command(String id,String epoch){return new DesktopInteractions.Command(id,epoch,"prompt",json("{\"text\":\"Continue\",\"mode\":\"queue\"}"));}
 @Test void workspaceCommandsAreScopedClaimedOnceAndExposeBoundedReceipts(){
  interactions.publish(owner,device,new DesktopInteractions.State("workspace-epoch",json("{}"),json("[{\"id\":\"workspace\",\"title\":\"Project\"}]"),false,false,json("{\"models\":[],\"presets\":[],\"workspaceOperations\":true}")));
  var request=new DesktopInteractions.Command(AuthService.id(),"workspace-epoch","workspace",json("{\"action\":\"files\",\"workspaceId\":\"workspace\",\"path\":\"\"}"));
  interactions.workspaceCommand(owner,device,request);interactions.workspaceCommand(owner,device,request);
  assertThrows(ApiException.class,()->interactions.workspaceCommand(other,device,request));
  interactions.heartbeat(owner,device,new DesktopInteractions.State("workspace-epoch",json("{}"),json("[{\"id\":\"workspace\",\"title\":\"Project\"}]"),false,false,json("{\"models\":[],\"presets\":[],\"workspaceOperations\":true}")));
  assertEquals(1,interactions.claim(owner,device,"workspace-epoch").size());assertTrue(interactions.claim(owner,device,"workspace-epoch").isEmpty());
  interactions.acknowledge(owner,device,request.id(),new DesktopInteractions.Result("workspace-epoch","accepted",json("{\"entries\":[],\"after\":\"\"}")));
  assertEquals("accepted",interactions.deviceCommand(owner,device,request.id()).get("status"));
  assertThrows(ApiException.class,()->interactions.deviceCommand(other,device,request.id()));
  var missing=new DesktopInteractions.Command(AuthService.id(),"workspace-epoch","workspace",json("{\"action\":\"git\",\"workspaceId\":\"missing\"}"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.workspaceCommand(owner,device,missing)).status);
  var invalid=new DesktopInteractions.Command(AuthService.id(),"workspace-epoch","workspace",json("{\"action\":\"shell\",\"workspaceId\":\"workspace\"}"));
  assertEquals(400,assertThrows(ApiException.class,()->interactions.workspaceCommand(owner,device,invalid)).status);
 }
 @Test void queueEditsRemainAccountScopedAndRejectEmptyReplacement(){
  String session=session();runtime("queue-epoch");
  var edit=new DesktopInteractions.Command(AuthService.id(),"queue-epoch","queueEdit",json("{\"itemId\":\"item\",\"action\":\"edit\",\"text\":\"Updated\"}"));
  interactions.enqueue(owner,session,edit);
  assertThrows(ApiException.class,()->interactions.enqueue(other,session,edit));
  var bad=new DesktopInteractions.Command(AuthService.id(),"queue-epoch","queueEdit",json("{\"itemId\":\"item\",\"action\":\"edit\",\"text\":\" \"}"));
  assertEquals(400,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,bad)).status);
  assertEquals(1,interactions.claim(owner,device,"queue-epoch").size());
  assertTrue(interactions.claim(owner,device,"queue-epoch").isEmpty());
 }
 @Test void claimsEachRemoteCommandOnceAndRetriesOnlyItsReceipt(){
  String session=session();runtime("epoch-1");var request=command(AuthService.id(),"epoch-1");
  interactions.enqueue(owner,session,request);interactions.enqueue(owner,session,request);
  assertEquals(1,interactions.claim(owner,device,"epoch-1").size());assertTrue(interactions.claim(owner,device,"epoch-1").isEmpty());
  var result=new DesktopInteractions.Result("epoch-1","accepted",json("{}"));interactions.acknowledge(owner,device,request.id(),result);interactions.acknowledge(owner,device,request.id(),result);
  assertEquals("accepted",interactions.commands(owner,session).get(0).get("status"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,new DesktopInteractions.Command(request.id(),"epoch-1","prompt",json("{\"text\":\"Different\",\"mode\":\"queue\"}")))).status);
 }
 @Test void runtimeReplacementExpiresQueuedCommandsAndMarksClaimedOnesUncertain(){
  String session=session();runtime("epoch-1");var claimed=command(AuthService.id(),"epoch-1");interactions.enqueue(owner,session,claimed);interactions.claim(owner,device,"epoch-1");var queued=command(AuthService.id(),"epoch-1");interactions.enqueue(owner,session,queued);
  runtime("epoch-2");assertTrue(interactions.claim(owner,device,"epoch-2").isEmpty());
  assertEquals(409,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,claimed.id(),new DesktopInteractions.Result("epoch-1","accepted",json("{}")))).status);
  var statuses=interactions.commands(owner,session).stream().map(row->row.get("status")).toList();assertTrue(statuses.contains("uncertain"));assertTrue(statuses.contains("expired"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,command(AuthService.id(),"epoch-1"))).status);
 }
 @Test void remoteCommandsEnforceAccountDeviceAndExpiry(){
  String session=session();runtime("epoch-1");
  assertEquals(404,assertThrows(ApiException.class,()->interactions.enqueue(other,session,command(AuthService.id(),"epoch-1"))).status);
  var request=command(AuthService.id(),"epoch-1");interactions.enqueue(owner,session,request);db.update("update sync_commands set expires_at=0 where id=?",request.id());assertTrue(interactions.claim(owner,device,"epoch-1").isEmpty());assertEquals("expired",interactions.commands(owner,session).get(0).get("status"));
  sync.revokeDevice(owner,device);assertEquals(false,interactions.state(owner,session).get("online"));assertEquals(404,assertThrows(ApiException.class,()->interactions.claim(owner,device,"epoch-1")).status);
 }
 @Test void decisionsRequireAStillPublishedNativeRequestAndOneShotOutcome(){
  String session=session();runtime("epoch-1");
  interactions.enqueue(owner,session,new DesktopInteractions.Command(AuthService.id(),"epoch-1","decision",json("{\"eventId\":\"decision-1\",\"outcome\":\"allowed-once\"}")));
  assertEquals(400,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,new DesktopInteractions.Command(AuthService.id(),"epoch-1","decision",json("{\"eventId\":\"decision-1\",\"outcome\":\"always\"}")))).status);
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,new DesktopInteractions.Command(AuthService.id(),"epoch-1","decision",json("{\"eventId\":\"old-decision\",\"outcome\":\"allowed-once\"}")))).status);
 }
 String attachment(String session,byte[] bytes) throws Exception {
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  JsonNode event=json(mapper.createObjectNode().put("seq",0).put("time",1).put("type","user/message").set("data",mapper.valueToTree(Map.of("content",List.of(Map.of("type","file","attachment",Map.of("attachmentId","sha256:"+hash,"bytes",bytes.length,"name","notes.txt")))))).toString());
  sync.append(owner,session,new DesktopHistorySync.Batch(device,List.of(event)));return hash;
 }
 @Test void attachmentsResumeByByteCursorAndPublishOnlyAfterHashVerification() throws Exception {
  String session=session();byte[] bytes="附件内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=attachment(session,bytes);
  String first=Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,0,2));String last=Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,2,bytes.length));
  assertEquals(false,attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,first)).get("complete"));
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(owner,session,hash,0)).status);
  assertEquals(2L,attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,first)).get("nextOffset"));
  assertEquals(true,attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,2,last)).get("complete"));
  assertEquals(first,attachments.download(owner,session,hash,0).get("data"));assertEquals(last,attachments.download(owner,session,hash,2).get("data"));
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(other,session,hash,0)).status);
 }
 @Test void corruptAttachmentAndMissingReferenceNeverBecomeDownloadable() throws Exception {
  String session=session();byte[] bytes="original".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=attachment(session,bytes);
  assertEquals("SYNC_ATTACHMENT_HASH_MISMATCH",assertThrows(ApiException.class,()->attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString("modified".getBytes())))).code);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(owner,session,hash,0)).status);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.upload(owner,session,"0".repeat(64),new DesktopAttachments.Chunk(device,0,""))).status);
  assertEquals(true,attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(bytes))).get("complete"));
 }
 @Test void mobileStagingResumesWithoutPublishingDesktopReferences() throws Exception {
  String session=session();byte[] bytes="mobile file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  var metadata=new DesktopAttachments.MobileFile(bytes.length,"notes.txt","text/plain");
  attachments.prepareMobile(owner,session,hash,metadata);
  String first=Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,0,3));
  String rest=Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,3,bytes.length));
  assertEquals(3L,attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(0,first)).get("nextOffset"));
  assertEquals(3L,attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(0,first)).get("nextOffset"));
  assertEquals(3L,attachments.prepareMobile(owner,session,hash,metadata).get("nextOffset"));
  assertEquals(false,attachments.mobileStatus(owner,session,hash).get("complete"));
  assertEquals("SYNC_ATTACHMENT_GAP",assertThrows(ApiException.class,()->attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(4,"YQ=="))).code);
  assertEquals(true,attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(3,rest)).get("complete"));
  assertTrue(((List<?>)attachments.manifest(owner,session).get("items")).isEmpty());
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(owner,session,hash,0)).status);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.mobileStatus(other,session,hash)).status);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.uploadMobile(other,session,hash,new DesktopAttachments.MobileChunk(0,first))).status);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.discardMobile(other,session,hash)).status);
  attachments.discardMobile(owner,session,hash);
  assertEquals(0,db.queryForObject("select count(*) from sync_attachment_objects where principal_id=? and hash=?",Integer.class,owner.id(),hash));
 }
 @Test void mobileStagingRejectsInvalidMetadataCorruptionAndConflicts() throws Exception {
  String session=session();String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("hello".getBytes()));
  assertEquals(400,assertThrows(ApiException.class,()->attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(5,"../secret","text/plain"))).status);
  assertEquals(413,assertThrows(ApiException.class,()->attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(33554433,"large","application/octet-stream"))).status);
  attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(5,"hello.txt","text/plain"));
  assertEquals(409,assertThrows(ApiException.class,()->attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(6,"hello.txt","text/plain"))).status);
  assertEquals("SYNC_ATTACHMENT_HASH_MISMATCH",assertThrows(ApiException.class,()->attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(0,"d29ybGQ="))).code);
  assertEquals(0L,attachments.mobileStatus(owner,session,hash).get("nextOffset"));
  assertEquals(false,attachments.mobileStatus(owner,session,hash).get("complete"));
  assertEquals(true,attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(0,"aGVsbG8=")).get("complete"));
 }
 @Test void discardMobilePreservesFilesReferencedByDesktopHistory() throws Exception {
  String session=session();byte[] bytes="shared".getBytes();String hash=attachment(session,bytes);
  attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(bytes)));
  assertEquals(true,attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(bytes.length,"shared.txt","text/plain")).get("complete"));
  attachments.discardMobile(owner,session,hash);
  assertEquals(Base64.getEncoder().encodeToString(bytes),attachments.download(owner,session,hash,0).get("data"));
 }
 @Test void mobileUploadRoutesAuthenticateAndHandleEmptyFiles() throws Exception {
  String token=AuthService.id(),tenant=AuthService.id(),source=AuthService.id();
  auth.create(AuthService.id(),tenant,"USER","HTTP",Set.of("VIEWER"),Set.of("sandbox"),token);
  Identity user=auth.authenticate(token);sync.registerDevice(user,new DesktopHistorySync.Device(source,"Mac","darwin"));
  String session=(String)sync.registerSession(user,new DesktopHistorySync.Registration(source,registration().header(),0)).get("id");
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]));
  String path="/api/account/sync/sessions/"+session+"/uploads/"+hash;
  http.perform(get(path)).andExpect(status().isUnauthorized());
  http.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"bytes\":0,\"name\":\"empty.txt\",\"mediaType\":\"text/plain\"}"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.complete").value(false));
  http.perform(post(path+"/chunks").header("Authorization","Bearer "+token).contentType("application/json").content("{\"offset\":0,\"data\":\"\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(true));
  http.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.metadata.name").value("empty.txt"));
  http.perform(delete(path).header("Authorization","Bearer "+token)).andExpect(status().isOk());
  http.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
 }
 @Test void pendingMobileUploadCapacityCanBeReleased(){
  String session=session();
  for(int i=0;i<100;i++)attachments.prepareMobile(owner,session,String.format("%064x",i),new DesktopAttachments.MobileFile(1,"file.txt","text/plain"));
  String extra="f".repeat(64);
  assertEquals("SYNC_UPLOAD_CAPACITY",assertThrows(ApiException.class,()->attachments.prepareMobile(owner,session,extra,new DesktopAttachments.MobileFile(1,"file.txt","text/plain"))).code);
  attachments.discardMobile(owner,session,"0".repeat(64));
  assertEquals(false,attachments.prepareMobile(owner,session,extra,new DesktopAttachments.MobileFile(1,"file.txt","text/plain")).get("complete"));
 }
 @Test void attachmentPromptRequiresAdvertisedSupportAndVerifiedStaging() throws Exception {
  String session=session();runtime("epoch-1");byte[] bytes="phone attachment".getBytes();
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  attachments.prepareMobile(owner,session,hash,new DesktopAttachments.MobileFile(bytes.length,"phone.txt","text/plain"));
  JsonNode payload=mapper.valueToTree(Map.of("text","","mode","queue","attachments",List.of(Map.of("hash",hash,"name","phone.txt","bytes",bytes.length,"mediaType","text/plain"))));
  var request=new DesktopInteractions.Command(AuthService.id(),"epoch-1","prompt",payload);
  assertEquals("SYNC_ATTACHMENTS_UNSUPPORTED",assertThrows(ApiException.class,()->interactions.enqueue(owner,session,request)).code);
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-1",json("{}"),json("[]"),true));
  assertEquals(true,interactions.state(owner,session).get("attachmentUploads"));
  assertEquals("SYNC_ATTACHMENT_INCOMPLETE",assertThrows(ApiException.class,()->interactions.enqueue(owner,session,request)).code);
  assertTrue(((List<?>)attachments.manifest(owner,session).get("items")).isEmpty());
  attachments.uploadMobile(owner,session,hash,new DesktopAttachments.MobileChunk(0,Base64.getEncoder().encodeToString(bytes)));
  assertEquals("queued",interactions.enqueue(owner,session,request).get("status"));
  attachments.discardMobile(owner,session,hash);
  assertEquals(Base64.getEncoder().encodeToString(bytes),attachments.download(owner,session,hash,0).get("data"));
  assertEquals("queued",interactions.enqueue(owner,session,request).get("status"));
  var delivered=interactions.claim(owner,device,"epoch-1");assertEquals(1,delivered.size());
  assertEquals(session,delivered.get(0).get("sessionId"));assertEquals(payload,delivered.get(0).get("payload"));
  runtime("epoch-2");assertEquals(false,interactions.state(owner,session).get("attachmentUploads"));
 }
 @Test void duplicateRegistrationAndLostAcknowledgementKeepOnePrefix(){
  String id=session();assertEquals(id,sync.registerSession(owner,registration()).get("id"));
  var batch=new DesktopHistorySync.Batch(device,List.of(event(0,"你好"),event(1,"Again")));
  assertEquals(2L,sync.append(owner,id,batch).get("nextSeq"));assertEquals(2L,sync.append(owner,id,batch).get("nextSeq"));
  assertEquals(2,db.queryForObject("select count(*) from sync_session_events where session_id=?",Integer.class,id));
  var page=sync.page(owner,id,0,1);assertEquals(List.of(event(0,"你好")),page.get("events"));assertEquals(1L,page.get("nextSeq"));assertEquals(2L,page.get("durableNextSeq"));
 }
 @Test void gapsAndConflictsRollBackWholeBatch(){
  String id=session();sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(0,"Original"))));
  assertEquals("SYNC_SEQUENCE_GAP",assertThrows(ApiException.class,()->sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(2,"Gap"))))).code);
  assertEquals("SYNC_EVENT_CONFLICT",assertThrows(ApiException.class,()->sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(0,"Changed"))))).code);
  assertEquals(400,assertThrows(ApiException.class,()->sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(1,"Pending"),event(3,"Bad"))))).status);
  assertEquals(1L,sync.page(owner,id,0,100).get("durableNextSeq"));
 }
 @Test void otherDeviceReadsButCannotWriteOriginalHostHistory(){
  String id=session(),windows=AuthService.id();sync.registerDevice(owner,new DesktopHistorySync.Device(windows,"Windows","win32"));
  sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(0,"Saved"))));
  assertEquals(1,((List<?>)sync.page(owner,id,0,100).get("events")).size());
  assertEquals(403,assertThrows(ApiException.class,()->sync.append(owner,id,new DesktopHistorySync.Batch(windows,List.of(event(1,"Wrong host"))))).status);
 }
 @Test void usersAndTenantsCannotReadOrClaimDevices(){
  String id=session();assertEquals(404,assertThrows(ApiException.class,()->sync.page(other,id,0,100)).status);
  assertEquals(404,assertThrows(ApiException.class,()->sync.registerDevice(other,new DesktopHistorySync.Device(device,"Taken","win32"))).status);
  Identity foreign=user(AuthService.id());assertEquals(404,assertThrows(ApiException.class,()->sync.page(foreign,id,0,100)).status);
  assertTrue(sync.list(other,"",100).isEmpty());
  sync.revokeDevice(owner,device);
  assertEquals(404,assertThrows(ApiException.class,()->sync.append(owner,id,new DesktopHistorySync.Batch(device,List.of(event(0,"Blocked"))))).status);
  assertEquals(false,sync.list(owner,"",100).get(0).get("online"));
 }
 @Test void headerConflictAndMalformedBatchDoNotChangeArchive(){
  assertEquals(403,assertThrows(ApiException.class,()->sync.registerDevice(owner,new DesktopHistorySync.Device(AuthService.id(),"Mac","darwin",other.id()))).status);
  session();var changed=new DesktopHistorySync.Registration(device,json("{\"id\":\"native\",\"version\":4,\"createdAt\":2,\"isSeeded\":false}"),0);
  assertEquals(409,assertThrows(ApiException.class,()->sync.registerSession(owner,changed)).status);
  assertEquals(400,assertThrows(ApiException.class,()->sync.registerSession(owner,new DesktopHistorySync.Registration(device,json("{}"),0))).status);
 }
 @Test void globalSearchHttpRequiresAuthenticationAndDoesNotCacheMessages() throws Exception {
  String token=AuthService.id(),tenant=AuthService.id();auth.create(AuthService.id(),tenant,"USER","HTTP",Set.of("VIEWER"),Set.of("sandbox"),token);
  var actor=auth.authenticate(token);String source=AuthService.id();
  sync.registerDevice(actor,new DesktopHistorySync.Device(source,"Mac","darwin"));
  String id=(String)sync.registerSession(actor,new DesktopHistorySync.Registration(source,registration().header(),0)).get("id");
  sync.append(actor,id,new DesktopHistorySync.Batch(source,List.of(event(0,"C++ 100% 中文"))));
  String path="/api/account/sync/search";
  http.perform(get(path).param("query","C++")).andExpect(status().isUnauthorized());
  http.perform(get(path).header("Authorization","Bearer "+token).param("query","C++"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.items[0].conversation.id").value(id)).andExpect(jsonPath("$.items[0].seq").value(0)).andExpect(jsonPath("$.hasMore").value(false));
  http.perform(get(path).header("Authorization","Bearer "+token).param("query"," ")).andExpect(status().isBadRequest());
 }
 @Test void conversationTitleHttpRequiresAuthenticationAndCurrentRevision() throws Exception {
  String token=AuthService.id(),tenant=AuthService.id();auth.create(AuthService.id(),tenant,"USER","HTTP",Set.of("VIEWER"),Set.of("sandbox"),token);
  var actor=auth.authenticate(token);String source=AuthService.id();
  sync.registerDevice(actor,new DesktopHistorySync.Device(source,"Mac","darwin"));
  String id=(String)sync.registerSession(actor,new DesktopHistorySync.Registration(source,registration().header(),0)).get("id");
  String path="/api/account/sync/sessions/"+id+"/title";
  http.perform(post(path).contentType("application/json").content("{\"title\":\"Name\",\"revision\":0}")).andExpect(status().isUnauthorized());
  http.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"title\":\"Name\",\"revision\":0}"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.title").value("Name")).andExpect(jsonPath("$.revision").value(1));
  http.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"title\":\"Stale\",\"revision\":0}")).andExpect(status().isConflict());
  http.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"title\":\"Missing revision\"}")).andExpect(status().isBadRequest());
  String archivePath="/api/account/sync/sessions/"+id+"/archive";
  http.perform(post(archivePath).contentType("application/json").content("{\"archived\":true,\"revision\":0}")).andExpect(status().isUnauthorized());
  http.perform(post(archivePath).header("Authorization","Bearer "+token).contentType("application/json").content("{\"archived\":true,\"revision\":0}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true)).andExpect(jsonPath("$.revision").value(1));
  http.perform(post(archivePath).header("Authorization","Bearer "+token).contentType("application/json").content("{\"archived\":false,\"revision\":0}")).andExpect(status().isConflict());
  http.perform(post(archivePath).header("Authorization","Bearer "+token).contentType("application/json").content("{\"revision\":1}")).andExpect(status().isBadRequest());
 }
 @Test void authenticatedHttpArchiveHasNoStoreAndRejectsMissingCredentials() throws Exception {
  String token=AuthService.id(),tenant=AuthService.id();auth.create(AuthService.id(),tenant,"USER","HTTP",Set.of("VIEWER"),Set.of("sandbox"),token);
  http.perform(get("/api/account/sync/sessions")).andExpect(status().isUnauthorized());
  http.perform(post("/api/account/sync/devices").header("Authorization","Bearer "+token).contentType("application/json").content("{\"id\":\""+AuthService.id()+"\",\"name\":\"Windows\",\"platform\":\"win32\"}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  http.perform(get("/api/account/sync/sessions").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().json("[]"));
 }
 @Test void stableDirectoryCursorDoesNotLoseRowsWithIdenticalTimes(){
  session();for(int i=0;i<3;i++)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json("{\"id\":\"native"+i+"\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0));
  var first=sync.list(owner,"",2);var second=sync.list(owner,(String)first.get(1).get("id"),2);
  assertEquals(2,first.size());assertEquals(2,second.size());assertNotEquals(first.get(1).get("id"),second.get(0).get("id"));
 }
 @Test void restoredDeviceNeedsFreshRuntimeAndDoesNotReviveCommands(){
  String session=session();runtime("epoch-1");interactions.enqueue(owner,session,command(AuthService.id(),"epoch-1"));
  sync.revokeDevice(owner,device);assertEquals("expired",interactions.commands(owner,session).get(0).get("status"));
  assertEquals(404,assertThrows(ApiException.class,()->sync.restoreDevice(other,device)).status);
  sync.restoreDevice(owner,device);assertEquals(false,interactions.state(owner,session).get("online"));
  runtime("epoch-2");assertTrue(interactions.claim(owner,device,"epoch-2").isEmpty());
 }
 @Test void attachmentManifestBoundsMetadataAndUsesStableHashPages(){
  String session=session();for(int i=0;i<101;i++){String hash=String.format("%064x",i);db.update("insert into sync_attachment_refs(session_id,hash,metadata_json) values(?,?,?)",session,hash,"{}");}
  var first=attachments.manifest(owner,session,"",null);assertEquals(100,((List<?>)first.get("items")).size());
  var second=attachments.manifest(owner,session,(String)first.get("after"),null);assertEquals(1,((List<?>)second.get("items")).size());assertEquals("",second.get("after"));
  assertEquals(1,((List<?>)attachments.manifest(owner,session,"",String.format("%064x",100)).get("items")).size());
 }

 @Test void archiveHeartbeatDoesNotRefreshStaleExecutionState(){
  String session=session();runtime("epoch-1");db.update("update sync_devices set state_seen=0 where id=?",device);
  sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));
  assertEquals(false,interactions.state(owner,session).get("online"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,session,command(AuthService.id(),"epoch-1"))).status);
 }

 @Test void deviceDirectoryMetadataDistinguishesStaleAndRevokedDevices() {
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-devices",json("{}"),json("[{\"id\":\"work\",\"title\":\"Work\"}]")));
  var active=interactions.devices(owner,"").get(0);
  assertEquals("darwin",active.get("platform"));assertEquals(false,active.get("revoked"));assertEquals(true,active.get("online"));
  assertTrue(((Number)active.get("lastSeen")).longValue()>0);
  assertTrue(interactions.devices(other,"").isEmpty());
  db.update("update sync_devices set state_seen=0 where id=?",device);
  var stale=interactions.devices(owner,"").get(0);assertEquals(false,stale.get("online"));assertEquals(false,stale.get("revoked"));
  assertEquals(0,((com.fasterxml.jackson.databind.JsonNode)stale.get("workspaces")).size());
  sync.revokeDevice(owner,device);
  var revoked=interactions.devices(owner,"").get(0);assertEquals(true,revoked.get("revoked"));assertEquals(false,revoked.get("online"));
 }

 @Test void remoteCreationUsesPublishedWorkspaceAndReturnsOwnedArchive() {
  interactions.publish(owner,device,new DesktopInteractions.State("epoch-create",json("{}"),json("[{\"id\":\"workspace-1\",\"title\":\"Work\"}]")));
  assertEquals(1,interactions.devices(owner,"").size());assertTrue(interactions.devices(other,"").isEmpty());
  var request=new DesktopInteractions.Command("create-request","epoch-create","create",json("{\"workspaceId\":\"workspace-1\"}"));
  assertEquals("queued",interactions.createSession(owner,device,request).get("status"));
  assertEquals("queued",interactions.createSession(owner,device,request).get("status"));
  assertEquals(404,assertThrows(ApiException.class,()->interactions.createSession(other,device,request)).status);
  assertEquals(409,assertThrows(ApiException.class,()->interactions.createSession(owner,device,new DesktopInteractions.Command("another","epoch-create","create",json("{\"workspaceId\":\"removed\"}")))).status);
  assertEquals(400,assertThrows(ApiException.class,()->interactions.createSession(owner,device,new DesktopInteractions.Command("path","epoch-create","create",json("{\"workspaceId\":\"workspace-1\",\"cwd\":\"/private\"}")))).status);
  assertEquals("create",interactions.claim(owner,device,"epoch-create").get(0).get("kind"));assertTrue(interactions.claim(owner,device,"epoch-create").isEmpty());
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,request.id(),new DesktopInteractions.Result("epoch-create","accepted",json("{\"sessionId\":\"missing\"}")))).status);
  String created=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json("{\"id\":\"mobile-create-request\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0)).get("id");
  var result=new DesktopInteractions.Result("epoch-create","accepted",mapper.valueToTree(Map.of("sessionId",created)));
  interactions.acknowledge(owner,device,request.id(),result);interactions.acknowledge(owner,device,request.id(),result);
  assertEquals("accepted",interactions.deviceCommand(owner,device,request.id()).get("status"));
  assertEquals("missing",interactions.deviceCommand(owner,device,"absent").get("status"));
 }
 @Test void oldHostsAndExpiredCreationCannotCreateSessions() {
  runtime("old-host");
  assertEquals(409,assertThrows(ApiException.class,()->interactions.createSession(owner,device,new DesktopInteractions.Command("not-supported","old-host","create",json("{\"workspaceId\":\"workspace-1\"}")))).status);
  interactions.publish(owner,device,new DesktopInteractions.State("new-host",json("{}"),json("[{\"id\":\"workspace-1\",\"title\":\"Work\"}]")));
  var request=new DesktopInteractions.Command("expire-create","new-host","create",json("{\"workspaceId\":\"workspace-1\"}"));
  interactions.createSession(owner,device,request);db.update("update sync_commands set expires_at=0 where id=?",request.id());
  assertEquals("expired",interactions.deviceCommand(owner,device,request.id()).get("status"));assertTrue(interactions.claim(owner,device,"new-host").isEmpty());
  assertEquals("expired",interactions.createSession(owner,device,request).get("status"));
 }

 @Test void creationHttpCatalogAndRequestRequireAuthentication() throws Exception {
  String token=AuthService.id(),tenant=AuthService.id(),source=AuthService.id();
  auth.create(AuthService.id(),tenant,"USER","HTTP",Set.of("VIEWER"),Set.of("sandbox"),token);
  Identity user=auth.authenticate(token);
  sync.registerDevice(user,new DesktopHistorySync.Device(source,"Mac","darwin"));
  http.perform(get("/api/account/sync/devices")).andExpect(status().isUnauthorized());
  http.perform(post("/api/account/sync/devices/"+source+"/state").header("Authorization","Bearer "+token).contentType("application/json")
   .content("{\"epoch\":\"http-epoch\",\"sessions\":{},\"workspaces\":[{\"id\":\"work\",\"title\":\"Work\"}]}"))
   .andExpect(status().isOk());
  http.perform(get("/api/account/sync/devices").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$[0].workspaces[0].id").value("work"));
  http.perform(post("/api/account/sync/devices/"+source+"/commands").header("Authorization","Bearer "+token).contentType("application/json")
   .content("{\"id\":\"http-create\",\"epoch\":\"http-epoch\",\"kind\":\"create\",\"payload\":{\"workspaceId\":\"work\"}}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("queued"));
  http.perform(get("/api/account/sync/devices/"+source+"/commands/http-create").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("queued"));
 }

}
