package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:mcp-bindings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.mcp-hosts=127.0.0.1","FUNNYWORK_MCP_FIXTURE=fixture-mcp"})
class McpBindingsIntegrationTest {
 @Autowired org.springframework.jdbc.core.JdbcTemplate db;
 @Autowired McpBindings mcp;@Autowired ToolRegistry tools;@Autowired ControlService control;@Autowired AuthService auth;@Autowired ObjectMapper mapper;
 Identity admin,owner,node;String tool,attempt,task;Runnable onRead=()->{},onCatalog=()->{};long epoch;HttpServer server;AtomicInteger calls;ToolContract contract;
 @BeforeEach void setup()throws Exception{
  String tenant=AuthService.id();admin=user(tenant,"ADMIN");owner=user(tenant,"OPERATOR");contract=new ToolContract("fixture.mcp.read",ToolContract.Effect.READ_ONLY,Map.of("query",new ToolContract.Field(ToolContract.Kind.STRING,true,100)),"vault://fixture/mcp","fixture.read");
  tool=(String)tools.publish(admin,new ToolRegistry.Publish(contract,"1.0.0","sandbox")).get("toolVersionId");String agent=(String)control.publishAgent(admin,new Requests.Agent("mcp","1.0.0","model.read.v1")).get("agentId");
  task=(String)control.createTask(owner,AuthService.id(),new Requests.Task(agent,"sandbox",List.of("Read a record"))).get("taskId");tools.grant(admin,task,tool,true);
  var registration=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));node=auth.authenticate((String)registration.get("token"));control.heartbeat(node,true);control.tick();var work=control.poll(node);attempt=(String)work.get("attemptId");epoch=((Number)work.get("epoch")).longValue();
  calls=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/mcp",exchange->{
   assertEquals("Bearer fixture-mcp",exchange.getRequestHeaders().getFirst("Authorization"));
   if(exchange.getRequestMethod().equals("DELETE")){exchange.sendResponseHeaders(200,-1);exchange.close();return;}
   var body=mapper.readTree(exchange.getRequestBody());String method=body.path("method").asText();if(method.equals("notifications/initialized")){assertEquals("fixture-session",exchange.getRequestHeaders().getFirst("MCP-Session-Id"));exchange.sendResponseHeaders(202,-1);exchange.close();return;}
   Object result;if(method.equals("initialize")){exchange.getResponseHeaders().set("MCP-Session-Id","fixture-session");result=Map.of("protocolVersion",McpHttpClient.PROTOCOL,"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));}
   else{assertEquals("fixture-session",exchange.getRequestHeaders().getFirst("MCP-Session-Id"));if(method.equals("tools/list")){onCatalog.run();result=Map.of("tools",List.of(Map.of("name","records.read","inputSchema",contract.inputSchema())));}else{assertEquals("tools/call",method);calls.incrementAndGet();onRead.run();result=Map.of("content",List.of(Map.of("type","text","text","Read result")),"isError",false);}}
   String response=mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",body.path("id").intValue(),"result",result));boolean stream=method.equals("tools/call");byte[] bytes=(stream?": heartbeat\n\nevent: message\ndata: "+response+"\n\n":response).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();
 }
 Identity user(String tenant,String role){String secret=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),secret);return auth.authenticate(secret);}
 McpBindings.Config config(boolean enabled,int version){return new McpBindings.Config(tool,"http://127.0.0.1:"+server.getAddress().getPort()+"/mcp","records.read","FUNNYWORK_MCP_FIXTURE",enabled,version);}
 @AfterEach void close(){if(server!=null)server.stop(0);}
 @Test void candidatesExposeOnlyAvailableReadOnlyMetadata(){
  String write=(String)tools.publish(admin,new ToolRegistry.Publish(new ToolContract("fixture.write",ToolContract.Effect.IDEMPOTENT_WRITE,Map.of(),"vault://private","write"),"1.0.0","sandbox")).get("toolVersionId");
  var found=new ArrayList<String>();String after="";do{var page=mcp.candidates(admin,"sandbox",after,1);for(var item:(List<?>)page.get("items")){var row=(Map<?,?>)item;found.add((String)row.get("id"));assertFalse(row.containsKey("contract_json"));assertFalse(row.containsKey("credentialRef"));}after=(String)page.getOrDefault("nextCursor","");}while(!after.isEmpty());
  assertEquals(List.of(tool),found);assertFalse(found.contains(write));
  assertThrows(ApiException.class,()->mcp.candidates(owner,"sandbox","",50));
  assertThrows(ApiException.class,()->mcp.candidates(admin,"other","",50));
  assertEquals(List.of(),mcp.candidates(user(AuthService.id(),"ADMIN"),"sandbox","",50).get("items"));
  mcp.save(admin,config(true,0));assertEquals(List.of(),mcp.candidates(admin,"sandbox","",50).get("items"));
  assertEquals("fixture.mcp.read",((Map<?,?>)((List<?>)mcp.list(admin,"sandbox","",50).get("items")).get(0)).get("tool_name"));
 }
 @Test void checksHandshakeAndContractWithoutInvokingToolAndRejectsChangedVersion(){
  mcp.save(admin,config(true,0));var result=mcp.check(admin,tool,1);assertEquals(true,result.get("schemaMatched"));assertEquals(false,result.get("toolInvoked"));assertEquals(0,calls.get());
  assertThrows(ApiException.class,()->mcp.check(owner,tool,1));
  assertThrows(ApiException.class,()->mcp.check(user(AuthService.id(),"ADMIN"),tool,1));
  onCatalog=()->mcp.save(admin,config(false,1));
  assertEquals("MCP_VERSION_CONFLICT",assertThrows(ApiException.class,()->mcp.check(admin,tool,1)).code);assertEquals(0,calls.get());
 }
 @Test void checkDoesNotConfirmAfterAdministratorRevocation(){
  mcp.save(admin,config(true,0));onCatalog=()->db.update("update principals set enabled=false where id=?",admin.id());
  assertThrows(ApiException.class,()->mcp.check(admin,tool,1));assertEquals(0,calls.get());
  assertEquals(0,db.queryForObject("select count(*) from events where tenant_id=? and event_type='MCP_BINDING_CHECKED'",Integer.class,admin.tenant()));
 }
 @Test void checkRejectsSchemaDriftAndDisabledTools(){
  mcp.save(admin,config(true,0));contract=new ToolContract("fixture.mcp.read",ToolContract.Effect.READ_ONLY,Map.of(),"vault://fixture/mcp","fixture.read");
  assertEquals("MCP_SCHEMA_CHANGED",assertThrows(ApiException.class,()->mcp.check(admin,tool,1)).code);assertEquals(0,calls.get());
  tools.disable(admin,tool);assertEquals("TOOL_DISABLED",assertThrows(ApiException.class,()->mcp.check(admin,tool,1)).code);
  assertEquals("TOOL_DISABLED",assertThrows(ApiException.class,()->mcp.save(admin,config(true,1))).code);
 }
 @Test void realHandshakeCatalogAndSseReadAreLeaseBound(){mcp.save(admin,config(true,0));assertEquals(List.of(tool),mcp.catalog(node,attempt,epoch));var value=mcp.call(node,attempt,tool,new McpBindings.Call(epoch,Map.of("query","test")));assertEquals("Read result",value.get("text"));assertEquals(false,value.get("businessVerified"));assertEquals(1,calls.get());
  assertThrows(ApiException.class,()->mcp.call(owner,attempt,tool,new McpBindings.Call(epoch,Map.of("query","test"))));assertThrows(ApiException.class,()->mcp.call(node,attempt,tool,new McpBindings.Call(epoch+1,Map.of("query","test"))));
  mcp.save(admin,config(false,1));assertTrue(mcp.catalog(node,attempt,epoch).isEmpty());assertThrows(ApiException.class,()->mcp.call(node,attempt,tool,new McpBindings.Call(epoch,Map.of("query","test"))));assertEquals(1,calls.get());
 }
 @Test void revokedGrantDuringRemoteCallWithholdsReturnedContent(){mcp.save(admin,config(true,0));onRead=()->tools.grant(admin,task,tool,false);assertEquals(403,assertThrows(ApiException.class,()->mcp.call(node,attempt,tool,new McpBindings.Call(epoch,Map.of("query","test")))).status);assertEquals(1,calls.get());assertTrue(mcp.catalog(node,attempt,epoch).isEmpty());}
 @Test void denyWritesAndSchemaDriftBeforeInvocation(){mcp.save(admin,config(true,0));contract=new ToolContract("fixture.mcp.read",ToolContract.Effect.READ_ONLY,Map.of(),"vault://fixture/mcp","fixture.read");assertEquals("MCP_SCHEMA_CHANGED",assertThrows(ApiException.class,()->mcp.call(node,attempt,tool,new McpBindings.Call(epoch,Map.of("query","test")))).code);assertEquals(0,calls.get());
  String write=(String)tools.publish(admin,new ToolRegistry.Publish(new ToolContract("fixture.mcp.write",ToolContract.Effect.IDEMPOTENT_WRITE,Map.of(),"vault://fixture/mcp","fixture.write"),"1.0.0","sandbox")).get("toolVersionId");assertEquals(400,assertThrows(ApiException.class,()->mcp.save(admin,new McpBindings.Config(write,config(true,0).endpoint(),"write","",true,0))).status);
  assertEquals(403,assertThrows(ApiException.class,()->mcp.save(owner,config(true,1))).status);
 }
}
