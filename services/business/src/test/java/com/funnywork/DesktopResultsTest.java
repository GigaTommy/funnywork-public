package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:result-files;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class DesktopResultsTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;@Autowired MobileChatResultLinks links;@Autowired AuthService auth;@Autowired DesktopHistorySync sync;@Autowired DesktopResults results;@Autowired DesktopAttachments attachments;@Autowired ObjectMapper json;@Autowired MockMvc http;
    Identity owner,other;String token,device,session;byte[] bytes="ABCDEF".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash;
    @BeforeEach void setup()throws Exception{
        String tenant=AuthService.id();token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Owner",Set.of("VIEWER"),Set.of("sandbox"),token);owner=auth.authenticate(token);
        String second=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Other",Set.of("VIEWER"),Set.of("sandbox"),second);other=auth.authenticate(second);
        device=AuthService.id();sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));
        session=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json.readTree("{\"id\":\"native\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0)).get("id");
        hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    ObjectNode file(String digest,String name,int size){var value=json.createObjectNode().put("type","file");value.set("attachment",json.createObjectNode().put("attachmentId","sha256:"+digest).put("name",name).put("bytes",size));return value;}
    ObjectNode manifest(){var result=json.createObjectNode().put("schema",1).put("id","report").put("version",1).put("previewState","pending");result.set("original",file(hash,"报告.png",bytes.length));result.putArray("variants");result.putArray("warnings");return result;}
    ObjectNode event(int seq,ObjectNode result){var event=json.createObjectNode().put("seq",seq).put("time",1).put("type","deliverables/presented");var data=event.putObject("data").put("turn",1).put("callId","publish");var item=data.putArray("files").addObject().put("path","/local/snapshot.png");item.set("result",result);return event;}
    void append(int seq,ObjectNode result){sync.append(owner,session,new DesktopHistorySync.Batch(device,List.of(event(seq,result))));}
    void upload(){attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,0,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,0,3))));attachments.upload(owner,session,hash,new DesktopAttachments.Chunk(device,3,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,3,6))));}
    String path(){return "/api/account/sync/sessions/"+session+"/results/report/versions/1/files/original";}
    @Test void fixedVersionsReplayAndLatePreviewPreserveOriginal(){
        var result=manifest();append(0,result);append(0,result);assertEquals(1,results.versions(owner,session,"report").size());
        var reserved=result.deepCopy();reserved.withArray("variants").addObject().put("role","thumbnail").put("key","original").set("content",file(hash,"preview.png",bytes.length));assertEquals(400,assertThrows(ApiException.class,()->append(1,reserved)).status);
        upload();var completed=result.deepCopy().put("previewState","ready");completed.withArray("variants").addObject().put("role","thumbnail").put("key","thumbnail").set("content",file(hash,"preview.png",bytes.length));append(1,completed);
        assertEquals("报告.png",results.contentReference(owner,session,"report",1,"original").path("name").asText());
        var changed=completed.deepCopy();changed.withObject("/original/attachment").put("name","other.png");assertEquals(409,assertThrows(ApiException.class,()->append(2,changed)).status);
        var second=manifest().put("version",2).put("previousHash",hash);append(2,second);assertEquals(2,results.versions(owner,session,"report").size());
    }
    @Test void binaryDownloadsAuthorizeEveryRequestAndSupportRanges()throws Exception{
        append(0,manifest());http.perform(get(path()).header("Authorization","Bearer "+token)).andExpect(status().isConflict());upload();
        http.perform(get(path()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("ETag","\""+hash+"\""));
        http.perform(get(path()).header("Authorization","Bearer "+token).header("Range","bytes=1-4")).andExpect(status().isPartialContent()).andExpect(content().bytes("BCDE".getBytes())).andExpect(header().string("Content-Range","bytes 1-4/6"));
        http.perform(get(path()).header("Authorization","Bearer "+token).header("Range","bytes=-2")).andExpect(content().bytes("EF".getBytes()));
        http.perform(get(path()).header("Authorization","Bearer "+token).header("Range","bytes=8-")).andExpect(status().isRequestedRangeNotSatisfiable());
        http.perform(get(path()).header("Authorization","Bearer "+token).header("Range","bytes=1-2").header("If-Range","old")).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        http.perform(get(path())).andExpect(status().isUnauthorized());assertEquals(404,assertThrows(ApiException.class,()->results.versions(other,session,"report")).status);
    }
    @Test void aForkHasOnlyItsInheritedResultReferences()throws Exception{
        append(0,manifest());upload();var later=manifest().put("id","later");append(1,later);
        String child=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json.readTree("{\"id\":\"child\",\"version\":4,\"createdAt\":1,\"isSeeded\":true,\"parentSession\":\"native\"}"),1)).get("id");
        sync.append(owner,child,new DesktopHistorySync.Batch(device,List.of(event(0,manifest()))));
        assertEquals(1,results.versions(owner,child,"report").size());assertTrue(results.versions(owner,child,"later").isEmpty());assertThrows(ApiException.class,()->results.contentReference(owner,child,"later",1,"original"));
    }
    @Test void phoneLinksAreExplicitIdempotentAndAccountOwned()throws Exception{
        String chat=AuthService.id();
        db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,?,?,?,?,0,1,1)",chat,owner.tenant(),owner.id(),"Phone request","fixture-model");
        assertTrue(links.list(owner,chat).isEmpty());links.link(owner,chat,new MobileChatResultLinks.Link(session));links.link(owner,chat,new MobileChatResultLinks.Link(session));
        assertEquals(1,links.list(owner,chat).size());assertEquals(session,links.list(owner,chat).get(0).get("id"));
        assertEquals(404,assertThrows(ApiException.class,()->links.link(other,chat,new MobileChatResultLinks.Link(session))).status);
        assertEquals(404,assertThrows(ApiException.class,()->links.list(other,chat)).status);
        http.perform(get("/api/account/chats/"+chat+"/desktop-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(session));
        http.perform(get("/api/account/sync/result-capabilities").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.binaryRangeDownloads").value(true));
    }

}
