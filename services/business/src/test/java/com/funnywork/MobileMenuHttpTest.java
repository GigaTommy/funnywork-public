package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the mobile menu contracts through a real HTTP server and migrated database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:mobile-menu-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class MobileMenuHttpTest {
 @Autowired AuthService auth;
 @Autowired EmployeeAccounts accounts;
 @Autowired ObjectMapper mapper;
 @LocalServerPort int port;
 private final HttpClient http=HttpClient.newHttpClient();
 private JsonNode request(String token,String path,Map<String,Object> body) throws Exception {
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/account/"+path)).header("Authorization","Bearer "+token);
  if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
  var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(200,response.statusCode(),path);
  return mapper.readTree(response.body());
 }
 @Test void menuRequestsReturnUsableResultsAndPreserveTheSession() throws Exception {
  String tenant=AuthService.id(),adminToken=AuthService.id(),principal=AuthService.id(),username="menu-"+AuthService.id();
  auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),adminToken);
  auth.create(principal,tenant,"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),AuthService.id());
  accounts.password(auth.authenticate(adminToken),principal,username,"test-password-1234");
  String token=(String)accounts.login(username,"test-password-1234","iOS menus").get("token"),device=AuthService.id();
  request(token,"sync/devices",Map.of("id",device,"name","Menu desktop","platform","darwin"));
  String chat=request(token,"sync/sessions",Map.of("deviceId",device,"header",Map.of("id","menu-native","version",4,"createdAt",1,"isSeeded",false),"inheritedEventCount",0)).path("id").asText();
  assertFalse(chat.isEmpty());
  request(token,"sync/sessions/"+chat+"/events",Map.of("deviceId",device,"events",List.of(Map.of("seq",0,"time",1,"type","user/message","data",Map.of("content",List.of(Map.of("type","text","text","menu-search-fixture")))))));
  request(token,"sync/devices/"+device+"/state",Map.of("epoch","menu-epoch","sessions",Map.of("menu-native",Map.of("seq",1,"running",false)),"workspaces",List.of(Map.of("id","workspace","title","Workspace"))));
  var devices=request(token,"sync/devices",null);
  assertEquals(device,devices.get(0).path("id").asText());assertTrue(devices.get(0).path("online").asBoolean());
  assertEquals("workspace",devices.get(0).path("workspaces").get(0).path("id").asText());assertEquals(0,devices.get(0).path("accessRevision").asLong());
  var command=request(token,"sync/devices/"+device+"/commands",Map.of("id",AuthService.id(),"epoch","menu-epoch","kind","create","payload",Map.of("workspaceId","workspace")));
  assertEquals("queued",command.path("status").asText());
  assertEquals("queued",request(token,"sync/devices/"+device+"/commands/"+command.path("id").asText(),null).path("status").asText());
  assertEquals(1,request(token,"sync/search?query=menu-search-fixture",null).path("items").size());
  assertEquals("Renamed",request(token,"sync/sessions/"+chat+"/title",Map.of("title","Renamed","revision",0)).path("title").asText());
  request(token,"sync/sessions/"+chat+"/archive",Map.of("archived",true,"revision",0));
  assertTrue(request(token,"sync/sessions",null).get(0).path("archived").asBoolean());
  assertEquals(1,request(token,"sync/search?query=menu-search-fixture&archived=true",null).path("items").size());
  request(token,"sync/sessions/"+chat+"/archive",Map.of("archived",false,"revision",1));
  var code=request(token,"sync/devices/"+device+"/link",Map.of());
  var secret=Map.<String,Object>of("secret",code.path("secret").asText());
  request(token,"sync/device-links/"+code.path("id").asText()+"/inspect",secret);
  request(token,"sync/device-links/"+code.path("id").asText()+"/confirm",secret);
  assertTrue(request(token,"push",null).path("available").isBoolean());
  var choices=Map.of("models",List.of(Map.of("provider","enterprise","model","qwen","name","企业千问")),"presets",List.of(Map.of("id","enterprise-helper-v1","name","企业助手")));
  var presets=new ArrayList<Map<String,String>>();presets.add(Map.of("id","standard","name","Standard"));
  for(int i=0;i<512;i++)presets.add(Map.of("id",i==0?"enterprise-helper-v1":"enterprise-"+i+"-v1","name","助".repeat(100)));
  choices=Map.of("models",List.of(Map.of("provider","enterprise","model","qwen","name","企业千问")),"presets",presets);
  var workspaces=new ArrayList<Map<String,String>>();workspaces.add(Map.of("id","workspace","title","Workspace"));
  for(int i=1;i<1000;i++)workspaces.add(Map.of("id","workspace-"+i,"title","Work"));
  request(token,"sync/devices/"+device+"/state",Map.of("epoch","menu-epoch","sessions",Map.of("menu-native",Map.of("seq",1,"running",true,"admissionWaiting",1)),"workspaces",workspaces,"choices",choices));
  assertEquals("qwen",request(token,"sync/devices",null).get(0).path("choices").path("models").get(0).path("model").asText());
  var directory=request(token,"sync/devices",null).get(0);
  assertEquals(513,directory.path("choices").path("presets").size());assertEquals(1000,directory.path("workspaces").size());
  assertFalse(directory.path("workspaces").get(0).has("choices"));
  assertEquals("waiting",request(token,"sync/sessions",null).get(0).path("task_state").asText());
  var payload=Map.of("workspaceId","workspace","model",Map.of("provider","enterprise","model","qwen"),"agentPreset","enterprise-helper-v1");
  var chosen=Map.<String,Object>of("id",AuthService.id(),"epoch","menu-epoch","kind","create","payload",payload);
  assertEquals("queued",request(token,"sync/devices/"+device+"/commands",chosen).path("status").asText());
  assertEquals("queued",request(token,"sync/devices/"+device+"/commands",chosen).path("status").asText());
  request(token,"sync/devices/"+device+"/state",Map.of("epoch","menu-epoch","sessions",Map.of("menu-native",Map.of("seq",1,"running",false)),"workspaces",List.of(Map.of("id","workspace","title","Workspace"))));
  var rejected=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/account/sync/devices/"+device+"/commands")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("id",AuthService.id(),"epoch","menu-epoch","kind","create","payload",payload)))).build();
  assertEquals(409,http.send(rejected,HttpResponse.BodyHandlers.ofString()).statusCode());
  request(token,"sync/devices/"+device+"/revoke",Map.of("revision",0));
  assertTrue(request(token,"sync/devices",null).get(0).path("revoked").asBoolean());
  request(token,"sync/devices/"+device+"/restore",Map.of("revision",1));
  assertFalse(request(token,"sync/devices",null).get(0).path("revoked").asBoolean());
  assertEquals(principal,request(token,"session",null).path("principalId").asText());
 }
 @Test void combinedStateOverOneMiBPreservesCatalogAndHeartbeatWithScopedLimit()throws Exception{
  String tenant=AuthService.id(),token=AuthService.id(),device=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Large directory",Set.of("VIEWER"),Set.of("sandbox"),token);
  request(token,"sync/devices",Map.of("id",device,"name","Large desktop","platform","win32"));
  var models=new ArrayList<Map<String,String>>();var presets=new ArrayList<Map<String,String>>();
  for(int i=0;i<512;i++){models.add(Map.of("provider","enterprise","model","model-"+i,"name","模".repeat(200)));presets.add(Map.of("id","preset-"+i,"name","设".repeat(200)));}
  presets.add(Map.of("id","standard","name","Standard"));
  var state=Map.<String,Object>of("epoch","large-epoch","sessions",Map.of("native",Map.of("seq",0,"running",false,"padding","x".repeat(550000))),"workspaces",List.of(Map.of("id","workspace","title","Workspace")),"choices",Map.of("models",models,"presets",presets));
  assertTrue(mapper.writeValueAsBytes(state).length>1048576);assertTrue(mapper.writeValueAsBytes(state).length<2200000);
  request(token,"sync/devices/"+device+"/state",state);var directory=request(token,"sync/devices",null).get(0);assertTrue(directory.path("online").asBoolean());assertEquals(512,directory.path("choices").path("models").size());assertEquals(513,directory.path("choices").path("presets").size());
  request(token,"sync/devices/"+device+"/commands",Map.of("id",AuthService.id(),"epoch","large-epoch","kind","create","payload",Map.of("workspaceId","workspace","model",Map.of("provider","enterprise","model","model-511"),"agentPreset","preset-511")));
  for(String path:List.of("sync/devices/"+device+"/state","chats/chat/turns")){
   String text="x".repeat(path.endsWith("/state")?2200001:1048577);var oversized=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/account/"+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(text)).build();assertEquals(413,http.send(oversized,HttpResponse.BodyHandlers.ofString()).statusCode());
  }
 }

}
