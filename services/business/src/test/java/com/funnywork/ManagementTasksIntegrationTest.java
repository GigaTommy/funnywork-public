package com.funnywork;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_MANAGEMENT_DB_URL:jdbc:h2:mem:management-tasks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ManagementTasksIntegrationTest {
    @Autowired AuthService auth; @Autowired ControlService control; @Autowired ManagementTasks tasks;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ManagementTaskCommands commands; @Autowired JdbcTemplate db; @Autowired MockMvc http;
    Identity admin,first,second,outsider; String token,a,b,hidden,foreign;
    ManagementTasks.Filter all(){return new ManagementTasks.Filter("","","","","",0,0);}
    Identity member(String tenant,String role,String zone){String secret=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of(zone),secret);return auth.authenticate(secret);}
    @BeforeEach void setup(){
        String tenant=AuthService.id();token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),token);admin=auth.authenticate(token);
        first=member(tenant,"OPERATOR","sandbox");second=member(tenant,"OPERATOR","sandbox");outsider=member(AuthService.id(),"ADMIN","sandbox");
        String agent=AuthService.id();db.update("insert into agent_versions(id,tenant_id,name,version,capability,status,created_by) values(?,?,?,'1.0.0','model.read.v1','PUBLISHED',?)",agent,tenant,"test-agent",admin.id());
        a=seed(first,agent,"sandbox",100);b=seed(second,agent,"sandbox",100);
        hidden=seed(first,agent,"private",200);
        String otherAgent=AuthService.id();db.update("insert into agent_versions(id,tenant_id,name,version,capability,status,created_by) values(?,?,?,'1.0.0','model.read.v1','PUBLISHED',?)",otherAgent,outsider.tenant(),"other-agent",outsider.id());
        foreign=seed(outsider,otherAgent,"sandbox",300);
    }
    String seed(Identity owner,String agent,String zone,long created){String id=AuthService.id();db.update("insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at,title) values(?,?,?,?,?,?,'RUNNING',?,?,?,?)",id,owner.tenant(),owner.id(),owner.authVersion(),zone,agent,id,id,created,"Example task");return id;}
    @Test void adminSeesOtherMembersOnlyInAuthorizedTenantAndZoneWithStablePagination(){
        var page=tasks.list(admin,all(),0,"",1);assertEquals(2L,page.get("total"));var one=(Map<?,?>)((List<?>)page.get("items")).get(0);
        var next=tasks.list(admin,all(),((Number)page.get("nextCreatedAt")).longValue(),(String)page.get("nextId"),1);
        var two=(Map<?,?>)((List<?>)next.get("items")).get(0);assertNotEquals(one.get("id"),two.get("id"));assertEquals(Set.of(a,b),Set.of(one.get("id"),two.get("id")));assertFalse(next.containsKey("nextId"));
        assertEquals(1L,tasks.list(admin,new ManagementTasks.Filter("","","",first.id(),"",0,0),0,"",50).get("total"));
        assertEquals(0L,tasks.list(admin,new ManagementTasks.Filter("","FAILED","","","",0,0),0,"",50).get("total"));
        var members=(List<?>)tasks.members(admin,"").get("items");assertEquals(2,members.size());
        assertEquals(Set.of(first.id(),second.id()),members.stream().map(item->((Map<?,?>)item).get("id")).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void privateAndForeignDetailsAndEventsAreNotAccessible(){for(String id:List.of(hidden,foreign)){
        assertEquals(404,assertThrows(ApiException.class,()->tasks.detail(admin,id)).status);
        assertEquals(404,assertThrows(ApiException.class,()->tasks.events(admin,id,0)).status);
    }assertEquals(403,assertThrows(ApiException.class,()->tasks.list(admin,new ManagementTasks.Filter("","","private","","",0,0),0,"",50)).status);}
    @Test void metadataDoesNotIncludeInputsResultsOrEventPayload(){
        db.update("insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) values(?,?,1,1,?,100)",a,admin.tenant(),a);
        db.update("insert into work_units(id,task_id,tenant_id,ordinal,input_text,state) values(?,?,?,0,'secret input','READY')",AuthService.id(),a,admin.tenant());
        db.update("insert into events(tenant_id,task_id,event_type,payload_json,created_at) values(?,?,'TASK_CREATED','{\"secret\":\"do not expose\"}',100)",admin.tenant(),a);
        var detail=tasks.detail(admin,a);assertEquals(false,detail.get("contentIncluded"));assertFalse(detail.toString().contains("secret"));
        var events=tasks.events(admin,a,0);assertFalse(events.toString().contains("payload"));assertFalse(events.toString().contains("secret"));
    }
    @Test void personalTaskBehaviorAndRolesRemainUnchanged(){
        assertEquals(1,((List<?>)control.listTasks(first,"",50).get("items")).size());
        assertEquals(404,assertThrows(ApiException.class,()->control.getTask(first,b)).status);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.detail(first,a)).status);
        assertEquals(403,assertThrows(ApiException.class,()->control.listTasks(admin,"",50)).status);
    }
    @Test void revokedScopeIsRecheckedAndFiltersValidated(){
        db.update("update principals set zones='[]' where id=?",admin.id());assertEquals(0L,tasks.list(admin,all(),0,"",50).get("total"));
        assertEquals(404,assertThrows(ApiException.class,()->tasks.detail(admin,a)).status);
        assertEquals(400,assertThrows(ApiException.class,()->tasks.list(admin,all(),1,"",50)).status);
    }
    @Test void routesAreReadOnlyAndUseDedicatedIdentityPermission() throws Exception {
        assertEquals("funnyworker:task-center:read",ManagementIdentity.permission("GET","/api/v1/management-tasks"));
        assertEquals("funnyworker:task-center:read",ManagementIdentity.permission("GET","/api/v1/management-tasks/"+a+"/events"));
        http.perform(get("/api/v1/management-tasks")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/management-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        http.perform(post("/api/v1/management-tasks/"+a+"/cancel").header("Authorization","Bearer "+token)).andExpect(status().is4xxClientError());
    }
    String department(String name){return (String)((Map<?,?>)tasks.department(admin,new ManagementTasks.Department(name,"sandbox"))).get("id");}
    String project(){String id=AuthService.id();db.update("insert into execution_projects(id,tenant_id,zone,name) values(?,?,'sandbox','Project')",id,admin.tenant());return id;}
    @Test void departmentAndProjectScopesConstrainEveryReadAndCannotSelfExpand(){
        String dept=department("Finance"),otherDept=department("Engineering"),project=project();
        tasks.assign(admin,a,new ManagementTasks.Context(dept,project,0));tasks.assign(admin,b,new ManagementTasks.Context(otherDept,project,0));
        Identity limited=member(admin.tenant(),"ADMIN","sandbox");tasks.saveLimits(admin,limited.id(),new ManagementTasks.Limits(true,Set.of(dept),true,Set.of(project),0));
        assertEquals(1L,tasks.list(limited,all(),0,"",50).get("total"));assertEquals(1,((List<?>)tasks.members(limited,"").get("items")).size());
        assertEquals(404,assertThrows(ApiException.class,()->tasks.detail(limited,b)).status);assertEquals(404,assertThrows(ApiException.class,()->tasks.events(limited,b,0)).status);assertEquals(404,assertThrows(ApiException.class,()->tasks.placements(limited,b)).status);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.saveLimits(limited,limited.id(),new ManagementTasks.Limits(false,Set.of(),false,Set.of(),1))).status);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.assign(limited,a,new ManagementTasks.Context(otherDept,project,1))).status);
        tasks.saveLimits(admin,limited.id(),new ManagementTasks.Limits(true,Set.of(),true,Set.of(project),1));assertEquals(0L,tasks.list(limited,all(),0,"",50).get("total"));
    }
    @Test void filterDirectoriesDeduplicateAndPaginateOnlyVisibleResources(){
        String shared=department("Shared"),unused=department("Unused"),project=project();
        tasks.assign(admin,a,new ManagementTasks.Context(shared,project,0));
        tasks.assign(admin,b,new ManagementTasks.Context(shared,project,0));
        var options=tasks.options(admin);
        assertEquals(1,((List<?>)options.get("departments")).size());
        assertEquals(shared,((Map<?,?>)((List<?>)options.get("departments")).get(0)).get("id"));
        assertFalse(options.toString().contains(unused));
        Identity limited=member(admin.tenant(),"ADMIN","sandbox");
        tasks.saveLimits(admin,limited.id(),new ManagementTasks.Limits(true,Set.of(),false,Set.of(),0));
        assertTrue(((List<?>)tasks.options(limited).get("departments")).isEmpty());
        assertTrue(((List<?>)tasks.options(limited).get("projects")).isEmpty());
        for(int i=0;i<104;i++){
            String dep=department("Directory "+i),prj=project(),task=AuthService.id();
            db.update("insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at) select ?,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,?,'hash',created_at from tasks where id=?",task,task,a);
            tasks.assign(admin,task,new ManagementTasks.Context(dep,prj,0));
        }
        var first=tasks.options(admin,"","");
        assertEquals(100,((List<?>)first.get("departments")).size());
        assertEquals(100,((List<?>)first.get("projects")).size());
        var next=tasks.options(admin,(String)first.get("nextDepartment"),(String)first.get("nextProject"));
        assertEquals(5,((List<?>)next.get("departments")).size());
        assertEquals(5,((List<?>)next.get("projects")).size());
        assertEquals("",next.get("nextDepartment"));assertEquals("",next.get("nextProject"));
        var ids=new HashSet<Object>();for(var page:List.of(first,next))for(Object value:(List<?>)page.get("departments"))assertTrue(ids.add(((Map<?,?>)value).get("id")));
        assertTrue(((List<?>)tasks.options(admin,"__END__","__END__").get("departments")).isEmpty());
    }
    @Test void contextVersionAndFilterSummaryAreConsistent(){
        String dept=department("Finance"),project=project();tasks.assign(admin,a,new ManagementTasks.Context(dept,project,0));
        var page=tasks.list(admin,new ManagementTasks.Filter("","","","","",0,0,dept,project),0,"",50);assertEquals(1L,page.get("total"));assertEquals("Finance",((Map<?,?>)((List<?>)page.get("items")).get(0)).get("department_name"));
        assertEquals(409,assertThrows(ApiException.class,()->tasks.assign(admin,a,new ManagementTasks.Context(dept,project,0))).status);
        assertNull(tasks.detail(admin,b).get("department_id"));
    }
    @Test void commandsPreviewRecheckStateAndIdempotentReceiptKeepAdminAttribution(){
        var preview=(Map<?,?>)commands.preview(admin,a,"PAUSE");String key=AuthService.id();var command=new ManagementTaskCommands.Command("PAUSE",(String)preview.get("expectedToken"));
        Object receipt=commands.execute(admin,a,key,command);assertEquals(receipt,commands.execute(admin,a,key,command));
        assertEquals(receipt,commands.receipt(admin,a,key));assertEquals(true,db.queryForObject("select dispatch_paused from tasks where id=?",Boolean.class,a));assertEquals(first.id(),db.queryForObject("select owner_id from tasks where id=?",String.class,a));
        assertEquals(1,db.queryForObject("select count(*) from events where task_id=? and actor_id=? and event_type='TASK_DISPATCH_PAUSED'",Integer.class,a,admin.id()));
        assertEquals(409,assertThrows(ApiException.class,()->commands.execute(admin,a,key,new ManagementTaskCommands.Command("CANCEL",command.expectedToken()))).status);
        var resume=(Map<?,?>)commands.preview(admin,a,"RESUME");db.update("update tasks set state='FAILED' where id=?",a);
        assertEquals(409,assertThrows(ApiException.class,()->commands.execute(admin,a,AuthService.id(),new ManagementTaskCommands.Command("RESUME",(String)resume.get("expectedToken")))).status);
    }
    @Test void commandCannotCrossScopeOrUseStaleAuthorizationAndCancelDoesNotClaimPhysicalStop(){
        assertEquals(404,assertThrows(ApiException.class,()->commands.preview(admin,foreign,"CANCEL")).status);
        assertEquals(403,assertThrows(ApiException.class,()->commands.preview(first,a,"CANCEL")).status);
        var preview=(Map<?,?>)commands.preview(admin,a,"CANCEL");var receipt=(Map<?,?>)commands.execute(admin,a,AuthService.id(),new ManagementTaskCommands.Command("CANCEL",(String)preview.get("expectedToken")));
        assertEquals(false,receipt.get("physicalStopConfirmed"));assertEquals("CANCELLED",receipt.get("state"));
        assertEquals("funnyworker:task-center:scope",ManagementIdentity.permission("PUT","/api/v1/management-tasks/scopes/user"));
        assertEquals("funnyworker:task-center:write",ManagementIdentity.permission("POST","/api/v1/management-tasks/"+a+"/commands"));
        assertEquals("funnyworker:task-center:read",ManagementIdentity.permission("POST","/api/v1/management-tasks/"+a+"/preview"));
    }

    @Test void cancellationOfUnknownExternalOperationPreservesReconciliation(){
        db.update("insert into external_operations(id,tenant_id,owner_id,task_id,tool_name,business_key_hash,input_hash,contract_hash,effect,state,created_at,updated_at) values(?,?,?,?,?,?,?,?,'WRITE','UNKNOWN',100,100)",AuthService.id(),admin.tenant(),first.id(),a,"external",AuthService.id(),"x","y");
        var preview=(Map<?,?>)commands.preview(admin,a,"CANCEL");var result=(Map<?,?>)commands.execute(admin,a,AuthService.id(),new ManagementTaskCommands.Command("CANCEL",(String)preview.get("expectedToken")));
        assertEquals("RECONCILING",result.get("state"));assertEquals(false,result.get("physicalStopConfirmed"));assertEquals("UNKNOWN",db.queryForObject("select state from external_operations where task_id=?",String.class,a));
    }
    @Test void terminalMetadataIsScopedAndDoesNotExposeFrozenAuthorizationSecrets(){
        String node=(String)control.registerNode(admin,new Requests.Node("Executor","sandbox",1)).get("nodeId"),unit=AuthService.id(),attempt=AuthService.id(),device=AuthService.id();
        db.update("insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) values(?,?,1,1,?,100)",a,admin.tenant(),a);
        db.update("insert into work_units(id,task_id,tenant_id,ordinal,input_text,state) values(?,?,?,0,'secret','RUNNING')",unit,a,admin.tenant());
        db.update("insert into attempts(id,unit_id,node_id,tenant_id,epoch,state,expires_at,created_at) values(?,?,?,?,1,'RUNNING',1000,100)",attempt,unit,node,admin.tenant());
        db.update("insert into sync_devices(id,tenant_id,principal_id,name,platform,created_at,last_seen) values(?,?,?,'Mac executor','darwin',100,100)",device,admin.tenant(),first.id());
        db.update("insert into execution_placements values(?,?,?,?,?,?,?,?,?,?,?,?)",attempt,admin.tenant(),a,node,device,"runtime","repository","DEVELOP","hash","{\"route\":{\"project_id\":\"project\",\"pool_id\":\"pool\"},\"private\":\"secret-key\"}",1,100);
        var page=tasks.placements(admin,a);var row=(Map<?,?>)((List<?>)page.get("items")).get(0);assertEquals("Mac executor",row.get("device_name"));assertEquals("project",row.get("project_id"));assertEquals(false,row.get("current_attempt"));assertFalse(page.toString().contains("secret-key"));assertFalse(row.containsKey("authorization_json"));
        assertEquals(404,assertThrows(ApiException.class,()->tasks.placements(outsider,a)).status);
        for(int epoch=2;epoch<=102;epoch++)db.update("insert into attempts(id,unit_id,node_id,tenant_id,epoch,state,expires_at,created_at) values(?,?,?,?,?,'SUCCEEDED',1000,100)",AuthService.id(),unit,node,admin.tenant(),epoch);
        var firstPage=tasks.placements(admin,a);assertEquals(true,firstPage.get("hasMore"));assertEquals(100,((List<?>)firstPage.get("items")).size());
        var lastPage=tasks.placements(admin,a,((Number)firstPage.get("nextCreatedAt")).longValue(),(String)firstPage.get("nextId"));assertEquals(2,((List<?>)lastPage.get("items")).size());assertEquals(false,lastPage.get("hasMore"));
        var ids=new HashSet<>();for(Object item:(List<?>)firstPage.get("items"))ids.add(((Map<?,?>)item).get("attempt_id"));for(Object item:(List<?>)lastPage.get("items"))assertTrue(ids.add(((Map<?,?>)item).get("attempt_id")));assertEquals(102,ids.size());

    }

    @Test void scopeWithdrawalAfterPreviewPreventsCommandAndOldPreviewCannotBeReusedAfterRestore(){
        var preview=(Map<?,?>)commands.preview(admin,a,"PAUSE");String key=AuthService.id();var command=new ManagementTaskCommands.Command("PAUSE",(String)preview.get("expectedToken"));
        tasks.saveLimits(admin,admin.id(),new ManagementTasks.Limits(true,Set.of(),false,Set.of(),0));assertEquals(404,assertThrows(ApiException.class,()->commands.execute(admin,a,key,command)).status);
        Identity restorer=member(admin.tenant(),"ADMIN","sandbox");tasks.saveLimits(restorer,admin.id(),new ManagementTasks.Limits(false,Set.of(),false,Set.of(),1));
        assertEquals(409,assertThrows(ApiException.class,()->commands.execute(admin,a,key,command)).status);assertEquals(0,db.queryForObject("select count(*) from management_task_commands where task_id=?",Integer.class,a));
    }

    @Test void largeClassificationDirectoriesPageIndependentlyWithoutRestartingCompletedDimension(){
        for(int i=0;i<102;i++)db.update("insert into task_departments values(?,?,'sandbox',?,100)",AuthService.id(),admin.tenant(),"D"+i);
        for(int i=0;i<203;i++)db.update("insert into execution_projects(id,tenant_id,zone,name) values(?,?,'sandbox',?)",AuthService.id(),admin.tenant(),"P"+i);
        var first=(Map<?,?>)tasks.scopeOptions(admin,"","");assertEquals(100,((List<?>)first.get("departments")).size());assertEquals(100,((List<?>)first.get("projects")).size());
        var second=(Map<?,?>)tasks.scopeOptions(admin,(String)first.get("nextDepartment"),(String)first.get("nextProject"));assertEquals(2,((List<?>)second.get("departments")).size());assertEquals("",second.get("nextDepartment"));
        var third=(Map<?,?>)tasks.scopeOptions(admin,"__END__",(String)second.get("nextProject"));assertTrue(((List<?>)third.get("departments")).isEmpty());assertEquals(3,((List<?>)third.get("projects")).size());assertEquals("",third.get("nextProject"));
    }

    @Test void httpPreviewCommandAndPolicyBindingUseTheRealControllerContracts()throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var response=http.perform(post("/api/v1/management-tasks/"+a+"/preview").header("Authorization","Bearer "+token).contentType("application/json").content("{\"action\":\"PAUSE\"}")).andExpect(status().isOk()).andReturn();
        String fingerprint=mapper.readTree(response.getResponse().getContentAsString()).path("expectedToken").asText();
        http.perform(post("/api/v1/management-tasks/"+a+"/commands").header("Authorization","Bearer "+token).header("Idempotency-Key",AuthService.id()).contentType("application/json").content(mapper.writeValueAsString(new ManagementTaskCommands.Command("PAUSE",fingerprint)))).andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));
        http.perform(put("/api/v1/management-tasks/scopes/"+admin.id()).header("Authorization","Bearer "+token).contentType("application/json").content("{\"expectedVersion\":0,\"restrictDepartments\":false,\"departments\":[],\"restrictProjects\":false,\"projects\":[]}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        http.perform(get("/api/v1/management-tasks/scope-options").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.nextDepartment").value(""));
    }

    @Autowired TaskAttribution attribution;
    @Test void creationAttributionIsLocalVersionedAndDoesNotRewriteHistoryOnMoveOrCorrection() throws Exception {
        String finance=department("Finance"),engineering=department("Engineering");
        http.perform(put("/api/v1/management-tasks/member-contexts/"+first.id()).header("Authorization","Bearer "+token).contentType("application/json").content("{\"zone\":\"sandbox\",\"departmentId\":\""+finance+"\",\"expectedVersion\":0}")).andExpect(status().isOk());
        http.perform(get("/api/v1/management-tasks/member-contexts/"+first.id()).param("zone","sandbox").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.departmentId").value(finance));
        String agent=db.queryForObject("select agent_id from tasks where id=?",String.class,a);var request=new Requests.Task(agent,"sandbox",List.of("private input"));
        String task=(String)control.createTask(first,"creation-test",request).get("taskId");
        var frozen=(Map<?,?>)((List<?>)tasks.detail(admin,task).get("creationAttribution")).get(0);assertEquals(finance,frozen.get("department_id"));assertEquals("LOCAL_MEMBER_ASSIGNMENT",frozen.get("department_basis"));
        attribution.assign(admin,first.id(),new TaskAttribution.Assignment("sandbox",engineering,1));
        assertEquals(task,control.createTask(first,"creation-test",request).get("taskId"));assertEquals(finance,tasks.detail(admin,task).get("department_id"));
        tasks.assign(admin,task,new ManagementTasks.Context(engineering,"",1));assertEquals("EXPLICIT_ASSIGNMENT",tasks.detail(admin,task).get("context_basis"));assertEquals(frozen,((List<?>)tasks.detail(admin,task).get("creationAttribution")).get(0));
        assertEquals(0,db.queryForObject("select count(*) from task_creation_attribution where task_id=?",Integer.class,a));
        String next=(String)control.createTask(first,"after-move",request).get("taskId");assertEquals(engineering,tasks.detail(admin,next).get("department_id"));
    }
    @Test void memberAssignmentsRequireFullScopeAndCannotBeSelfExpandedOrCrossTenant(){
        String finance=department("Finance");Identity restricted=member(admin.tenant(),"ADMIN","sandbox");tasks.saveLimits(admin,restricted.id(),new ManagementTasks.Limits(true,Set.of(finance),false,Set.of(),0));
        assertEquals(403,assertThrows(ApiException.class,()->attribution.assign(restricted,first.id(),new TaskAttribution.Assignment("sandbox",finance,0))).status);
        assertEquals(404,assertThrows(ApiException.class,()->attribution.assignment(outsider,first.id(),"sandbox")).status);
        assertEquals(403,assertThrows(ApiException.class,()->attribution.assign(admin,first.id(),new TaskAttribution.Assignment("private",finance,0))).status);
        attribution.assign(admin,first.id(),new TaskAttribution.Assignment("sandbox",finance,0));assertEquals(409,assertThrows(ApiException.class,()->attribution.assign(admin,first.id(),new TaskAttribution.Assignment("sandbox","",0))).status);
        attribution.assign(admin,first.id(),new TaskAttribution.Assignment("sandbox","",1));assertEquals("",attribution.assignment(admin,first.id(),"sandbox").get("departmentId"));
    }
    @Test void specializedProjectIsCapturedAtCreationWhileUnattributedTasksRemainUnknown(){
        String agent=db.queryForObject("select agent_id from tasks where id=?",String.class,a),project=project();var request=new Requests.Task(agent,"sandbox",List.of("input"));
        String task=(String)control.createScopedTask(first,"project-snapshot",request,project).get("taskId");assertEquals(project,tasks.detail(admin,task).get("project_id"));var frozen=(Map<?,?>)((List<?>)tasks.detail(admin,task).get("creationAttribution")).get(0);assertEquals("SPECIALIZED_TASK_RESOURCE",frozen.get("project_basis"));
        String plain=(String)control.createTask(second,"unknown-snapshot",request).get("taskId");var unknown=(Map<?,?>)((List<?>)tasks.detail(admin,plain).get("creationAttribution")).get(0);assertEquals("UNKNOWN",unknown.get("department_basis"));assertEquals("UNKNOWN",unknown.get("project_basis"));assertNull(tasks.detail(admin,plain).get("context_version"));
        String bad=AuthService.id();assertThrows(ApiException.class,()->control.createScopedTask(first,"bad-project",request,bad));assertEquals(0,db.queryForObject("select count(*) from tasks where tenant_id=? and request_key='bad-project'",Integer.class,admin.tenant()));
    }

    @Autowired ManagementTaskContent content; @Autowired ArtifactService artifacts;
    String contentUnit(String task,int ordinal){String id=AuthService.id();if(db.queryForObject("select count(*) from task_plans where task_id=?",Integer.class,task)==0)db.update("insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) values(?,?,1,1,?,100)",task,admin.tenant(),task);db.update("insert into work_units(id,task_id,ordinal,input_text,state,epoch,attempts,tenant_id) values(?,?,?,'private input','READY',0,0,?)",id,task,ordinal,admin.tenant());return id;}
    @Test void metadataAdminNeedsSeparateContentGrantAndEveryReadIsAudited() throws Exception {
        String unit=contentUnit(a,0);assertEquals(403,assertThrows(ApiException.class,()->content.input(admin,a,unit)).status);
        http.perform(get("/api/v1/management-tasks/"+a+"/units/"+unit+"/input").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        content.save(admin,admin.id(),new ManagementTaskContent.Access(true,false,0));
        http.perform(get("/api/v1/management-tasks/"+a+"/units/"+unit+"/input").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.input_text").value("private input"));
        assertEquals(1,db.queryForObject("select count(*) from events where actor_id=? and event_type='MANAGEMENT_TASK_INPUT_READ' and payload_json not like '%private input%'",Integer.class,admin.id()));
        assertEquals(403,assertThrows(ApiException.class,()->content.artifacts(admin,a,"",0)).status);
        assertEquals(404,assertThrows(ApiException.class,()->content.input(admin,b,unit)).status);
        content.save(admin,admin.id(),new ManagementTaskContent.Access(false,false,1));assertEquals(403,assertThrows(ApiException.class,()->content.input(admin,a,unit)).status);
    }
    @Test void contentGrantDoesNotBypassTaskScopesOrPermitRestrictedSelfGrant(){
        String dept=department("Finance"),other=department("Other"),unit=contentUnit(a,0);tasks.assign(admin,a,new ManagementTasks.Context(dept,"",0));Identity limited=member(admin.tenant(),"ADMIN","sandbox");
        content.save(admin,limited.id(),new ManagementTaskContent.Access(true,true,0));tasks.saveLimits(admin,limited.id(),new ManagementTasks.Limits(true,Set.of(other),false,Set.of(),0));
        assertEquals(404,assertThrows(ApiException.class,()->content.input(limited,a,unit)).status);
        assertEquals(403,assertThrows(ApiException.class,()->content.save(limited,limited.id(),new ManagementTaskContent.Access(true,true,1))).status);
        assertEquals(404,assertThrows(ApiException.class,()->content.artifacts(admin,foreign,"",0)).status);
        assertEquals(403,assertThrows(ApiException.class,()->content.save(first,admin.id(),new ManagementTaskContent.Access(true,true,0))).status);
    }
    @Test void artifactContentIsBoundToTheVisibleTaskAndImmutableVersion() throws Exception {
        content.save(admin,admin.id(),new ManagementTaskContent.Access(false,true,0));String encoded=Base64.getEncoder().encodeToString("<html>opaque file</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String artifact=(String)artifacts.create(first,new ArtifactService.Create("report.html","sandbox",encoded)).get("artifactId");artifacts.attach(first,a,new ArtifactService.Attachment(artifact,1));
        var page=(Map<?,?>)content.artifacts(admin,a,"",0);assertEquals(1,((List<?>)page.get("items")).size());assertEquals(false,page.get("businessVerified"));assertFalse(page.toString().contains(encoded));
        http.perform(get("/api/v1/management-tasks/"+a+"/artifacts/"+artifact+"/1").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.content_base64").value(encoded)).andExpect(jsonPath("$.businessVerified").value(false));
        assertEquals(404,assertThrows(ApiException.class,()->content.artifact(admin,b,artifact,1)).status);assertEquals(404,assertThrows(ApiException.class,()->content.artifact(admin,a,artifact,2)).status);
        assertEquals(1,db.queryForObject("select count(*) from events where actor_id=? and event_type='MANAGEMENT_TASK_ARTIFACT_READ'",Integer.class,admin.id()));
    }
    @Test void artifactDirectoryPaginatesWithoutMissingLinkedFiles(){
        content.save(admin,admin.id(),new ManagementTaskContent.Access(false,true,0));String encoded=Base64.getEncoder().encodeToString(new byte[]{1,2,3});
        for(int index=0;index<102;index++){String artifact=(String)artifacts.create(first,new ArtifactService.Create("file-"+index,"sandbox",encoded)).get("artifactId");artifacts.attach(first,a,new ArtifactService.Attachment(artifact,1));}
        var page=(Map<?,?>)content.artifacts(admin,a,"",0);assertEquals(100,((List<?>)page.get("items")).size());assertEquals(true,page.get("hasMore"));var next=(Map<?,?>)content.artifacts(admin,a,(String)page.get("nextArtifact"),((Number)page.get("nextVersion")).intValue());assertEquals(2,((List<?>)next.get("items")).size());assertEquals(false,next.get("hasMore"));
        var ids=new HashSet<Object>();for(Object value:(List<?>)page.get("items"))ids.add(((Map<?,?>)value).get("artifact_id"));for(Object value:(List<?>)next.get("items"))assertTrue(ids.add(((Map<?,?>)value).get("artifact_id")));assertEquals(102,ids.size());
    }

    @Autowired ManagementTaskBatches batches;
    ManagementTaskBatches.Item batchItem(String task,String action){var preview=(Map<?,?>)commands.preview(admin,task,action);return new ManagementTaskBatches.Item(task,(String)preview.get("expectedToken"));}
    @Test void confirmedBatchReturnsPerTaskResultsAndReplaysWithoutDuplicateEvents() throws Exception {
        var body=new ManagementTaskBatches.Batch("PAUSE",List.of(batchItem(a,"PAUSE"),batchItem(b,"PAUSE")));String key=AuthService.id();
        var result=(Map<?,?>)batches.submit(admin,key,body);assertEquals("COMPLETED",result.get("state"));assertEquals(2,((List<?>)result.get("items")).size());assertEquals(result,batches.submit(admin,key,body));
        assertEquals(2,db.queryForObject("select count(*) from events where tenant_id=? and actor_id=? and event_type='TASK_DISPATCH_PAUSED'",Integer.class,admin.tenant(),admin.id()));
        assertEquals(409,assertThrows(ApiException.class,()->batches.submit(admin,key,new ManagementTaskBatches.Batch("PAUSE",List.of(body.items().get(0))))).status);
        http.perform(get("/api/v1/management-tasks/batches/"+key).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        assertEquals(404,assertThrows(ApiException.class,()->batches.read(outsider,key)).status);
    }
    @Test void staleBatchItemsAreRejectedWithoutStoppingValidItemsOrClaimingAllSucceeded(){
        var firstItem=batchItem(a,"CANCEL");var secondItem=batchItem(b,"CANCEL");db.update("update tasks set state='FAILED' where id=?",a);
        var result=(Map<?,?>)batches.submit(admin,AuthService.id(),new ManagementTaskBatches.Batch("CANCEL",List.of(firstItem,secondItem)));assertEquals("COMPLETED_WITH_REJECTIONS",result.get("state"));var rows=(List<?>)result.get("items");assertEquals("REJECTED",((Map<?,?>)rows.get(0)).get("state"));assertEquals("DONE",((Map<?,?>)rows.get(1)).get("state"));assertEquals(false,result.get("physicalStopConfirmed"));assertEquals("FAILED",db.queryForObject("select state from tasks where id=?",String.class,a));assertEquals("CANCELLED",db.queryForObject("select state from tasks where id=?",String.class,b));
    }
    @Test void batchPreviewAndReadDoNotRevealTasksOutsideCurrentScope(){
        var preview=(Map<?,?>)batches.preview(admin,new ManagementTaskBatches.Preview("PAUSE",List.of(a,foreign)));var rows=(List<?>)preview.get("items");assertEquals(false,((Map<?,?>)rows.get(1)).get("allowed"));assertFalse(((Map<?,?>)rows.get(1)).containsKey("title"));
        String key=AuthService.id();var body=new ManagementTaskBatches.Batch("PAUSE",List.of(batchItem(a,"PAUSE")));batches.submit(admin,key,body);String dept=department("Limited");tasks.saveLimits(admin,admin.id(),new ManagementTasks.Limits(true,Set.of(dept),false,Set.of(),0));
        var result=(Map<?,?>)batches.read(admin,key);assertEquals("SCOPE_UNAVAILABLE",result.get("state"));assertEquals(true,result.get("settled"));var hidden=(Map<?,?>)((List<?>)result.get("items")).get(0);assertFalse(hidden.containsKey("title"));assertFalse(hidden.containsKey("taskId"));assertFalse(hidden.containsKey("result"));
    }
    @Test void malformedOrInvisibleBatchTargetsCannotCreateAnExistenceOracle(){
        assertEquals(400,assertThrows(ApiException.class,()->batches.preview(admin,new ManagementTaskBatches.Preview("PAUSE",List.of(a,a)))).status);
        assertEquals(400,assertThrows(ApiException.class,()->batches.submit(admin,AuthService.id(),new ManagementTaskBatches.Batch("PAUSE",List.of(new ManagementTaskBatches.Item(a,"bad"))))).status);
        for(String task:List.of(foreign,AuthService.id())){var result=(Map<?,?>)batches.submit(admin,AuthService.id(),new ManagementTaskBatches.Batch("PAUSE",List.of(new ManagementTaskBatches.Item(task,"a".repeat(64)))));assertEquals("SCOPE_UNAVAILABLE",result.get("state"));assertFalse(result.toString().contains("title"));}
    }

    @Test void uncertainBatchStopsRemainingItemsAndResumesOnlyOriginalRequestWithoutRepeatingDoneItems(){
        String agent=db.queryForObject("select agent_id from tasks where id=?",String.class,a),third=seed(first,agent,"sandbox",400);var body=new ManagementTaskBatches.Batch("PAUSE",List.of(batchItem(a,"PAUSE"),batchItem(b,"PAUSE"),batchItem(third,"PAUSE")));String key=AuthService.id();
        org.mockito.Mockito.doThrow(new IllegalStateException("test response unavailable")).doCallRealMethod().when(commands).execute(org.mockito.ArgumentMatchers.any(Identity.class),org.mockito.ArgumentMatchers.eq(b),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(ManagementTaskCommands.Command.class));
        var result=(Map<?,?>)batches.submit(admin,key,body);assertEquals("PENDING_RECONCILIATION",result.get("state"));var items=(List<?>)result.get("items");assertEquals("DONE",((Map<?,?>)items.get(0)).get("state"));assertEquals("UNCERTAIN",((Map<?,?>)items.get(1)).get("state"));assertEquals("PENDING",((Map<?,?>)items.get(2)).get("state"));assertEquals(false,db.queryForObject("select dispatch_paused from tasks where id=?",Boolean.class,third));
        assertEquals("COMPLETED",((Map<?,?>)batches.submit(admin,key,body)).get("state"));assertEquals(1,db.queryForObject("select count(*) from events where task_id=? and event_type='TASK_DISPATCH_PAUSED'",Integer.class,a));assertEquals(3,db.queryForObject("select count(*) from management_task_commands where tenant_id=?",Integer.class,admin.tenant()));
    }

    @Test void concurrentBatchResubmissionCannotDuplicatePerTaskEffects() throws Exception {
        var body=new ManagementTaskBatches.Batch("PAUSE",List.of(batchItem(a,"PAUSE"),batchItem(b,"PAUSE")));String key=AuthService.id();var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var one=workers.submit(()->batches.submit(admin,key,body));var two=workers.submit(()->batches.submit(admin,key,body));assertEquals("COMPLETED",((Map<?,?>)one.get(10,java.util.concurrent.TimeUnit.SECONDS)).get("state"));assertEquals("COMPLETED",((Map<?,?>)two.get(10,java.util.concurrent.TimeUnit.SECONDS)).get("state"));}
        finally{workers.shutdownNow();}
        assertEquals(2,db.queryForObject("select count(*) from management_task_commands where tenant_id=?",Integer.class,admin.tenant()));assertEquals(2,db.queryForObject("select count(*) from events where tenant_id=? and event_type='TASK_DISPATCH_PAUSED'",Integer.class,admin.tenant()));
    }

    @Autowired ManagementTaskWorkflow workflow;
    @Test void generalExecutionNeverClaimsBusinessAcceptanceOrDeliveryAndWorkflowReadsStayScoped() throws Exception {
        db.update("update tasks set state='OUTPUT_READY' where id=?",a);var value=(Map<?,?>)workflow.inspect(admin,a);assertEquals("NOT_COVERED",value.get("validation"));assertEquals("NOT_COVERED",value.get("delivery"));assertEquals(false,value.get("contentIncluded"));
        assertEquals(404,assertThrows(ApiException.class,()->workflow.inspect(admin,foreign)).status);
        http.perform(get("/api/v1/management-tasks/"+a+"/workflow").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("GENERAL"));
    }

}
