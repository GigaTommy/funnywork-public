package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:session-fork;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class DesktopSessionForkTest {
 @Autowired DesktopHistorySync sync;
 @Autowired DesktopInteractions interactions;
 @Autowired DesktopAttachments attachments;
 @Autowired AuthService auth;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 @Autowired MockMvc http;
 Identity owner,other;String token,device,chat;
 JsonNode json(String value){try{return mapper.readTree(value);}catch(Exception error){throw new RuntimeException(error);}}
 Identity user(String tenant,String credential){auth.create(AuthService.id(),tenant,"USER","User",Set.of("VIEWER"),Set.of("sandbox"),credential);return auth.authenticate(credential);}
 @BeforeEach void setup(){
  String tenant=AuthService.id();token=AuthService.id();owner=user(tenant,token);other=user(tenant,AuthService.id());device=AuthService.id();
  sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));
  chat=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json("{\"id\":\"source\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0)).get("id");
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(
   json("{\"seq\":0,\"time\":1,\"type\":\"turn/start\",\"data\":{\"turn\":1}}"),
   json("{\"seq\":1,\"time\":1,\"type\":\"assistant/message\",\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"Reply\"}]}}"))));
  publish(true);
 }
 void publish(boolean supported){interactions.publish(owner,device,new DesktopInteractions.State("epoch",json("{\"source\":{\"seq\":2,\"running\":false,\"controllable\":true}}"),json("[]"),false,supported));}
 DesktopInteractions.Command command(String id,long seq){return new DesktopInteractions.Command(id,"epoch","fork",mapper.valueToTree(Map.of("atSeq",seq)));}
 String child(String command,String parent,long inherited){
  String created=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,mapper.valueToTree(Map.of("id","mobile-"+command,"version",4,"createdAt",1,"isSeeded",true,"parentSession",parent)),inherited)).get("id");
  var events=db.queryForList("select event_json from sync_session_events where session_id=? and seq<? order by seq",chat,inherited).stream().map(row->json((String)row.get("event_json"))).toList();
  sync.append(owner,created,new DesktopHistorySync.Batch(device,events));return created;
 }
 DesktopInteractions.Result accepted(String child){return new DesktopInteractions.Result("epoch","accepted",mapper.valueToTree(Map.of("sessionId",child)));}

 @Test void exactForkIsClaimedOnceAndReceiptLookupSurvivesMoreThanOneHundredOtherCommands(){
  var request=command("fork-request",1);
  assertEquals(true,interactions.state(owner,chat).get("sessionForks"));
  assertEquals("queued",interactions.enqueue(owner,chat,request).get("status"));
  assertEquals("queued",interactions.enqueue(owner,chat,request).get("status"));
  var delivered=interactions.claim(owner,device,"epoch");assertEquals(1,delivered.size());assertEquals("source",delivered.get(0).get("nativeId"));assertEquals("fork",delivered.get(0).get("kind"));
  assertTrue(interactions.claim(owner,device,"epoch").isEmpty());
  String created=child(request.id(),"source",2);
  interactions.acknowledge(owner,device,request.id(),accepted(created));
  interactions.acknowledge(owner,device,request.id(),accepted(created));
  for(int i=0;i<105;i++)interactions.enqueue(owner,chat,command("later-"+i,1));
  assertEquals("accepted",interactions.sessionCommand(owner,chat,request.id()).get("status"));
  assertEquals(created,((JsonNode)interactions.sessionCommand(owner,chat,request.id()).get("result")).path("sessionId").asText());
  assertEquals(2L,db.queryForObject("select next_seq from sync_sessions where id=?",Long.class,chat));
 }
 @Test void rejectsOtherAccountsConflictingPayloadsAndUnsyncedOrNonMessageCuts(){
  var request=command("same",1);interactions.enqueue(owner,chat,request);
  assertEquals(404,assertThrows(ApiException.class,()->interactions.enqueue(other,chat,request)).status);
  assertEquals(404,assertThrows(ApiException.class,()->interactions.sessionCommand(other,chat,request.id())).status);
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("same",0))).status);
  for(long seq:new long[]{-1,0,2,9007199254740992L})assertEquals(400,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command(AuthService.id(),seq))).status);
  for(String payload:new String[]{"{\"atSeq\":true}","{\"atSeq\":1.5}","{\"atSeq\":1,\"childSessionId\":\"other\"}"})
   assertEquals(400,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,new DesktopInteractions.Command(AuthService.id(),"epoch","fork",json(payload)))).status);
 }
 @Test void capabilityOfflineEpochAndAccessChangesFenceNewForks(){
  publish(false);assertEquals(false,interactions.state(owner,chat).get("sessionForks"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("legacy",1))).status);
  publish(true);db.update("update sync_devices set state_seen=0 where id=?",device);
  assertEquals(false,interactions.state(owner,chat).get("sessionForks"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("offline",1))).status);
  publish(true);var request=command("revoke",1);interactions.enqueue(owner,chat,request);sync.revokeDevice(owner,device);
  assertEquals(404,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("revoked",1))).status);
  assertEquals("expired",interactions.sessionCommand(owner,chat,request.id()).get("status"));
  sync.restoreDevice(owner,device);assertEquals(false,interactions.state(owner,chat).get("sessionForks"));
  publish(true);assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,new DesktopInteractions.Command("wrong-epoch","old","fork",json("{\"atSeq\":1}")))).status);
  interactions.publish(owner,device,new DesktopInteractions.State("epoch",json("{\"source\":{\"seq\":2,\"running\":false,\"controllable\":false}}"),json("[]"),false,true));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("read-only",1))).status);
 }
 @Test void acceptedResultMustMatchSourceCutAndDerivedChildIdentity(){
  interactions.enqueue(owner,chat,command("receipt",1));interactions.claim(owner,device,"epoch");
  String wrongParent=child("receipt","other-parent",2);
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,"receipt",accepted(wrongParent))).status);
  interactions.enqueue(owner,chat,command("wrong-cut",1));interactions.claim(owner,device,"epoch");
  String wrongCut=child("wrong-cut","source",1);
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,"wrong-cut",accepted(wrongCut))).status);
  interactions.enqueue(owner,chat,command("valid",1));interactions.claim(owner,device,"epoch");
  String valid=child("valid","source",2);
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,"receipt",accepted(valid))).status);
  interactions.acknowledge(owner,device,"valid",accepted(valid));
 }
 @Test void claimedExpiryIsUncertainAndCannotBeReplayed(){
  var request=command("expire",1);interactions.enqueue(owner,chat,request);interactions.claim(owner,device,"epoch");
  db.update("update sync_commands set expires_at=0 where id=?",request.id());
  assertEquals("uncertain",interactions.sessionCommand(owner,chat,request.id()).get("status"));
  assertEquals("uncertain",interactions.enqueue(owner,chat,request).get("status"));assertTrue(interactions.claim(owner,device,"epoch").isEmpty());
 }
 @Test void acknowledgementWaitsForCopiedContextAndRejectsADifferentMessageAtTheCut(){
  interactions.enqueue(owner,chat,command("body",1));interactions.claim(owner,device,"epoch");
  String created=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json("{\"id\":\"mobile-body\",\"version\":4,\"createdAt\":1,\"isSeeded\":true,\"parentSession\":\"source\"}"),2)).get("id");
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,"body",accepted(created))).status);
  String wrong=child("body-wrong","source",2);
  interactions.enqueue(owner,chat,command("body-wrong",1));interactions.claim(owner,device,"epoch");
  db.update("update sync_session_events set event_json=? where session_id=? and seq=1","{\"seq\":1,\"type\":\"assistant/message\",\"data\":{\"content\":[]}}",wrong);
  assertEquals(400,assertThrows(ApiException.class,()->interactions.acknowledge(owner,device,"body-wrong",accepted(wrong))).status);
 }
 @Test void maximumCommandIdentityFitsNativeChildAndLegacyPublicationClearsCapability(){
  String id="f".repeat(64);interactions.enqueue(owner,chat,command(id,1));interactions.claim(owner,device,"epoch");
  String created=child(id,"source",2);interactions.acknowledge(owner,device,id,accepted(created));
  assertEquals("accepted",interactions.sessionCommand(owner,chat,id).get("status"));
  interactions.publish(owner,device,new DesktopInteractions.State("epoch",json("{\"source\":{\"seq\":2,\"running\":false,\"controllable\":true}}")));
  assertEquals(false,interactions.state(owner,chat).get("sessionForks"));
  assertEquals(409,assertThrows(ApiException.class,()->interactions.enqueue(owner,chat,command("legacy-publication",1))).status);
 }
 @Test void inheritedFilesAndImagesReuseVerifiedObjectsButLaterAttachmentsRemainOutsideTheChild()throws Exception{
  byte[] file="before file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAADElEQVQImWNgZGIGAAAOAAeCcsnOAAAAAElFTkSuQmCC");
  byte[] later="after file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  String fileHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file));
  String imageHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
  String laterHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(later));
  var filePart=Map.of("type","file","attachment",Map.of("attachmentId","sha256:"+fileHash,"bytes",file.length,"name","before.txt"));
  var imagePart=Map.of("type","image","attachment",Map.of("attachmentId","sha256:"+imageHash,"bytes",image.length,"mediaType","image/png","width",1,"height",1));
  var laterPart=Map.of("type","file","attachment",Map.of("attachmentId","sha256:"+laterHash,"bytes",later.length,"name","later.txt"));
  sync.append(owner,chat,new DesktopHistorySync.Batch(device,List.of(
   mapper.valueToTree(Map.of("seq",2,"time",1,"type","user/message","data",Map.of("content",List.of(filePart,imagePart)))),
   mapper.valueToTree(Map.of("seq",3,"time",1,"type","user/message","data",Map.of("content",List.of(laterPart)))))));
  attachments.upload(owner,chat,fileHash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(file)));
  attachments.upload(owner,chat,imageHash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(image)));
  attachments.upload(owner,chat,laterHash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(later)));
  interactions.enqueue(owner,chat,command("attachments",2));interactions.claim(owner,device,"epoch");
  String created=child("attachments","source",3);interactions.acknowledge(owner,device,"attachments",accepted(created));
  assertEquals(Base64.getEncoder().encodeToString(file),attachments.download(owner,created,fileHash,0).get("data"));
  assertEquals(Base64.getEncoder().encodeToString(image),attachments.download(owner,created,imageHash,0).get("data"));
  assertEquals(2,((List<?>)attachments.manifest(owner,created).get("items")).size());
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(owner,created,laterHash,0)).status);
  assertEquals(404,assertThrows(ApiException.class,()->attachments.download(other,created,fileHash,0)).status);
  assertEquals(3L,db.queryForObject("select count(*) from sync_attachment_objects where principal_id=?",Long.class,owner.id()));
 }
 @Test void httpAdmissionAndExactLookupEnforceAuthentication()throws Exception{
  String body=mapper.writeValueAsString(command("http-fork",1));
  http.perform(post("/api/account/sync/sessions/"+chat+"/commands").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("queued"));
  http.perform(get("/api/account/sync/sessions/"+chat+"/commands/http-fork").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("fork"));
  http.perform(get("/api/account/sync/sessions/"+chat+"/commands/http-fork")).andExpect(status().isUnauthorized());
  http.perform(get("/api/account/sync/sessions/"+chat+"/commands/missing").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
 }
}
