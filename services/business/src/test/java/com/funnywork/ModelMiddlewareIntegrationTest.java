package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:model-middleware;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.model-admission-calls-per-minute=10000","funnywork.scheduling-enabled=false","funnywork.model-inference-enabled=true","FUNNYWORK_MODEL_PROXY_TEST_KEY=server-only-secret","funnywork.model-inference-origins=http://127.0.0.1:19099,https://models.internal"})
class ModelMiddlewareIntegrationTest {
    @Autowired RecoveryGate recovery;@Autowired Json json;@Autowired java.time.Clock clock;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired org.springframework.core.env.Environment environment;
    @Autowired ModelMiddleware service;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;
    @Autowired ModelOperations operations;@Autowired ModelBudgets budgets;
    @org.springframework.boot.test.web.server.LocalServerPort int serverPort;
    static final com.sun.net.httpserver.HttpServer upstream;
    static final int refusedPort;
    static final java.util.concurrent.atomic.AtomicReference<String> reply=new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicReference<byte[]> received=new java.util.concurrent.atomic.AtomicReference<>();
    static {
        try{upstream=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/v1/chat/completions",exchange->{received.set(exchange.getRequestBody().readAllBytes());assertNull(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().add("Content-Type","text/event-stream");byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});upstream.createContext("/anthropic/messages",exchange->{received.set(exchange.getRequestBody().readAllBytes());assertEquals("server-only-secret",exchange.getRequestHeaders().getFirst("x-api-key"));assertEquals("custom-tools-beta",exchange.getRequestHeaders().getFirst("anthropic-beta"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().add("Content-Type","text/event-stream");byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});upstream.start();try(var unused=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){refusedPort=unused.getLocalPort();}
        }catch(java.io.IOException e){throw new ExceptionInInitializerError(e);}
    }
    @org.springframework.test.context.DynamicPropertySource static void origins(org.springframework.test.context.DynamicPropertyRegistry registry){registry.add("funnywork.model-inference-origins",()->"https://models.internal,http://127.0.0.1:"+upstream.getAddress().getPort()+",http://127.0.0.1:"+refusedPort);String url=System.getenv("FUNNYWORK_MODEL_TEST_DB_URL");if(url!=null){registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getProperty("user.name"));registry.add("spring.datasource.password",()->"");}}
    @AfterAll static void stopUpstream(){upstream.stop(0);}
    Identity operator,admin,user,other;String pool,model,second;
    @BeforeEach void setup(){
        if(db.queryForObject("select count(*) from principals where id='bootstrap-admin'",Integer.class)==0)auth.create("bootstrap-admin","budget-operator","USER","Budget operator",Set.of("ADMIN"),Set.of("sandbox"),"model-fixture-budget-operator");
        operator=auth.authenticate("model-fixture-budget-operator");
        String tenant=AuthService.id();admin=create(tenant,"ADMIN");user=create(tenant,"OPERATOR");other=create(AuthService.id(),"ADMIN");
        db.update("update model_budget_guard set total_limit=1000000000000,daily_limit=1000000000000,paused=false where id=1");
        for(Identity member:List.of(admin,user))budgets.saveUser(admin,member.id(),new ModelBudgets.UserPolicy(1000000000000L,1000000000000L,32,600,8192,System.currentTimeMillis()+86400000,true,0));
        pool=(String)service.savePool(admin,null,new ModelMiddleware.Pool("Ascend","sandbox",1,10,1,5,60000,false,0)).get("id");
        model=deployment("deepseek");second=deployment("qwen");
    }
    Identity create(String tenant,String role){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),token);return auth.authenticate(token);}
    String deployment(String name){return (String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,name,name,"CHAT_COMPLETIONS","https://models.internal/v1","",32768,1024,Map.of("tools",true),true))).get("id");}
    Map<String,Object> priced(Map<String,Object> created){budgets.savePrice(operator,(String)created.get("id"),new ModelBudgets.Price(1000000,2000000,2000000,0));return created;}
    byte[] body(String name){return ("{\"model\":\""+name+"\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":128,\"stream\":true}").getBytes(StandardCharsets.UTF_8);}
    ModelMiddleware.Ticket request(String deployment,String call,byte[] body){return new ModelMiddleware.Ticket(deployment,call,call,"device","session","task","","agent",AuthService.hash(new String(body,StandardCharsets.UTF_8)),128);}
    Map<String,Object> admit(Identity actor,String deployment,String name){return service.admit(actor,request(deployment,AuthService.id(),body(name)));}
    String id(Map<String,Object> t){return (String)t.get("id");}
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=";
    private String imageDeployment(String protocol,boolean images){return (String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Vision","vision",protocol,"https://models.internal/v1","",32768,1024,Map.of("tools",true,"images",images),true))).get("id");}
    private byte[] imageBody(String protocol,String source){return ("{\"model\":\"vision\",\"messages\":[{\"role\":\"user\",\"content\":["+source+"]}],\"max_tokens\":128,\"stream\":true}").getBytes(StandardCharsets.UTF_8);}
    @Test void enabledChatImagePassesTheSamePinnedAdmissionContract(){
        String deployment=imageDeployment("CHAT_COMPLETIONS",true);
        byte[] bytes=imageBody("CHAT_COMPLETIONS","{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"+PNG+"\",\"detail\":\"auto\"}}");
        var ticket=service.admit(user,request(deployment,AuthService.id(),bytes));
        assertDoesNotThrow(()->service.begin(user,id(ticket),bytes));
        assertEquals(409,assertThrows(ApiException.class,()->service.begin(user,id(ticket),bytes)).status);
        service.finish(id(ticket),"FAILED","FIXTURE_STOPPED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
    }
    @Test void messagesSupportsInlineImagesInsideToolResults(){
        String deployment=imageDeployment("MESSAGES",true);
        byte[] bytes=imageBody("MESSAGES","{\"type\":\"tool_result\",\"tool_use_id\":\"chart\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""+PNG+"\"}}]}");
        var ticket=service.admit(user,request(deployment,AuthService.id(),bytes));
        assertDoesNotThrow(()->service.begin(user,id(ticket),bytes));
        service.finish(id(ticket),"FAILED","FIXTURE_STOPPED",new ModelUsage(mapper,"MESSAGES"),null);
    }
    @Test void imageCapabilityAppliesToNestedContentAndRejectsExternalOrMalformedImages(){
        for(String protocol:List.of("CHAT_COMPLETIONS","MESSAGES")){
            String deployment=imageDeployment(protocol,true);
            var blocks=protocol.equals("CHAT_COMPLETIONS")?List.of(
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://private.internal/chart.png\"}}",
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,%%%\"}}",
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/svg+xml;base64,"+PNG+"\"}}"):
                List.of("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://private.internal/chart.png\"}}",
                        "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"%%%\"}}");
            for(String block:blocks){
                byte[] bytes=imageBody(protocol,block);var ticket=service.admit(user,request(deployment,AuthService.id(),bytes));
                assertEquals(400,assertThrows(ApiException.class,()->service.begin(user,id(ticket),bytes)).status);
                assertNull(service.readTicket(user,id(ticket)).get("started_at"));service.cancel(user,id(ticket),false);
            }
        }
        String deployment=imageDeployment("MESSAGES",false);
        byte[] bytes=imageBody("MESSAGES","{\"type\":\"tool_result\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""+PNG+"\"}}]}");
        var ticket=service.admit(user,request(deployment,AuthService.id(),bytes));
        assertEquals(400,assertThrows(ApiException.class,()->service.begin(user,id(ticket),bytes)).status);
        service.cancel(user,id(ticket),false);
    }
    @Autowired org.springframework.test.web.servlet.MockMvc startupHttp;
    @Test void combinedStartupRequiresAccountIdentityAndPreservesScopeAndBudgets()throws Exception{
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),user.id(),System.currentTimeMillis()+60000);
        var request=Map.of("modelDevice","startup-device-"+AuthService.id(),"policyDevice",AuthService.id().replace("-",""));
        long total=db.queryForObject("select total_limit from model_user_budgets where owner_id=?",Long.class,user.id());
        var response=startupHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(request))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());var result=mapper.readTree(response.getContentAsString());
        assertEquals(1,result.path("schema").asInt());assertTrue(result.path("inference").path("bootstrap").path("enabled").asBoolean());
        assertEquals(service.catalog(user).size(),result.path("inference").path("catalog").path("items").size());
        assertFalse(response.getContentAsString().contains("base_url"));assertFalse(response.getContentAsString().contains("credential_ref"));
        assertEquals(total,db.queryForObject("select total_limit from model_user_budgets where owner_id=?",Long.class,user.id()));
        String entry=result.path("inference").path("credential").path("token").asText();assertTrue(entry.startsWith("mwi."));
        for(String bearer:List.of("",entry)){
            var denied=startupHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer "+bearer).contentType("application/json").content(mapper.writeValueAsString(request))).andReturn().getResponse();
            assertTrue(denied.getStatus()==401||denied.getStatus()==403);
        }
        assertEquals(400,startupHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer "+token).contentType("application/json").content("{\"modelDevice\":\"../invalid\",\"policyDevice\":\"invalid\"}")).andReturn().getResponse().getStatus());
        db.update("update model_user_budgets set total_limit=0,daily_limit=0 where owner_id=?",user.id());
        assertEquals(200,startupHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(request))).andReturn().getResponse().getStatus());
        assertEquals(0L,db.queryForObject("select total_limit from model_user_budgets where owner_id=?",Long.class,user.id()));
        assertEquals(429,assertThrows(ApiException.class,()->admit(user,model,"deepseek")).status);
        service.revokeEntry(user,(String)request.get("modelDevice"));
    }
    @Test void catalogGroupsModelFamiliesWithoutExposingUpstreamConfiguration(){
        for(String name:List.of("codex/gpt-6.1-sol","codex/gpt-6-astra","anthropic/claude-sonnet","gemini-pro","custom-private"))deployment(name);
        var rows=service.catalog(user);
        for(var row:rows){
            String name=(String)row.get("model");
            String expected=name.contains("gpt-")?"openai":name.startsWith("deepseek")?"deepseek":name.startsWith("qwen")?"alibaba":name.contains("claude")?"anthropic":name.startsWith("gemini")?"google":"custom";
            assertEquals("enterprise-"+expected,row.get("provider_id"));
            assertFalse(row.containsKey("base_url"));assertFalse(row.containsKey("credential_ref"));
        }
        assertEquals(2,rows.stream().filter(row->"OpenAI".equals(row.get("provider_name"))).count());
    }
    @Test void largeContextPayloadPassesGatewayAndStillSettlesBudget()throws Exception{
        model=(String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Large context","deepseek","CHAT_COMPLETIONS","http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","",1000000,8192,Map.of("streamUsage",true),true))).get("id");
        byte[] payload=mapper.writeValueAsBytes(Map.of("model","deepseek","messages",List.of(Map.of("role","user","content","a".repeat(1100000))),"max_tokens",128,"stream",true));
        var ticket=service.admit(user,request(model,AuthService.id(),payload));
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),user.id(),System.currentTimeMillis()+60000);
        reply.set("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: {\"usage\":{\"prompt_tokens\":300000,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n");
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/tickets/"+id(ticket)+"/execute")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        assertArrayEquals(payload,received.get());
        assertEquals("SETTLED",db.queryForObject("select state from model_budget_charges where ticket_id=?",String.class,id(ticket)));
    }
    @Test void reportHttpFiltersBindAndRequireAdministrativeIdentity()throws Exception{
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+60000);
        String query="?from="+(System.currentTimeMillis()-60000)+"&until="+(System.currentTimeMillis()+1000)+"&owner="+user.id()+"&limit=1";
        var client=java.net.http.HttpClient.newHttpClient();
        for(String view:List.of("overview","members","calls","tasks")){
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/v1/model-middleware/usage/"+view+query)).header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());assertEquals("ENTERPRISE_ENTRY_ONLY",mapper.readTree(response.body()).path("coverage").asText());
        }
    }
    @Test void operationalReportsFilterPageAttributeAndKeepUnknownUsageUnknown(){
        var first=admit(user,model,"deepseek");service.begin(user,id(first),body("deepseek"));
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}");usage.event("data: [DONE]");service.finish(id(first),"SUCCEEDED","COMPLETED",usage,System.currentTimeMillis());
        var secondCall=admit(admin,second,"qwen");service.begin(admin,id(secondCall),body("qwen"));service.finish(id(secondCall),"FAILED","UPSTREAM_ERROR",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        var third=service.admit(user,new ModelMiddleware.Ticket(model,AuthService.id(),(String)first.get("logical_call_id"),"device","session","task","","agent",AuthService.hash(new String(body("deepseek"),StandardCharsets.UTF_8)),128));service.begin(user,id(third),body("deepseek"));service.finish(id(third),"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        var filter=new ModelOperations.Filter(System.currentTimeMillis()-60000,System.currentTimeMillis()+1000,"","","","","");
        var overview=operations.overview(admin,filter);var summary=(Map<String,Object>)overview.get("summary");assertEquals(3L,((Number)summary.get("calls")).longValue());assertEquals(20L,((Number)summary.get("input_tokens")).longValue());assertEquals(2L,((Number)summary.get("incomplete_usage_calls")).longValue());assertFalse(((List<?>)overview.get("hourly")).isEmpty());
        var cost=(Map<?,?>)overview.get("cost");
        assertEquals("CNY",cost.get("currency"));assertEquals("28",cost.get("provider_micros"));assertEquals("28",cost.get("settled_micros"));assertEquals("0",cost.get("estimated_micros"));
        long secondHold=db.queryForObject("select reserved from model_budget_charges where ticket_id=?",Long.class,id(secondCall)),thirdHold=db.queryForObject("select reserved from model_budget_charges where ticket_id=?",Long.class,id(third));
        assertEquals(String.valueOf(secondHold+thirdHold),cost.get("held_micros"));assertEquals(String.valueOf(thirdHold),cost.get("unknown_held_micros"));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->budgets.reconcile(id(secondCall)));
        var reconciled=(Map<?,?>)operations.overview(admin,filter).get("cost");assertEquals(String.valueOf(secondHold),reconciled.get("estimated_micros"));assertEquals(String.valueOf(28+secondHold),reconciled.get("settled_micros"));assertEquals(String.valueOf(thirdHold),reconciled.get("held_micros"));
        var excluded=(Map<?,?>)operations.overview(other,filter).get("cost");assertEquals("0",excluded.get("settled_micros"));assertEquals("0",excluded.get("held_micros"));
        var page=operations.members(admin,filter,"",1);assertEquals(1,((List<?>)page.get("items")).size());assertNotNull(page.get("nextOwner"));assertEquals(1,((List<?>)operations.members(admin,filter,(String)page.get("nextOwner"),1).get("items")).size());
        var calls=operations.calls(admin,filter,0,"",1);var following=operations.calls(admin,filter,((Number)calls.get("nextCreatedAt")).longValue(),(String)calls.get("nextId"),100);assertEquals(2,((List<?>)following.get("items")).size());assertFalse(mapper.valueToTree(calls).toString().contains("request_digest"));
        var own=new ModelOperations.Filter(filter.from(),filter.until(),"",user.id(),model,"","");var tasks=(List<Map<String,Object>>)operations.tasks(admin,own,"","","",100).get("items");assertEquals(1,tasks.size());assertEquals(2L,((Number)tasks.get(0).get("calls")).longValue());
        assertEquals(1L,((Number)tasks.get(0).get("retry_attempts")).longValue());
        var failed=new ModelOperations.Filter(filter.from(),filter.until(),"",admin.id(),second,"FAILED","");assertNull(((Map<?,?>)operations.overview(admin,failed).get("summary")).get("input_tokens"));
        assertEquals(403,assertThrows(ApiException.class,()->operations.overview(user,filter)).status);
        var forbidden=new ModelOperations.Filter(filter.from(),filter.until(),"private","","","","");
        assertEquals(403,assertThrows(ApiException.class,()->operations.overview(admin,forbidden)).status);
        assertEquals(0L,((Number)((Map<?,?>)operations.overview(other,filter).get("summary")).get("calls")).longValue());
    }
    @Test void queuePagesHavePoolWideCountsAndFilteredStableCursors(){
        var a=admit(user,model,"deepseek");var b=admit(admin,second,"qwen");var c=admit(admin,second,"qwen");
        var page=service.queuePage(admin,pool,"","","",0,"",1);assertEquals(3L,((Number)page.get("total")).longValue());assertEquals(1L,((Number)page.get("occupied")).longValue());assertEquals(0L,((Number)page.get("available")).longValue());
        var next=service.queuePage(admin,pool,"","","",((Number)page.get("nextCreatedAt")).longValue(),(String)page.get("nextId"),100);assertEquals(2,((List<?>)next.get("items")).size());
        var filtered=service.queuePage(admin,pool,"WAITING",admin.id(),second,0,"",100);assertEquals(2L,((Number)filtered.get("total")).longValue());assertEquals(1L,((Number)filtered.get("occupied")).longValue());
        service.cancel(admin,id(b),true);assertEquals(1L,((Number)service.queuePage(admin,pool,"WAITING","","",0,"",100).get("total")).longValue());
        assertEquals(403,assertThrows(ApiException.class,()->service.queuePage(user,pool,"","","",0,"",100)).status);assertEquals(404,assertThrows(ApiException.class,()->service.queuePage(other,pool,"","","",0,"",100)).status);
    }
    @Test void scopedCredentialsExpireRevokeAndCannotMintOrReachManagement()throws Exception{
        var issued=service.credential(user);String token=(String)issued.get("token");assertTrue(token.startsWith("mwi."));
        assertEquals(user.id(),service.authenticateEntry(token,"/api/account/inference/catalog").id());
        assertEquals(403,assertThrows(ApiException.class,()->service.authenticateEntry(token,"/api/v1/model-middleware/pools")).status);
        assertEquals(403,assertThrows(ApiException.class,()->service.authenticateEntry(token,"/api/account/inference/credential")).status);
        var client=java.net.http.HttpClient.newHttpClient();
        var catalog=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/catalog")).header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,catalog.statusCode());assertFalse(catalog.body().contains(token));
        var management=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/v1/model-middleware/pools")).header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(403,management.statusCode());
        db.update("update model_entry_credentials set expires_at=0 where token_hash=?",AuthService.hash(token));
        assertEquals(401,assertThrows(ApiException.class,()->service.authenticateEntry(token,"/api/account/inference/catalog")).status);
        String replacement=(String)service.credential(user).get("token");auth.disable(admin,user.id());
        assertEquals(403,assertThrows(ApiException.class,()->service.authenticateEntry(replacement,"/api/account/inference/catalog")).status);
    }
    @Test void explicitDisableStopsScopedCredentialIssuance(){
        var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment().withProperty("funnywork.model-inference-enabled","false"),recovery,"https://models.internal",budgets);
        assertEquals(false,replica.bootstrap(user).get("enabled"));
        assertEquals(409,assertThrows(ApiException.class,()->replica.credential(user)).status);
        String token=(String)service.credential(user).get("token");
        assertEquals(409,assertThrows(ApiException.class,()->replica.authenticateEntry(token,"/api/account/inference/catalog")).status);
    }
    @Test void entryRevocationIsPerOwnerAndDeviceAndCannotUseTheScopedKey(){
        String first=(String)service.credential(user,"desktop-a").get("token"),rotated=(String)service.credential(user,"desktop-a").get("token"),otherDevice=(String)service.credential(user,"desktop-b").get("token"),otherOwner=(String)service.credential(admin,"desktop-a").get("token");
        assertEquals(403,assertThrows(ApiException.class,()->service.authenticateEntry(first,"/api/account/inference/credentials/revoke")).status);
        service.revokeEntry(user,"desktop-a");
        for(String token:List.of(first,rotated))assertEquals(401,assertThrows(ApiException.class,()->service.authenticateEntry(token,"/api/account/inference/catalog")).status);
        assertEquals(user.id(),service.authenticateEntry(otherDevice,"/api/account/inference/catalog").id());
        assertEquals(admin.id(),service.authenticateEntry(otherOwner,"/api/account/inference/catalog").id());
    }
    @Test void sharedPoolCapsDifferentModelsAndNeverReplaysConsumedPermit(){
        var a=admit(user,model,"deepseek");var b=admit(admin,second,"qwen");assertEquals("GRANTED",a.get("state"));assertEquals("WAITING",b.get("state"));
        var dispatch=service.begin(user,id(a),body("deepseek"));assertEquals("deepseek",dispatch.deployment().get("model"));
        assertEquals(409,assertThrows(ApiException.class,()->service.begin(user,id(a),body("deepseek"))).status);
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");
        service.finish(id(a),"SUCCEEDED","COMPLETED",usage,123L);
        assertEquals("GRANTED",service.readTicket(admin,id(b)).get("state"));assertEquals(10L,((Number)((java.util.List<java.util.Map<String,Object>>)service.usage(admin,System.currentTimeMillis()-60000,System.currentTimeMillis()+1,"","",50).get("items")).stream().filter(row->row.get("input_tokens")!=null).findFirst().orElseThrow().get("input_tokens")).longValue());
    }
    @Test void ticketIdempotencyBindsBodyAndAttribution(){
        var request=request(model,"same-call",body("deepseek"));var a=service.admit(user,request);assertEquals(id(a),id(service.admit(user,request)));
        assertEquals(409,assertThrows(ApiException.class,()->service.admit(user,request(model,"same-call",body("qwen")))).status);
        byte[] modified=body("deepseek");modified[modified.length-2]=' ';
        assertEquals(400,assertThrows(ApiException.class,()->service.begin(user,id(a),modified)).status);
        assertEquals("GRANTED",service.readTicket(user,id(a)).get("state"));
    }
    @Test void unknownOccupiesCapacityUntilAuditedConfirmation(){
        var a=admit(user,model,"deepseek");service.begin(user,id(a),body("deepseek"));
        service.finish(id(a),"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        var b=admit(admin,second,"qwen");assertEquals("WAITING",b.get("state"));
        service.cancel(user,id(a),false);assertEquals("UNKNOWN",service.readTicket(user,id(a)).get("state"));
        assertEquals(403,assertThrows(ApiException.class,()->service.reconcile(user,id(a),"stopped")).status);
        service.reconcile(admin,id(a),"Operator checked the serving instance has no active request");
        assertEquals("GRANTED",service.readTicket(admin,id(b)).get("state"));
    }
    @Test void fairRotationAndCancelledGrantsReleaseOnlyUnusedCapacity(){
        var first=admit(user,model,"deepseek");var own=admit(user,model,"deepseek");var fresh=admit(admin,model,"deepseek");
        service.cancel(user,id(first),false);assertEquals("WAITING",service.readTicket(user,id(own)).get("state"));assertEquals("GRANTED",service.readTicket(admin,id(fresh)).get("state"));
    }
    @Test void scopesRevocationPauseDisableAndExpiryAreEnforced(){
        var a=admit(user,model,"deepseek");assertEquals(404,assertThrows(ApiException.class,()->service.readTicket(other,id(a))).status);
        assertEquals(404,assertThrows(ApiException.class,()->service.readTicket(admin,id(a))).status);
        service.savePool(admin,pool,new ModelMiddleware.Pool("Ascend","sandbox",1,10,1,5,60000,true,1));
        assertEquals(503,assertThrows(ApiException.class,()->service.begin(user,id(a),body("deepseek"))).status);
        service.savePool(admin,pool,new ModelMiddleware.Pool("Ascend","sandbox",1,10,1,5,60000,false,2));
        db.update("update model_call_tickets set grant_expires_at=0 where id=?",id(a));assertEquals("EXPIRED",service.readTicket(user,id(a)).get("state"));
        var b=admit(user,model,"deepseek");auth.disable(admin,user.id());
        assertEquals(403,assertThrows(ApiException.class,()->service.begin(user,id(b),body("deepseek"))).status);
        service.enableDeployment(admin,second,false);assertEquals(409,assertThrows(ApiException.class,()->admit(admin,second,"qwen")).status);
    }
    @Test void concurrentPoolAdmissions()throws Exception{
        ExecutorService workers=Executors.newFixedThreadPool(8);
        try{
            var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,environment,recovery,"https://models.internal",budgets);
            var futures=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<8;i++){final ModelMiddleware current=i%2==0?service:replica;futures.add(workers.submit(()->current.admit(admin,request(model,AuthService.id(),body("deepseek")))));}
            int granted=0,accepted=0;for(var f:futures){try{if("GRANTED".equals(f.get().get("state")))granted++;accepted++;}catch(ExecutionException e){assertInstanceOf(ApiException.class,e.getCause());assertEquals(429,((ApiException)e.getCause()).status);}}
            assertEquals(1,granted);assertEquals(6,accepted);
        }finally{workers.shutdownNow();}
    }
    @Test void credentialsAndHostConfigurationAreNotExposedToEmployees(){
        assertFalse(mapper.valueToTree(service.catalog(user)).toString().contains("base_url"));
        assertEquals(400,assertThrows(ApiException.class,()->service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"bad","bad","MESSAGES","https://untrusted.internal/v1","",1024,128,Map.of(),true))).status);
        assertEquals("desktop",ManagementIdentity.module("/api/account/inference/tickets"));assertEquals("models",ManagementIdentity.module("/api/v1/model-middleware/pools"));assertEquals("usage",ManagementIdentity.module("/api/v1/model-middleware/usage"));
    }
    @Test void realHttpStreamIsUnchangedAndLedgerUsesObservedUsage()throws Exception{
        String deployed=(String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Mock","deepseek","CHAT_COMPLETIONS","http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","",32768,1024,Map.of("tools",true),true))).get("id");
        reply.set("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}}]}\n\ndata: {\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":7}}\n\ndata: [DONE]\n\n");
        var t=admit(admin,deployed,"deepseek");String token=AuthService.id();
        // Authenticate the same principal without persisting a second principal.
        db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+60000);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/tickets/"+id(t)+"/execute")).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body("deepseek"))).build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals(reply.get(),response.body());
        assertArrayEquals(body("deepseek"),received.get());var finalTicket=service.readTicket(admin,id(t));assertEquals("SUCCEEDED",finalTicket.get("state"));assertEquals(30L,finalTicket.get("input_tokens"));assertEquals(7L,finalTicket.get("output_tokens"));
    }

    @Test void refusedConnectionReleasesSingleSlot()throws Exception{
        String failed=(String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Unavailable","deepseek","CHAT_COMPLETIONS","http://127.0.0.1:"+refusedPort+"/v1","",8192,2048,Map.of(),true))).get("id");
        var a=admit(admin,failed,"deepseek");var waiting=admit(user,model,"deepseek");assertEquals("WAITING",waiting.get("state"));
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+60000);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/tickets/"+id(a)+"/execute")).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body("deepseek"))).build();
        assertEquals(502,java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var done=service.readTicket(admin,id(a));
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        while("RUNNING".equals(done.get("state"))&&System.nanoTime()<deadline){Thread.sleep(10);done=service.readTicket(admin,id(a));}
        assertEquals("FAILED",done.get("state"));assertEquals("UPSTREAM_CONNECT_FAILED",done.get("outcome"));assertEquals("GRANTED",service.readTicket(user,id(waiting)).get("state"));
    }
    @Test void messagesExtensionsAndServerCredentialsArePreservedWithoutClientSecret()throws Exception{
        String deployed=(String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Messages","deepseek","MESSAGES","http://127.0.0.1:"+upstream.getAddress().getPort()+"/anthropic","FUNNYWORK_MODEL_PROXY_TEST_KEY",32768,1024,Map.of("tools",true,"reasoning",true),true))).get("id");
        reply.set("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11,\"cache_read_input_tokens\":2}}}\n\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reason\",\"signature\":\"opaque\"}}\n\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\ndata: {\"type\":\"message_stop\"}\n\n");
        var t=admit(admin,deployed,"deepseek");String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+60000);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/tickets/"+id(t)+"/execute")).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("anthropic-beta","custom-tools-beta").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body("deepseek"))).build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals(reply.get(),response.body());var finalTicket=service.readTicket(admin,id(t));assertEquals("SUCCEEDED",finalTicket.get("state"));assertEquals(13L,finalTicket.get("input_tokens"));assertEquals(5L,finalTicket.get("output_tokens"));assertFalse(mapper.writeValueAsString(service.catalog(user)).contains("server-only-secret"));
    }
    @Test void interruptedMessagesPreserveUnknownOutputAndOccupiedCapacity()throws Exception{
        String deployed=(String)priced(service.createDeployment(admin,new ModelMiddleware.Deployment(pool,"Interrupted","deepseek","MESSAGES","http://127.0.0.1:"+upstream.getAddress().getPort()+"/anthropic","FUNNYWORK_MODEL_PROXY_TEST_KEY",8192,2048,Map.of(),true))).get("id");
        reply.set("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}}\n\n");
        var t=admit(admin,deployed,"deepseek");String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+60000);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+serverPort+"/api/account/inference/tickets/"+id(t)+"/execute")).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("anthropic-beta","custom-tools-beta").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body("deepseek"))).build();
        assertEquals(200,java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        Map<String,Object> done=service.readTicket(admin,id(t));for(int retry=0;"RUNNING".equals(done.get("state"))&&retry<100;retry++){Thread.sleep(10);done=service.readTicket(admin,id(t));}
        assertEquals("UNKNOWN",done.get("state"));assertEquals(11L,done.get("input_tokens"));assertNull(done.get("output_tokens"));assertEquals("PARTIAL",done.get("usage_quality"));assertEquals("WAITING",admit(user,model,"deepseek").get("state"));
    }
    @Test void requestsWithoutTerminalEvidenceStayUnknownAndBlockNextRequest()throws Exception{
        var a=admit(user,model,"deepseek");service.begin(user,id(a),body("deepseek"));db.update("update model_call_tickets set execution_deadline=0 where id=?",id(a));assertEquals("UNKNOWN",service.readTicket(user,id(a)).get("state"));assertEquals("WAITING",admit(admin,second,"qwen").get("state"));
    }

    @Test void thousandActiveCallsRemainBoundedAndDurable(){
        service.savePool(admin,pool,new ModelMiddleware.Pool("Ascend","sandbox",1,999,1,999,60000,false,1));
        String first=null;
        for(int i=0;i<1000;i++){var t=admit(user,model,"deepseek");if(i==0)first=id(t);assertEquals(i==0?"GRANTED":"WAITING",t.get("state"));}
        var seen=new HashSet<String>();long afterCreated=0;String afterId="";
        do{var page=service.queuePage(admin,pool,"","","",afterCreated,afterId,100);
            for(var row:(List<Map<String,Object>>)page.get("items"))assertTrue(seen.add((String)row.get("id")));
            afterId=(String)page.getOrDefault("nextId","");afterCreated=((Number)page.getOrDefault("nextCreatedAt",0L)).longValue();
        }while(!afterId.isEmpty());assertEquals(1000,seen.size());
        assertEquals(429,assertThrows(ApiException.class,()->admit(user,model,"deepseek")).status);
        assertEquals(1,db.queryForObject("select count(*) from model_call_tickets where pool_id=? and state='GRANTED'",Integer.class,pool));
        assertEquals(999,db.queryForObject("select count(*) from model_call_tickets where pool_id=? and state='WAITING'",Integer.class,pool));
        var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,environment,recovery,"https://models.internal",budgets);assertEquals("GRANTED",replica.readTicket(user,first).get("state"));
    }

    @Test void recoveryGenerationMismatchCannotIssuePermits(){
        var stale=new ModelMiddleware(db,auth,json,mapper,clock,transactions,environment,new RecoveryGate(db,false,"stale-generation"),"https://models.internal",budgets);
        assertEquals(503,assertThrows(ApiException.class,()->stale.admit(user,request(model,AuthService.id(),body("deepseek")))).status);
        assertEquals(0,db.queryForObject("select count(*) from model_call_tickets where pool_id=?",Integer.class,pool));
    }

}
