package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:management-history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ManagementHistoryTest {
    @Autowired ManagementHistory history;@Autowired DesktopHistorySync sync;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc http;
    Identity admin,owner,hidden,foreign;String token,device,desktop,mobile,employee,agent,task;
    Identity user(String tenant,String role,String zone){String key=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of(zone),key);if(role.equals("ADMIN"))token=key;return auth.authenticate(key);}
    @BeforeEach void setup(){
        String tenant=AuthService.id();admin=user(tenant,"ADMIN","sandbox");owner=user(tenant,"VIEWER","sandbox");hidden=user(tenant,"VIEWER","hidden");foreign=user(AuthService.id(),"VIEWER","sandbox");
        device=AuthService.id();sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));desktop=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json.createObjectNode().put("id","native").put("version",4).put("createdAt",1).put("isSeeded",false),0)).get("id");
        mobile=AuthService.id();db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,?,?,?,?,0,1,2)",mobile,tenant,owner.id(),"private-title","model");
        agent=AuthService.id();db.update("insert into agent_versions values(?,?,?,?,?,?,?)",agent,tenant,"test","1","model.read.v1","PUBLISHED",owner.id());
        employee=AuthService.id();db.update("insert into employee_chats(id,tenant_id,owner_id,zone,agent_id,title,created_at) values(?,?,?,?,?,?,1)",employee,tenant,owner.id(),"sandbox",agent,"private-title");
        task=AuthService.id();db.update("insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at) values(?,?,?,?,?,?,'SUCCEEDED',?,'hash',1)",task,tenant,owner.id(),owner.authVersion(),"sandbox",agent,AuthService.id());
        db.update("insert into employee_chat_turns(chat_id,ordinal,task_id,prompt,request_key,request_hash) values(?,0,?,'employee-request',?,'hash')",employee,task,AuthService.id());
    }
    List<?> items(Map<String,Object> page){return (List<?>)page.get("items");}
    String mobileFor(Identity actor,String id){db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,?,?,?,?,0,1,2)",id,actor.tenant(),actor.id(),"private-title","model");return id;}
    @Test void directoryUnifiesSourcesWithoutLeakingTitlesOrForeignScopes(){
        mobileFor(hidden,AuthService.id());mobileFor(foreign,AuthService.id());
        var page=history.list(admin,"ALL","","");assertEquals(3,items(page).size());assertFalse(page.toString().contains("private-title"));assertFalse(page.toString().contains("employee-request"));
        assertEquals(1,items(history.list(admin,"MOBILE",owner.id(),"")).size());
        assertEquals(403,assertThrows(ApiException.class,()->history.list(owner,"ALL","","")).status);
        assertEquals(400,assertThrows(ApiException.class,()->history.list(admin,"ALL","","invalid")).status);
        assertEquals(1,db.queryForObject("select count(*) from events where actor_id=? and event_type='HISTORY_DIRECTORY_READ' and payload_json like '%ALL%'",Integer.class,admin.id()));
    }
    @Test void keysetCursorAdvancesOverHiddenRowsAndAcrossSourceBoundary(){
        for(int i=0;i<101;i++)mobileFor(hidden,"hidden-"+String.format("%03d",i)+"-"+hidden.id());
        var first=history.list(admin,"MOBILE","","");assertEquals(true,first.get("hasMore"));
        var next=history.list(admin,"MOBILE","",(String)first.get("after"));assertEquals(false,next.get("hasMore"));assertEquals(1,items(first).size()+items(next).size());
        var later=history.list(admin,"ALL","","EMPLOYEE:"+employee);assertTrue(items(later).stream().allMatch(row->"MOBILE".equals(((Map<?,?>)row).get("source"))));
    }
    @Test void desktopContentUsesVisibleProjectionAndAuditNeverIncludesBody() throws Exception {
        var events=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(int i=0;i<35;i++)events.add(json.readTree("{\"seq\":"+i+",\"time\":1,\"type\":\"tool/call\",\"data\":{\"secret\":\"hidden-body\"}}"));
        events.add(json.readTree("{\"seq\":35,\"time\":1,\"type\":\"assistant/message\",\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"visible-reply\"}]}}"));
        sync.append(owner,desktop,new DesktopHistorySync.Batch(device,events));
        var first=history.content(admin,"DESKTOP",desktop,-1);assertEquals(true,first.get("hasMore"));assertTrue(items(first).isEmpty());assertEquals(31L,first.get("after"));
        var second=history.content(admin,"DESKTOP",desktop,31);assertEquals(1,items(second).size());assertEquals("ASSISTANT",((Map<?,?>)items(second).get(0)).get("role"));assertTrue(second.toString().contains("visible-reply"));assertFalse(second.toString().contains("hidden-body"));
        db.update("update sync_session_events set event_type=null,projection_version=1 where session_id=? and seq=35",desktop);
        assertEquals("ASSISTANT",((Map<?,?>)items(history.content(admin,"DESKTOP",desktop,31)).get(0)).get("role"));
        db.update("update sync_session_events set event_type='assistant/message',projection_version=2 where session_id=? and seq=35",desktop);
        var logs=db.queryForList("select payload_json from events where actor_id=? and event_type='HISTORY_CONTENT_READ'",String.class,admin.id());assertEquals(3,logs.size());assertFalse(logs.toString().contains("visible-reply"));
        assertEquals(404,assertThrows(ApiException.class,()->history.content(admin,"MOBILE",mobileFor(hidden,AuthService.id()),-1)).status);
        db.update("update principals set zones='[\"hidden\"]' where id=?",owner.id());assertEquals(404,assertThrows(ApiException.class,()->history.content(admin,"DESKTOP",desktop,-1)).status);
    }
    @Test void mobileContentPreservesPartialStateAndBoundsText(){
        String text="x".repeat(5000);db.update("insert into mobile_chat_turns values(?, ?,0,'hash',?,?,'{}',?,'FAILED',1)",AuthService.id(),mobile,text,"partial-reply",AuthService.id());
        var page=history.content(admin,"MOBILE",mobile,-1);assertEquals(2,items(page).size());var message=(Map<?,?>)items(page).get(0);assertEquals(4096,message.get("text").toString().length());assertEquals(true,message.get("truncated"));assertEquals("FAILED",((Map<?,?>)items(page).get(1)).get("state"));
    }
    private Map<String,String> seedOutput(){
        String artifact=AuthService.id(),unit=AuthService.id(),attempt=AuthService.id();byte[] bytes="original-reply".getBytes(StandardCharsets.UTF_8);String digest;
        try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new RuntimeException(e);}
        db.update("insert into artifacts values(?,?,?,?,?,1,1)",artifact,owner.tenant(),owner.id(),"sandbox","Output");db.update("insert into artifact_versions values(?,1,?,?,?,1)",artifact,Base64.getEncoder().encodeToString(bytes),digest,bytes.length);
        db.update("insert into task_plans(task_id,version,tenant_id,input_count,request_hash,created_at) values(?,1,?,1,'hash',1)",task,owner.tenant());
        db.update("insert into work_units(id,task_id,tenant_id,ordinal,input_text,state) values(?,?,?,0,'input','SUCCEEDED')",unit,task,owner.tenant());
        String node=AuthService.id();auth.create(node,owner.tenant(),"NODE","Node",Set.of(),Set.of("sandbox"),AuthService.id());db.update("insert into nodes(id,tenant_id,zone,capacity,capability,status,last_seen) values(?,?,'sandbox',1,'model.read.v1','ONLINE',1)",node,owner.tenant());
        db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,0,'SUCCEEDED',1,1,?)",attempt,unit,node,owner.tenant());
        db.update("insert into unit_results values(?,?,?,1)",unit,attempt,json.valueToTree(Map.of("kind","MODEL_OUTPUT","artifactId",artifact,"sha256",digest)).toString());
        db.update("insert into employee_chat_instructions values(?,?,?,?,?,'correction',1,0,1)",AuthService.id(),employee,task,AuthService.id(),"hash");
        return Map.of("artifact",artifact,"node",node,"result",json.valueToTree(Map.of("kind","MODEL_OUTPUT","artifactId",artifact,"sha256",digest)).toString());
    }
    @Test void employeeContentReadsOnlyOriginalOwnedVerifiedOutput(){
        var seed=seedOutput();String artifact=seed.get("artifact");
        var page=history.content(admin,"EMPLOYEE",employee,-1);assertTrue(page.toString().contains("original-reply"));assertTrue(page.toString().contains("version=1"));
        assertTrue(page.toString().contains("correction"));
        db.update("update artifact_versions set sha256='changed' where artifact_id=?",artifact);page=history.content(admin,"EMPLOYEE",employee,-1);assertFalse(page.toString().contains("original-reply"));assertTrue(page.toString().contains("REFERENCE_ONLY"));
    }
    @Test void legacyAdminTokenCannotBypassDedicatedContentPermission() throws Exception {
        assertEquals("funnyworker:history:read",ManagementIdentity.permission("GET","/api/v1/management-history"));
        assertEquals("funnyworker:history:content",ManagementIdentity.permission("GET","/api/v1/management-history/DESKTOP/"+desktop+"/content"));
        http.perform(get("/api/v1/management-history/DESKTOP/"+desktop+"/content").header("Authorization","Bearer "+token)).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/v1/management-history").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }

    @Test void employeeOutputAndCorrectionContinuationDoesNotSkipRecordsOrTurns(){
        var seed=seedOutput();String artifact=seed.get("artifact"),node=seed.get("node"),raw=seed.get("result");
        db.update("update task_plans set input_count=40 where task_id=? and version=1",task);
        for(int i=1;i<40;i++){
            String unit=AuthService.id(),attempt=AuthService.id();
            db.update("insert into work_units(id,task_id,tenant_id,ordinal,input_text,state) values(?,?,?,?,'input','SUCCEEDED')",unit,task,owner.tenant(),i);
            db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id) values(?,?,?,0,'SUCCEEDED',1,1,?)",attempt,unit,node,owner.tenant());
            db.update("insert into unit_results values(?,?,?,1)",unit,attempt,raw);
            // Equal instruction positions exercise the id tie-breaker as well as the row cap.
            db.update("insert into employee_chat_instructions values(?,?,?,?,?,?,1,0,1)",AuthService.id(),employee,task,AuthService.id(),"hash","correction-"+i);
        }
        String nextTask=AuthService.id();db.update("insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at) values(?,?,?,?,?,?,'SUCCEEDED',?,'hash',2)",nextTask,owner.tenant(),owner.id(),owner.authVersion(),"sandbox",agent,AuthService.id());
        db.update("insert into employee_chat_turns(chat_id,ordinal,task_id,prompt,request_key,request_hash) values(?,1,?,'next-request',?,'hash')",employee,nextTask,AuthService.id());
        var first=history.content(admin,"EMPLOYEE",employee,-1);assertEquals(-1L,first.get("after"));assertEquals(true,first.get("hasMore"));assertTrue(first.get("continuation").toString().contains(":OUTPUT:"));
        var seen=new ArrayList<Object>();var page=first;int pages=0;
        while(true){
            seen.addAll(items(page));pages++;assertTrue(pages<=8);
            if(!Boolean.TRUE.equals(page.get("hasMore")))break;
            page=history.content(admin,"EMPLOYEE",employee,((Number)page.get("after")).longValue(),(String)page.get("continuation"));
        }
        assertEquals(4,pages);assertEquals(1L,page.get("after"));
        var outputIds=new HashSet<Object>();var instructionIds=new HashSet<Object>();int prompts=0;
        for(var value:seen){var item=(Map<?,?>)value;
            if("ASSISTANT".equals(item.get("role"))){assertTrue(outputIds.add(item.get("unitId")));assertEquals("ORIGINAL_OUTPUT",item.get("availability"));assertEquals(artifact,item.get("artifactId"));}
            if("CORRECTION".equals(item.get("role")))assertTrue(instructionIds.add(item.get("instructionId")));
            if("USER".equals(item.get("role")))prompts++;
        }
        assertEquals(40,outputIds.size());assertEquals(40,instructionIds.size());assertEquals(2,prompts);
        assertEquals(items(first),items(history.content(admin,"EMPLOYEE",employee,-1)));
        db.update("update principals set zones='[\"hidden\"]' where id=?",owner.id());
        assertEquals(404,assertThrows(ApiException.class,()->history.content(admin,"EMPLOYEE",employee,-1,(String)first.get("continuation"))).status);
    }
    @Test void invalidAndWrongSourceContinuationsAreRejected(){
        for(String cursor:List.of("bad","0:OUTPUT:-1","0:OUTPUT:9999999999","0:CORRECTION:1:../","0:OTHER:0"))assertEquals(400,assertThrows(ApiException.class,()->history.content(admin,"EMPLOYEE",employee,-1,cursor)).status);
        assertEquals(400,assertThrows(ApiException.class,()->history.content(admin,"EMPLOYEE",employee,0,"0:OUTPUT:1")).status);
        assertEquals(400,assertThrows(ApiException.class,()->history.content(admin,"MOBILE",mobile,-1,"0:OUTPUT:1")).status);
    }
}
