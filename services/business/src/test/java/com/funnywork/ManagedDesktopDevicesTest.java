package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:managed-desktop-devices;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ManagedDesktopDevicesTest {
    @Autowired MockMvc http;
    final Map<String,String> tokens=new HashMap<>();
    @Autowired ManagedDesktopDevices devices;@Autowired AuthService auth;@Autowired DesktopHistorySync sync;@Autowired DesktopInteractions interactions;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    Identity admin,user,other;String device;
    Identity identity(String tenant,String name,String role,Set<String> zones){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",name,Set.of(role),zones,token);var actor=auth.authenticate(token);tokens.put(actor.id(),token);return actor;}
    @BeforeEach void setup(){String tenant=AuthService.id();admin=identity(tenant,"Manager","ADMIN",Set.of("sandbox"));user=identity(tenant,"Member","VIEWER",Set.of("sandbox"));other=identity(tenant,"Other department","VIEWER",Set.of("elsewhere"));device=AuthService.id();sync.registerDevice(user,new DesktopHistorySync.Device(device,"Member Mac","darwin"));}
    List<?> items(Map<String,Object> page){return (List<?>)page.get("items");}
    Map<?,?> deviceView(){return (Map<?,?>)items(devices.list(admin,"","ALL","")).get(0);}
    void publishBuild(com.fasterxml.jackson.databind.JsonNode build){
        var choices=json.createObjectNode();choices.putArray("models");choices.putArray("presets");choices.set("clientBuild",build);
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode(),null,false,false,choices));
    }
    com.fasterxml.jackson.databind.node.ObjectNode build(boolean dirty){return json.createObjectNode().put("version","1.2.3").put("commit","a".repeat(40)).put("tree","b".repeat(40)).put("dirty",dirty);}
    @Test void deviceBoundBuildIsVisibleWithoutAnyModelReceiptAndDirtyIsNotHidden(){
        assertNull(deviceView().get("client_version"));assertEquals("UNKNOWN",deviceView().get("clientVersionSource"));
        for(boolean dirty:List.of(false,true)){
            publishBuild(build(dirty));var row=deviceView();assertEquals("1.2.3",row.get("client_version"));assertEquals("DEVICE_BUILD_MANIFEST",row.get("clientVersionSource"));
            assertEquals(Map.of("version","1.2.3","commit","a".repeat(40),"tree","b".repeat(40),"dirty",dirty),row.get("clientBuild"));
            assertEquals("UNREPORTED",row.get("modelStatus"));
        }
        interactions.publish(user,device,new DesktopInteractions.State("older-runtime",json.createObjectNode()));
        assertNull(deviceView().get("clientBuild"));assertNull(deviceView().get("client_version"));assertEquals("UNKNOWN",deviceView().get("clientVersionSource"));
    }
    @Test void legacyReceiptRemainsClearlyIdentifiedAndCannotOverrideLiveBuild(){
        db.update("insert into desktop_model_receipts(tenant_id,principal_id,device_id,issued_version,issued_digest,recipient,issued_at,expires_at,settings_json,sync_device_id,client_version) values(?,?,?,0,?,'recipient',1,9223372036854775807,'{}',?,?)",user.tenant(),user.id(),"a".repeat(32),"b".repeat(64),device,"0.1.0");
        assertEquals("0.1.0",deviceView().get("client_version"));assertEquals("LEGACY_MODEL_RECEIPT",deviceView().get("clientVersionSource"));assertNull(deviceView().get("clientBuild"));
        publishBuild(build(false));assertEquals("1.2.3",deviceView().get("client_version"));assertEquals("DEVICE_BUILD_MANIFEST",deviceView().get("clientVersionSource"));
    }
    @Test void invalidBuildMetadataIsRejectedWithoutChangingThePreviouslyReportedIdentity(){
        publishBuild(build(false));
        for(var invalid:List.of(build(false).put("version","not-a-version"),build(false).put("commit","backend-main"),build(false).put("tree","a".repeat(41)),build(false).put("dirty","false"),build(false).put("extra","private-path"))){
            assertEquals(400,assertThrows(ApiException.class,()->publishBuild(invalid)).status);assertEquals("1.2.3",deviceView().get("client_version"));
        }
    }
    @Test void buildPublicationCannotTargetAnotherOwnersDevice(){
        var choices=json.createObjectNode();choices.putArray("models");choices.putArray("presets");choices.set("clientBuild",build(false));
        assertEquals(404,assertThrows(ApiException.class,()->interactions.publish(other,device,new DesktopInteractions.State("epoch",json.createObjectNode(),null,false,false,choices))).status);
        assertNull(deviceView().get("clientBuild"));
    }
    @Test void historyInventoryCountsOnlyReceivedMetadataForVisibleDevices(){
        String session=(String)sync.registerSession(user,new DesktopHistorySync.Registration(device,json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false),0)).get("id");
        var event=json.createObjectNode().put("seq",0).put("time",1).put("type","user/message").set("data",json.createObjectNode().put("private","secret"));
        sync.append(user,session,new DesktopHistorySync.Batch(device,List.of(event)));
        var row=(Map<?,?>)items(devices.list(admin,"","ALL","")).get(0);
        assertEquals(1L,row.get("historySessionCount"));assertEquals(1L,row.get("historyEventCount"));
        assertNotNull(row.get("historyLastUpdatedAt"));assertFalse(row.toString().contains("secret"));assertFalse(row.containsKey("title"));
        sync.append(user,session,new DesktopHistorySync.Batch(device,List.of(event)));
        row=(Map<?,?>)items(devices.list(admin,"","ALL","")).get(0);assertEquals(1L,row.get("historyEventCount"));
    }
    @Test void httpRoutesRequireAdministrativeAuthorityAndRevision()throws Exception {
        http.perform(get("/api/account/sync/maintenance/features").header("Authorization","Bearer "+tokens.get(user.id()))).andExpect(status().isOk()).andExpect(jsonPath("$.clientBuild").value(true));
        http.perform(get("/api/v1/desktop-devices").header("Authorization","Bearer "+tokens.get(user.id()))).andExpect(status().isForbidden());
        http.perform(get("/api/v1/desktop-devices").header("Authorization","Bearer "+tokens.get(admin.id()))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Member Mac"));
        http.perform(post("/api/v1/desktop-devices/"+device+"/revoke").header("Authorization","Bearer "+tokens.get(admin.id())).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/desktop-devices/"+device+"/revoke").header("Authorization","Bearer "+tokens.get(admin.id())).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
    }
    @Test void inventoryRespectsTenantOwnerScopesAndDoesNotExposeConversationState(){
        sync.registerDevice(other,new DesktopHistorySync.Device(AuthService.id(),"Hidden device","linux"));var foreign=identity(AuthService.id(),"Foreign","VIEWER",Set.of("sandbox"));sync.registerDevice(foreign,new DesktopHistorySync.Device(AuthService.id(),"Foreign device","win32"));
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode().set("session",json.createObjectNode().put("seq",0).put("running",false).put("secret","conversation"))));
        var result=devices.list(admin,"","ALL","");assertEquals(1,items(result).size());var row=(Map<?,?>)items(result).get(0);assertEquals(true,row.get("online"));assertEquals("Member",row.get("owner_name"));assertFalse(row.containsKey("state_json"));assertFalse(row.containsKey("runtime_epoch"));assertFalse(row.toString().contains("conversation"));
        assertEquals(403,assertThrows(ApiException.class,()->devices.list(user,"","ALL","")).status);
    }
    @Test void onlineRequiresFreshStateAndAnEnabledAccount(){
        assertEquals(0,items(devices.list(admin,"","ONLINE","")).size());interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode()));assertEquals(1,items(devices.list(admin,"","ONLINE","")).size());
        db.update("update sync_devices set state_seen=1 where id=?",device);assertEquals(0,items(devices.list(admin,"","ONLINE","")).size());
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode()));db.update("update principals set enabled=false where id=?",user.id());assertEquals(0,items(devices.list(admin,"","ONLINE","")).size());assertEquals(1,items(devices.list(admin,"","OFFLINE","")).size());
    }
    @Test void revokeExpiresQueuedCommandsAndRestoreRequiresANewHeartbeat(){
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode()));
        String session=(String)sync.registerSession(user,new DesktopHistorySync.Registration(device,json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false),0)).get("id");
        interactions.publish(user,device,new DesktopInteractions.State("epoch",json.createObjectNode().set("native",json.createObjectNode().put("seq",0).put("running",false))));
        String command=AuthService.id();interactions.enqueue(user,session,new DesktopInteractions.Command(command,"epoch","prompt",json.createObjectNode().put("text","hello").put("mode","queue")));
        var revoked=devices.access(admin,device,new DesktopHistorySync.DeviceAccess(0L),true);assertEquals(1L,revoked.get("revision"));assertEquals("expired",db.queryForObject("select status from sync_commands where id=?",String.class,command));assertEquals(1,items(devices.list(admin,"","REVOKED","")).size());
        assertEquals(409,assertThrows(ApiException.class,()->devices.access(admin,device,new DesktopHistorySync.DeviceAccess(0L),false)).status);
        assertEquals(1L,devices.access(admin,device,new DesktopHistorySync.DeviceAccess(1L),true).get("revision"));
        devices.access(admin,device,new DesktopHistorySync.DeviceAccess(1L),false);assertEquals(0,items(devices.list(admin,"","ONLINE","")).size());assertEquals("expired",db.queryForObject("select status from sync_commands where id=?",String.class,command));
    }
    @Test void crossTenantAndOutOfScopeWritesAreRejectedEvenForAdministrators(){
        String hidden=AuthService.id();sync.registerDevice(other,new DesktopHistorySync.Device(hidden,"Hidden","linux"));assertEquals(403,assertThrows(ApiException.class,()->devices.access(admin,hidden,new DesktopHistorySync.DeviceAccess(0L),true)).status);
        var foreign=identity(AuthService.id(),"Foreign","ADMIN",Set.of("sandbox"));assertEquals(404,assertThrows(ApiException.class,()->devices.access(foreign,device,new DesktopHistorySync.DeviceAccess(0L),true)).status);
        assertEquals(false,db.queryForObject("select revoked from sync_devices where id=?",Boolean.class,hidden));
    }
    @Test void keysetPaginationAdvancesAcrossHiddenDevicesAndSearchTreatsWildcardsLiterally(){
        for(int i=0;i<101;i++)sync.registerDevice(other,new DesktopHistorySync.Device(String.format("hidden-%03d",i),"Hidden","linux"));
        var first=devices.list(admin,"","ALL","");assertEquals(true,first.get("hasMore"));var next=devices.list(admin,(String)first.get("after"),"ALL","");assertEquals(false,next.get("hasMore"));assertEquals(1,items(first).size()+items(next).size());
        assertEquals(0,items(devices.list(admin,"","ALL","%")).size());assertEquals(1,items(devices.list(admin,"","ALL","member")).size());assertEquals(400,assertThrows(ApiException.class,()->devices.list(admin,"../","ALL","")).status);
        assertEquals("client",ManagementIdentity.module("/api/v1/desktop-devices"));assertEquals("client",ManagementIdentity.module("/api/v1/desktop-devices/abc/revoke"));
    }
}
