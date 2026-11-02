package com.funnywork;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:mobile-chat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.model-inference-enabled=true"})
class MobileChatsIntegrationTest {
 static final AtomicInteger calls=new AtomicInteger();static HttpServer upstream;static String origin;
 static {try{upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+upstream.getAddress().getPort();upstream.createContext("/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();var bytes=("data: {\"choices\":[{\"delta\":{\"content\":\"手机独立回复\"}}]}\n\ndata: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});upstream.createContext("/slow/chat/completions",exchange->{
  exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
  exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"开始\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
  try{Thread.sleep(300);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
  exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"后台完成\"}}]}\n\ndata: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));exchange.close();
 });upstream.createContext("/no-usage/chat/completions",exchange->{
  var body=new ObjectMapper().readTree(exchange.getRequestBody());if(body.has("stream_options")){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
  byte[] reply="data: {\"choices\":[{\"delta\":{\"content\":\"兼容回复\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
 });upstream.createContext("/messages",exchange->{
  exchange.getRequestBody().readAllBytes();byte[] reply=("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private reasoning\"}}\n\n"+
   "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Messages reply\"}}\n\n"+
   "data: {\"type\":\"message_stop\"}\n\n").getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
 });upstream.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("funnywork.model-inference-origins",()->origin);}
 @AfterAll static void close(){upstream.stop(0);}
 @Autowired ModelBudgets budgets;@Autowired MobileChats chats;@Autowired AuthService auth;@Autowired ModelMiddleware models;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @MockitoBean DesktopModelReleases releases;
 @LocalServerPort int port;
 String token,otherToken,route;Identity operator,user,admin;
 @BeforeEach void setup(){if(db.queryForObject("select count(*) from principals where id='bootstrap-admin'",Integer.class)==0)auth.create("bootstrap-admin","budget-operator","USER","Budget operator",Set.of("ADMIN"),Set.of("sandbox"),"mobile-fixture-budget-operator");operator=auth.authenticate("mobile-fixture-budget-operator");String tenant=AuthService.id();String adminToken=AuthService.id();token=AuthService.id();otherToken=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),adminToken);auth.create(AuthService.id(),tenant,"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),token);auth.create(AuthService.id(),tenant,"USER","Other",Set.of("VIEWER"),Set.of("sandbox"),otherToken);user=auth.authenticate(token);when(releases.resolve(any())).thenThrow(new ApiException(503,"MODEL_NOT_CONFIGURED","Not configured"));admin=auth.authenticate(adminToken);db.update("update model_budget_guard set total_limit=1000000000000,daily_limit=1000000000000,paused=false where id=1");budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(1000000000000L,1000000000000L,32,600,8192,System.currentTimeMillis()+86400000,true,0));String pool=(String)models.savePool(admin,null,new ModelMiddleware.Pool("Chat","sandbox",1,10,1,3,120000,false,0)).get("id");route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Private Qwen","qwen","CHAT_COMPLETIONS",origin,"",65536,1024,Map.of("streamUsage",true),true))).get("id");}
 Map<String,Object> priced(Map<String,Object> created){budgets.savePrice(operator,(String)created.get("id"),new ModelBudgets.Price(1000000,2000000,2000000,0));return created;}
 @Test void messagesCatalogThinkingAndFinalReplyWorkThroughRealHttp()throws Exception {
  String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,route);
  route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"DeepSeek Pro","deepseek-v4-pro","MESSAGES",origin,"",1000000,1024,Map.of("reasoning",true),true))).get("id");
  var catalog=json.readTree(request(token,"/models",null).body()).path("items");
  var option=java.util.stream.StreamSupport.stream(catalog.spliterator(),false).filter(x->x.path("id").asText().equals(route)).findFirst().orElseThrow();
  assertEquals(4,option.path("reasoningEfforts").size());assertEquals("MESSAGES",option.path("protocol").asText());
  String chat=AuthService.id(),turn=AuthService.id();var body=new LinkedHashMap<String,Object>(send(turn,"hello",0));body.put("reasoningEffort","max");
  assertEquals(200,request(token,"/"+chat+"/turns",body).statusCode());
  var wire=json.readTree(db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,turn));
  assertEquals("max",wire.path("output_config").path("effort").asText());assertFalse(wire.has("stream_options"));
  var changed=new LinkedHashMap<>(body);changed.put("reasoningEffort","off");assertEquals(409,request(token,"/"+chat+"/turns",changed).statusCode());
  assertEquals(200,request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of()).statusCode());
  assertEquals("Messages reply",db.queryForObject("select reply_text from mobile_chat_turns where id=?",String.class,turn));
 }
 @Test void perTurnOutputBudgetIsBoundedAndPartOfRequestIdentity()throws Exception {
  String chat=AuthService.id(),turn=AuthService.id();var body=new LinkedHashMap<String,Object>(send(turn,"hello",0));body.put("maxOutputTokens",128);
  assertEquals(200,request(token,"/"+chat+"/turns",body).statusCode());
  assertEquals(128,json.readTree(db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,turn)).path("max_tokens").asInt());
  body.put("maxOutputTokens",256);assertEquals(409,request(token,"/"+chat+"/turns",body).statusCode());
  body=new LinkedHashMap<>(send(AuthService.id(),"hello",0));body.put("maxOutputTokens",2048);
  assertEquals(400,request(token,"/"+AuthService.id()+"/turns",body).statusCode());
 }
 @Test void mobileUsesDeploymentOutputBeyondLegacyFourKLimit()throws Exception{
  String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,route);
  route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Long output","qwen","CHAT_COMPLETIONS",origin,"",1000000,8192,Map.of("streamUsage",true),true))).get("id");
  String turn=AuthService.id();
  assertEquals(200,request(token,"/"+AuthService.id()+"/turns",send(turn,"hello",0)).statusCode());
  assertEquals(8192,json.readTree(db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,turn)).path("max_tokens").asInt());
 }
 @Test void smallerPersonalOutputLimitIsAppliedBeforeHashingAndExecution()throws Exception{
  budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(1000000000000L,1000000000000L,32,600,128,System.currentTimeMillis()+86400000,true,1));
  String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,route);
  route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Output policy","qwen","CHAT_COMPLETIONS",origin,"",65536,2048,Map.of("streamUsage",true),true))).get("id");
  assertEquals(128L,((Number)models.catalog(user).stream().filter(row->route.equals(row.get("id"))).findFirst().orElseThrow().get("max_output_tokens")).longValue());
  String chat=AuthService.id(),turn=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"hello",0)).statusCode());
  assertEquals(128,json.readTree(db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,turn)).path("max_tokens").asInt());
  assertEquals(200,request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of()).statusCode());
 }
 HttpResponse<String> request(String credential,String path,Object body)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/account/chats"+path)).header("Authorization","Bearer "+credential);if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());}
 Map<String,Object> send(String id,String text,long revision){return Map.of("id",id,"text",text,"modelId",route,"expectedRevision",revision);}
 String seedHistory(Identity actor,String title,long time){String id=AuthService.id();db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,?,?,?,?,0,?,?)",id,actor.tenant(),actor.id(),title,route,time,time);return id;}
 @Test void historyPaginationTraversesBeyondOneHundredWithEqualTimestampsAndAccountIsolation()throws Exception{
  var expected=new HashSet<String>();for(int i=0;i<115;i++)expected.add(seedHistory(user,"History "+i,i<70?1000:900));
  seedHistory(auth.authenticate(otherToken),"Private other",2000);
  var found=new HashSet<String>();String cursor="";int pages=0;
  do{
   var response=request(token,"?history=true&after="+cursor,null);assertEquals(200,response.statusCode());var page=json.readTree(response.body());assertTrue(page.path("historyManagement").asBoolean());
   assertTrue(page.path("items").size()<=50);for(var row:page.path("items"))assertTrue(found.add(row.path("id").asText()));
   cursor=page.path("next").asText();pages++;
  }while(!cursor.isEmpty());
  assertEquals(expected,found);assertEquals(3,pages);
  assertEquals(100,json.readTree(request(token,"",null).body()).path("items").size());
 }
 @Test void historySearchMatchesBodyLiterallyAndCursorCannotChangeQueryOrArchiveScope()throws Exception{
  String bodyChat=seedHistory(user,"Unrelated title",1000),titleChat=seedHistory(user,"Literal 50%_!",900);
  db.update("insert into mobile_chat_turns values(?,?,0,'hash','question','answer 50%_!', '{}',?,'SUCCEEDED',1)",AuthService.id(),bodyChat,AuthService.id());
  seedHistory(user,"Literal 50XYZ",800);
  var rows=json.readTree(request(token,"?history=true&query="+URLEncoder.encode("50%_!",StandardCharsets.UTF_8),null).body()).path("items");
  assertEquals(Set.of(bodyChat,titleChat),new HashSet<>(java.util.stream.StreamSupport.stream(rows.spliterator(),false).map(row->row.path("id").asText()).toList()));
  for(int i=0;i<51;i++)seedHistory(user,"Paged",100);
  String cursor=json.readTree(request(token,"?history=true",null).body()).path("next").asText();assertFalse(cursor.isEmpty());
  assertEquals(400,request(token,"?history=true&archived=true&after="+cursor,null).statusCode());
  assertEquals(400,request(token,"?history=true&query=other&after="+cursor,null).statusCode());
  assertEquals(400,request(token,"?history=true&after=invalid",null).statusCode());
 }
 @Test void searchMatchReturnsLatestOwnedTurnAndBoundedUnicodeExcerpt()throws Exception {
  String owned=seedHistory(user,"A title without query",1200),hidden=seedHistory(auth.authenticate(otherToken),"hidden",1300);
  String older=AuthService.id(),latest=AuthService.id();
  db.update("insert into mobile_chat_turns values(?,?,0,'hash','old needle','older reply','{}',?,'SUCCEEDED',100)",older,owned,AuthService.id());
  String reply="😀".repeat(150)+" needle "+"尾".repeat(300);
  db.update("insert into mobile_chat_turns values(?,?,1,'hash','another question',?,'{}',?,'SUCCEEDED',200)",latest,owned,reply,AuthService.id());
  db.update("insert into mobile_chat_turns values(?,?,0,'hash','needle','private answer','{}',?,'SUCCEEDED',300)",AuthService.id(),hidden,AuthService.id());
  String title=seedHistory(user,"needle in title",1100);
  var page=json.readTree(request(token,"?history=true&query=needle",null).body());assertTrue(page.path("searchHits").asBoolean());
  assertEquals(2,page.path("items").size());
  var hit=page.path("items").get(0).path("search_match");assertEquals(latest,hit.path("turnId").asText());assertEquals("reply",hit.path("field").asText());
  String excerpt=hit.path("excerpt").asText();assertTrue(excerpt.contains("needle"));assertTrue(excerpt.length()<=500);assertFalse(excerpt.contains("private answer"));
  for(int i=0;i<excerpt.length();i++)if(Character.isSurrogate(excerpt.charAt(i))){assertTrue(Character.isHighSurrogate(excerpt.charAt(i)));assertTrue(i+1<excerpt.length()&&Character.isLowSurrogate(excerpt.charAt(++i)));}
  var titleHit=page.path("items").get(1);assertEquals(title,titleHit.path("id").asText());assertEquals("title",titleHit.path("search_match").path("field").asText());assertFalse(titleHit.path("search_match").has("turnId"));
  assertFalse(json.readTree(request(token,"?history=true",null).body()).path("items").get(0).has("search_match"));
 }
 @Test void metadataUsesSeparateRevisionIdempotentReceiptsAndOwnership()throws Exception{
  String chat=seedHistory(user,"Original",1000),id=AuthService.id();
  var rename=Map.of("id",id,"expectedHistoryRevision",0,"title","Renamed");
  var first=request(token,"/"+chat+"/history",rename);assertEquals(200,first.statusCode());assertEquals(1,json.readTree(first.body()).path("history_revision").asLong());
  assertEquals(0,db.queryForObject("select revision from mobile_chats where id=?",Long.class,chat));
  assertEquals(200,request(token,"/"+chat+"/history",rename).statusCode());
  assertEquals(409,request(token,"/"+chat+"/history",Map.of("id",id,"expectedHistoryRevision",0,"title","Different")).statusCode());
  assertEquals(409,request(token,"/"+chat+"/history",Map.of("id",AuthService.id(),"expectedHistoryRevision",0,"archived",true)).statusCode());
  assertEquals(404,request(otherToken,"/"+chat+"/history",rename).statusCode());
  assertEquals(404,request(otherToken,"/"+chat+"/history",null).statusCode());
  assertEquals(200,request(token,"/"+chat+"/history",Map.of("id",AuthService.id(),"expectedHistoryRevision",1,"archived",true)).statusCode());
  assertEquals(1,json.readTree(request(token,"/"+chat+"/history",rename).body()).path("history_revision").asLong());
  assertEquals(2,db.queryForObject("select history_revision from mobile_chats where id=?",Long.class,chat));
  assertEquals(2,json.readTree(request(token,"/"+chat+"/history",null).body()).path("history_revision").asLong());
  assertEquals(0,json.readTree(request(token,"",null).body()).path("items").size());
  assertEquals(chat,json.readTree(request(token,"?history=true&archived=true",null).body()).path("items").get(0).path("id").asText());
 }
 @Test void archivedRunningChatFinishesAndOnlyRestoreAllowsNewTurns()throws Exception{
  String chat=AuthService.id(),turn=AuthService.id();var original=send(turn,"Run then archive",0);
  assertEquals(200,request(token,"/"+chat+"/turns",original).statusCode());String ticket=chats.ticket(user,chat,turn);Object state=models.readTicket(user,ticket).get("state");
  assertEquals(200,request(token,"/"+chat+"/history",Map.of("id",AuthService.id(),"expectedHistoryRevision",0,"archived",true)).statusCode());
  assertEquals(state,models.readTicket(user,ticket).get("state"));
  assertEquals(200,request(token,"/"+chat+"/turns",original).statusCode());
  assertEquals(200,request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of()).statusCode());
  assertEquals(409,request(token,"/"+chat+"/turns",send(AuthService.id(),"Next",1)).statusCode());
  assertEquals(200,request(token,"/"+chat+"/history",Map.of("id",AuthService.id(),"expectedHistoryRevision",1,"archived",false)).statusCode());
  assertEquals(200,request(token,"/"+chat+"/turns",send(AuthService.id(),"Next",1)).statusCode());
 }
 @Test void blankOrOversizedTitleAndUnspecifiedUpdateAreRejected()throws Exception{
  String chat=seedHistory(user,"Original",1000);
  for(var body:List.of(Map.of("id",AuthService.id(),"expectedHistoryRevision",0,"title","  "),Map.of("id",AuthService.id(),"expectedHistoryRevision",0,"title","x".repeat(161)),Map.of("id",AuthService.id(),"expectedHistoryRevision",0)))
   assertEquals(400,request(token,"/"+chat+"/history",body).statusCode());
 }
 @Test void independentStreamingPersistsHistoryAndNeverExecutesTwice()throws Exception{
  String chat=AuthService.id(),turn=AuthService.id();int before=calls.get();var body=send(turn,"你好",0);
  assertEquals(200,request(token,"/"+chat+"/turns",body).statusCode());assertEquals(200,request(token,"/"+chat+"/turns",body).statusCode());
  assertEquals(404,request(otherToken,"/"+chat,null).statusCode());
  var executed=request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of());assertEquals(200,executed.statusCode());assertTrue(executed.body().contains("手机独立回复"));
  assertEquals(409,request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of()).statusCode());assertEquals(before+1,calls.get());
  var snapshot=json.readTree(request(token,"/"+chat,null).body());assertEquals("手机独立回复",snapshot.path("turns").get(0).path("reply_text").asText());assertEquals("SUCCEEDED",snapshot.path("ticket").path("state").asText());assertEquals(10,snapshot.path("turns").get(0).path("input_tokens").asLong());assertEquals("Private Qwen",snapshot.path("turns").get(0).path("model_name").asText());assertEquals("qwen",snapshot.path("turns").get(0).path("model").asText());var perf=snapshot.path("turns").get(0).path("performance");assertTrue(perf.has("request_ms"));assertTrue(perf.has("first_output_ms"));assertTrue(perf.path("first_output_ms").asLong()>=0);assertEquals(perf.path("total_ms").asLong(),perf.path("queue_ms").asLong()+perf.path("dispatch_ms").asLong()+perf.path("request_ms").asLong());
  assertEquals(0,db.queryForObject("select count(*) from sync_devices where tenant_id=?",Integer.class,user.tenant()));
  assertEquals(409,request(token,"/"+chat+"/turns",send(turn,"修改正文",0)).statusCode());
  String next=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(next,"继续",1)).statusCode());String payload=db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,next);assertEquals(3,json.readTree(payload).path("messages").size());assertFalse(json.readTree(payload).has("tools"));
 }
 @Test void anonymousRequestsCannotEnterChatOrInference()throws Exception{
  for(String path:List.of("/api/account/chats/chat/turns/turn/execute","/api/account/inference/tickets/ticket/execute")){
   var anonymous=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
   assertEquals(401,HttpClient.newHttpClient().send(anonymous,HttpResponse.BodyHandlers.ofString()).statusCode());
  }
  assertEquals(401,request("invalid","/models",null).statusCode());
  assertEquals(401,request("invalid","/chat/turns/turn/execute",Map.of()).statusCode());
 }
 @Test void queuedTurnCancelsBeforeAnyInferenceAndIsTenantIsolated()throws Exception{
  String first=AuthService.id();assertEquals(200,request(token,"/"+AuthService.id()+"/turns",send(first,"占用",0)).statusCode());
  String chat=AuthService.id(),turn=AuthService.id();int before=calls.get();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"等待",0)).statusCode());
  assertEquals("WAITING",json.readTree(request(token,"/"+chat,null).body()).path("ticket").path("state").asText());
  assertEquals(200,request(token,"/"+chat+"/turns/"+turn+"/cancel",Map.of()).statusCode());assertEquals("CANCELLED",json.readTree(request(token,"/"+chat,null).body()).path("ticket").path("state").asText());assertEquals(before,calls.get());
 }
 @Test void mobileDisconnectDoesNotLoseTheServerReplyOrRepeatInference()throws Exception{
  String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,route);
  route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Slow Qwen","qwen","CHAT_COMPLETIONS",origin+"/slow","",65536,1024,Map.of("streamUsage",true),true))).get("id");
  String chat=AuthService.id(),turn=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"后台继续",0)).statusCode());
  try(var socket=new java.net.Socket("127.0.0.1",port)){
   socket.setSoTimeout(5000);String http="POST /api/account/chats/"+chat+"/turns/"+turn+"/execute HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
   socket.getOutputStream().write(http.getBytes(StandardCharsets.UTF_8));assertTrue(socket.getInputStream().read()!=-1);socket.setSoLinger(true,0);
  }
  JsonNode snapshot=null;
  for(int i=0;i<60;i++){snapshot=json.readTree(request(token,"/"+chat,null).body());if("SUCCEEDED".equals(snapshot.path("turns").get(0).path("state").asText()))break;Thread.sleep(50);}
  assertEquals("SUCCEEDED",snapshot.path("ticket").path("state").asText());assertEquals("开始后台完成",snapshot.path("turns").get(0).path("reply_text").asText());
 }
 @Test void publishedPublicModelSharesAdmissionButDoesNotExposeCredentials()throws Exception{
  doReturn(new DesktopModelPublication.Configuration(user.tenant(),"","","https://api.deepseek.com","deepseek-chat","test-only-secret","disabled",1024,1)).when(releases).resolve(any());
  var response=request(token,"/models",null);assertEquals(200,response.statusCode());assertFalse(response.body().contains("test-only-secret"));assertFalse(response.body().contains("credential_ref"));assertTrue(models.catalog(user).stream().noneMatch(row->((String)row.get("id")).startsWith("chat-public-")));
  var items=json.readTree(response.body()).path("items");String published="";for(var item:items)if(item.path("id").asText().startsWith("chat-public-"))published=item.path("id").asText();assertFalse(published.isEmpty());
  assertEquals(1,db.queryForObject("select count(*) from model_capacity_pools where tenant_id=? and id like 'chat-pool-%'",Integer.class,user.tenant()));
 }
 @Test void unsupportedStreamUsageIsOmittedAndMissingUsageRemainsUnknown()throws Exception{
  String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,route);
  route=(String)priced(models.createDeployment(admin,new ModelMiddleware.Deployment(pool,"No usage","qwen","CHAT_COMPLETIONS",origin+"/no-usage","",65536,1024,Map.of("streamUsage",false),true))).get("id");
  String chat=AuthService.id(),turn=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"兼容",0)).statusCode());
  assertFalse(json.readTree(db.queryForObject("select request_json from mobile_chat_turns where id=?",String.class,turn)).has("stream_options"));
  assertEquals(200,request(token,"/"+chat+"/turns/"+turn+"/execute",Map.of()).statusCode());
  var snapshot=json.readTree(request(token,"/"+chat,null).body());assertEquals("兼容回复",snapshot.path("turns").get(0).path("reply_text").asText());assertEquals("SUCCEEDED",snapshot.path("ticket").path("state").asText());assertEquals("UNKNOWN",snapshot.path("ticket").path("usage_quality").asText());assertTrue(snapshot.path("ticket").path("input_tokens").isNull());
 }
 @Test void replyFailureRollsBackLedgerAndCanCommitTogetherOnRetry()throws Exception{
  String chat=AuthService.id(),turn=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"事务",0)).statusCode());
  String ticket=chats.ticket(user,chat,turn);models.begin(user,ticket,chats.payload(user,chat,turn));
  var observer=chats.observer(turn);byte[] reply="data: {\"choices\":[{\"delta\":{\"content\":\"原子回复\"}}]}\n".getBytes(StandardCharsets.UTF_8);observer.bytes(reply,reply.length);
  var usage=new ModelUsage(json,"CHAT_COMPLETIONS");usage.response("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes(StandardCharsets.UTF_8));
  db.execute("alter table mobile_chat_turns add constraint fail_reply check(id <> '"+turn+"' or reply_text <> '原子回复')");
  try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->observer.complete("SUCCEEDED",()->models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null)));assertEquals("RUNNING",models.readTicket(user,ticket).get("state"));assertEquals("PREPARED",db.queryForObject("select state from mobile_chat_turns where id=?",String.class,turn));}
  finally{db.execute("alter table mobile_chat_turns drop constraint fail_reply");}
  observer.complete("SUCCEEDED",()->models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null));
  assertEquals("SUCCEEDED",models.readTicket(user,ticket).get("state"));assertEquals("原子回复",db.queryForObject("select reply_text from mobile_chat_turns where id=?",String.class,turn));
 }
 @Test void orphanedSuccessfulLedgerIsMarkedFailedWithoutReplayingAndChatCanContinue()throws Exception{
  String chat=AuthService.id(),turn=AuthService.id();assertEquals(200,request(token,"/"+chat+"/turns",send(turn,"恢复",0)).statusCode());String ticket=chats.ticket(user,chat,turn);
  models.begin(user,ticket,chats.payload(user,chat,turn));models.finish(ticket,"SUCCEEDED","COMPLETED",new ModelUsage(json,"CHAT_COMPLETIONS"),null);
  int before=calls.get();var snapshot=json.readTree(request(token,"/"+chat,null).body());assertEquals("FAILED",snapshot.path("turns").get(0).path("state").asText());assertEquals(before,calls.get());
  assertEquals(200,request(token,"/"+chat+"/turns",send(AuthService.id(),"继续",1)).statusCode());
 }

}
