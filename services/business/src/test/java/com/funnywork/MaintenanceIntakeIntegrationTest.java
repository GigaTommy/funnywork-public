package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:maintenanceintake;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.execution-enforcement=false"})
@AutoConfigureMockMvc
class MaintenanceIntakeIntegrationTest {
    @Autowired MaintenanceIntake intake;@Autowired MaintenanceService maintenance;@Autowired MaintenanceDesktopChannel desktops;
    @Autowired DesktopHistorySync history;@Autowired DesktopInteractions interactions;@Autowired ControlService control;
    @Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired Json json;@Autowired ObjectMapper mapper;@Autowired MockMvc http;
    Identity admin;String tenant,repo,device,attempt;Map<String,Object> payload;
    @BeforeEach void setup(){tenant=AuthService.id();String token=AuthService.id();auth.create(token,tenant,"USER","Maintainer",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("sandbox"),token);admin=auth.authenticate(token);
        repo=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/project","main","sandbox",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
        intake.configure(admin,new MaintenanceIntake.Configuration(repo,"a".repeat(40),List.of("scripts/checks"),true,true,"","macos"));
        device=AuthService.id();history.registerDevice(admin,new DesktopHistorySync.Device(device,"Original Mac","darwin"));desktops.bind(admin,new MaintenanceDesktopChannel.Binding(device,"sandbox"));
    }
    MaintenanceIntake.Feedback feedback(String id){return new MaintenanceIntake.Feedback(repo,id,"Missing example","Example is missing","Example contains valid JSON","Open scripts/checks","macos","1.0");}
    String submit(String key){return (String)((Map<?,?>)intake.feedback(admin,feedback(key))).get("intakeId");}
    com.fasterxml.jackson.databind.JsonNode issue(int number,String body)throws Exception{return mapper.readTree(json.write(Map.of("number",number,"title","Missing example","body",body,"state","open","html_url","https://github.com/example/project/issues/"+number,"labels",List.of(),"updated_at",java.time.Instant.now().toString())));}
    DesktopInteractions.State state(String runtime)throws Exception{return new DesktopInteractions.State(runtime,mapper.readTree("{}"),mapper.readTree("[]"),false,true,mapper.readTree("{\"models\":[],\"presets\":[],\"maintenance\":{\"executor\":\"funnywork-native-v1\",\"gitVersion\":\"git-test\",\"triage\":true}}"));}
    void claim()throws Exception {var frames=interactions.exchange(admin,device,state("native-runtime"));assertEquals(1,frames.size());assertEquals("maintenance-triage",frames.get(0).get("kind"));attempt=(String)frames.get(0).get("id");@SuppressWarnings("unchecked")var p=(Map<String,Object>)frames.get(0).get("payload");payload=p;}
    MaintenanceIntake.Analysis ready(List<String> paths){return new MaintenanceIntake.Analysis("READY","Add missing example","Add the example described by the source","The example contains valid JSON","The source specifies observed and expected behavior",paths,null);}
    MaintenanceIntake.Receipt receipt(MaintenanceIntake.Analysis a){return new MaintenanceIntake.Receipt("native-runtime",1,(String)payload.get("specHash"),a,new MaintenanceService.ModelUsage(1,100L,50L,0L));}
    int tasks(){return db.queryForObject("select count(*) from tasks where tenant_id=?",Integer.class,tenant);}
    @Test void clientAndGithubExactSourcesMergeBeforeOneNativeAnalysisAndOneRepair()throws Exception {
        String id=submit("once");assertEquals(id,submit("once"));intake.github(tenant,repo,issue(42,MaintenanceIntake.feedbackBody(feedback("other"))));
        assertEquals(1,db.queryForObject("select count(*) from maintenance_intakes where tenant_id=?",Integer.class,tenant));assertEquals(2,db.queryForObject("select count(*) from maintenance_intake_sources where intake_id=?",Integer.class,id));
        claim();assertEquals(0,tasks());assertEquals(0,control.tickTenant(tenant));assertEquals(attempt,interactions.exchange(admin,device,state("native-runtime")).get(0).get("id"));
        assertEquals(409,assertThrows(ApiException.class,()->intake.renew(admin,device,attempt,new MaintenanceIntake.Renewal("old-runtime",1))).status);
        intake.renew(admin,device,attempt,new MaintenanceIntake.Renewal("native-runtime",1));var r=receipt(ready(List.of("scripts/checks/example.json")));intake.report(admin,device,attempt,r);assertEquals(1,tasks());assertEquals(true,((Map<?,?>)intake.report(admin,device,attempt,r)).get("duplicate"));
        assertEquals("ADMITTED",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));
        var spec=(Map<?,?>)json.read(db.queryForObject("select spec_json from maintenance_specs where tenant_id=?",String.class,tenant));assertEquals(List.of("scripts/checks/example.json"),spec.get("allowedPaths"));
        assertEquals(0,control.tickTenant(tenant));interactions.exchange(admin,device,state("native-runtime"));db.update("update work_units set next_dispatch_at=0 where task_id in (select id from tasks where tenant_id=?)",tenant);assertEquals(1,control.tickTenant(tenant));assertEquals("maintenance",interactions.exchange(admin,device,state("native-runtime")).get(0).get("kind"));
    }
    @Test void incompleteAnalysisNeverCreatesRepair()throws Exception{submit("info");claim();intake.report(admin,device,attempt,receipt(new MaintenanceIntake.Analysis("NEEDS_INFORMATION","Example","","","Need reproducible behavior",List.of(),null)));assertEquals(0,tasks());}
    @Test void generatedPathsOutsideConfiguredScopeAreRejected()throws Exception{submit("scope");claim();assertThrows(ApiException.class,()->intake.report(admin,device,attempt,receipt(ready(List.of("README.md")))));assertEquals(0,tasks());}
    @Test void issueEditIsSavedButCannotRewriteRunningInputOrDispatchAnotherRepair()throws Exception{String id=submit("edit");intake.github(tenant,repo,issue(42,MaintenanceIntake.feedbackBody(feedback("same"))));claim();intake.github(tenant,repo,issue(42,"Changed requirement"));intake.report(admin,device,attempt,receipt(ready(List.of("scripts/checks/example.json"))));assertEquals("SOURCE_CHANGED",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));assertEquals(0,tasks());assertEquals(2,db.queryForObject("select count(*) from maintenance_intake_sources where intake_id=?",Integer.class,id));}
    @Test void ordinaryFeedbackDoesNotNeedOrGainOperatorRole()throws Exception{String token=AuthService.id();auth.create(token,tenant,"USER","Reporter",Set.of("VIEWER"),Set.of("sandbox"),token);var reporter=auth.authenticate(token);String id=(String)((Map<?,?>)intake.feedback(reporter,feedback("viewer"))).get("intakeId");assertNotNull(intake.detail(reporter,id,false));assertThrows(ApiException.class,()->maintenance.create(reporter,"escalate",new MaintenanceService.Problem(repo,"Fix","Fix example","Works","macos","a".repeat(40))));assertThrows(ApiException.class,()->intake.configure(reporter,new MaintenanceIntake.Configuration(repo,"a".repeat(40),List.of("scripts"),true,false,"","macos")));}
    @Test void foreignSourceAndDuplicateTargetCannotCrossTenant()throws Exception {
        String token=AuthService.id();auth.create(token,AuthService.id(),"USER","Other",Set.of("ADMIN","OPERATOR"),Set.of("sandbox"),token);
        var other=auth.authenticate(token);assertThrows(ApiException.class,()->intake.feedback(other,feedback("other")));
        submit("own");claim();var duplicate=new MaintenanceIntake.Analysis("DUPLICATE","","","","Same case",List.of(),AuthService.id());
        assertThrows(ApiException.class,()->intake.report(admin,device,attempt,receipt(duplicate)));assertEquals(0,tasks());
    }
    @Test void requestKeyConflictAndPullRequestsAreNotNewProblems()throws Exception{String id=submit("conflict");var changed=new MaintenanceIntake.Feedback(repo,"conflict","Different","Actual","Expected","Steps","macos","1.0");assertThrows(ApiException.class,()->intake.feedback(admin,changed));var pr=(com.fasterxml.jackson.databind.node.ObjectNode)issue(43,"PR");pr.putObject("pull_request");intake.github(tenant,repo,pr);assertEquals(1,db.queryForObject("select count(*) from maintenance_intakes where tenant_id=?",Integer.class,tenant));assertNotNull(id);}
    @Test void semanticDuplicateLinksOneExistingRepairInsteadOfStartingAnother()throws Exception {
        String target=submit("first");claim();intake.report(admin,device,attempt,receipt(ready(List.of("scripts/checks/example.json"))));
        var another=new MaintenanceIntake.Feedback(repo,"second","Example missing too","Example is missing","Example contains valid JSON","Open scripts/checks","macos","1.0");
        String duplicate=(String)((Map<?,?>)intake.feedback(admin,another)).get("intakeId");claim();
        intake.report(admin,device,attempt,receipt(new MaintenanceIntake.Analysis("DUPLICATE","","","","Same observed behavior and exact expected example",List.of(),target)));
        assertEquals(1,tasks());assertEquals(target,db.queryForObject("select duplicate_of from maintenance_intakes where id=?",String.class,duplicate));
    }
    @Test void disabledBindingCannotRenewAnalysisAndExpiredUnknownWorkRequiresStopConfirmation()throws Exception {
        String id=submit("recover");claim();db.update("update maintenance_desktop_bindings set enabled=false where device_id=?",device);
        assertThrows(ApiException.class,()->intake.renew(admin,device,attempt,new MaintenanceIntake.Renewal("native-runtime",1)));
        assertThrows(ApiException.class,()->intake.recover(admin,id,new MaintenanceIntake.Recovery("Unchecked",false)));
        assertThrows(ApiException.class,()->intake.recover(admin,id,new MaintenanceIntake.Recovery("Still leased",true)));
        db.update("update maintenance_intake_attempts set expires_at=0 where id=?",attempt);
        intake.recover(admin,id,new MaintenanceIntake.Recovery("Original session and process confirmed stopped",true));
        assertEquals("RECEIVED",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));
        assertEquals(2,db.queryForObject("select revision from maintenance_intakes where id=?",Integer.class,id));
        assertThrows(ApiException.class,()->intake.report(admin,device,attempt,receipt(ready(List.of("scripts/checks/example.json")))));
    }
    @Test void caseSensitiveExpectedBehaviorIsNotAnExactDuplicate(){
        submit("case-one");intake.feedback(admin,new MaintenanceIntake.Feedback(repo,"case-two","Missing example","Example is missing","example contains valid JSON","Open scripts/checks","macos","1.0"));
        assertEquals(2,db.queryForObject("select count(*) from maintenance_intakes where tenant_id=?",Integer.class,tenant));
    }
    @SuppressWarnings("unchecked")
    @Test void githubPaginationSkipsPRsMergesSourcesAndDoesNotAdvanceCursorOnReadFailure()throws Exception {
        var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<java.io.InputStream> first=org.mockito.Mockito.mock(java.net.http.HttpResponse.class),second=org.mockito.Mockito.mock(java.net.http.HttpResponse.class),failure=org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        var page=mapper.createArrayNode();for(int n=0;n<49;n++){var pr=(com.fasterxml.jackson.databind.node.ObjectNode)issue(100+n,"PR");pr.putObject("pull_request");page.add(pr);}page.add(issue(42,MaintenanceIntake.feedbackBody(feedback("one"))));
        org.mockito.Mockito.when(first.statusCode()).thenReturn(200);org.mockito.Mockito.when(first.body()).thenReturn(new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(page)));
        org.mockito.Mockito.when(second.statusCode()).thenReturn(200);org.mockito.Mockito.when(second.body()).thenReturn(new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(List.of(issue(43,MaintenanceIntake.feedbackBody(feedback("two")))))));
        org.mockito.Mockito.when(failure.statusCode()).thenReturn(503);org.mockito.Mockito.when(failure.body()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any())).thenReturn(first,second,failure);
        var github=new MaintenanceGithub(db,intake,auth,new org.springframework.mock.env.MockEnvironment(),mapper,client);
        try{assertEquals(2,((Map<?,?>)github.sync(admin,repo)).get("imported"));assertEquals(1,db.queryForObject("select count(*) from maintenance_intakes where tenant_id=?",Integer.class,tenant));
            assertEquals(2,db.queryForObject("select count(*) from maintenance_intake_sources where tenant_id=?",Integer.class,tenant));
            String cursor=db.queryForObject("select github_since from maintenance_intake_configs where repository_id=?",String.class,repo);
            assertNotNull(((Map<?,?>)github.sync(admin,repo)).get("error"));assertEquals(cursor,db.queryForObject("select github_since from maintenance_intake_configs where repository_id=?",String.class,repo));
        }finally{github.close();}
    }
    @Test void HTTPFeedbackUsesOriginalAccountAndRequiresStructuredFields()throws Exception{String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+3600000);http.perform(post("/api/account/sync/feedback").header("Authorization","Bearer "+token).contentType("application/json").content(json.write(feedback("http")))).andExpect(status().isOk()).andExpect(jsonPath("$.intakeId").isString());http.perform(post("/api/account/sync/feedback").header("Authorization","Bearer "+token).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());}
    @Test void identicalSourceWithDifferentSerializedKeyOrderIsNotAnEdit()throws Exception{
        String id=submit("restart-order");var incoming=issue(42,MaintenanceIntake.feedbackBody(feedback("same")));intake.github(tenant,repo,incoming);
        var legacy=new LinkedHashMap<String,Object>();legacy.put("platform","macos");legacy.put("body",MaintenanceIntake.feedbackBody(feedback("same")));legacy.put("title","Missing example");String encoded=mapper.writeValueAsString(legacy);
        db.update("update maintenance_intake_sources set payload_json=?,source_revision=? where intake_id=? and source_kind='GITHUB'",encoded,AuthService.hash(encoded),id);
        intake.github(tenant,repo,incoming);assertEquals("RECEIVED",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));
        assertEquals(2,db.queryForObject("select count(*) from maintenance_intake_sources where intake_id=?",Integer.class,id));
    }


    @Test void malformedGithubIssueIsQuarantinedWithoutBlockingNextSource() throws Exception {
        var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        @SuppressWarnings("unchecked") java.net.http.HttpResponse<java.io.InputStream> response=org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        var malformed=(com.fasterxml.jackson.databind.node.ObjectNode)issue(43,"Bad metadata");malformed.put("updated_at","not-a-timestamp");
        byte[] bytes=mapper.writeValueAsBytes(List.of(issue(41,""),malformed,issue(42,"Valid reproducible issue")));
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body()).thenAnswer(i->new java.io.ByteArrayInputStream(bytes));
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any())).thenReturn(response);
        var github=new MaintenanceGithub(db,intake,auth,new org.springframework.mock.env.MockEnvironment(),mapper,client);
        try {
            for(int round=0;round<2;round++)assertNull(((Map<?,?>)github.sync(admin,repo)).get("error"));
            assertEquals(1,db.queryForObject("select count(*) from maintenance_intakes where repository_id=?",Integer.class,repo));
            assertEquals(2,db.queryForObject("select count(*) from maintenance_source_failures where repository_id=? and resolved=false",Integer.class,repo));
            assertEquals(2,((List<?>)intake.sourceFailures(admin,repo)).size());
            var config=(Map<?,?>)((List<?>)intake.configurations(admin)).get(0);assertEquals(2,((Number)config.get("source_failure_count")).intValue());
        }finally{github.close();}
    }
    @Test void feedbackRetainsVersionAndRejectsConflictingRetry() {
        String id=submit("version");var f=feedback("version");
        assertEquals("1.0",db.queryForObject("select client_version from maintenance_intake_sources where intake_id=?",String.class,id));
        assertEquals(true,((Map<?,?>)intake.feedback(admin,f)).get("reused"));
        assertThrows(ApiException.class,()->intake.feedback(admin,new MaintenanceIntake.Feedback(repo,f.requestId(),f.title(),f.actual(),f.expected(),f.steps(),f.platform(),"2.0")));
    }
    @Test void supplementaryInformationRequeuesSameFeedbackOnce() {
        String id=submit("supplement");db.update("update maintenance_intakes set state='NEEDS_INFORMATION' where id=?",id);
        var note=new MaintenanceIntake.Supplement("note-1","Occurs after restarting Windows");
        assertEquals(false,((Map<?,?>)intake.supplement(admin,id,note)).get("reused"));
        assertEquals(true,((Map<?,?>)intake.supplement(admin,id,note)).get("reused"));
        assertEquals(2,db.queryForObject("select revision from maintenance_intakes where id=?",Integer.class,id));
        assertEquals("RECEIVED",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));
        assertEquals(1,db.queryForObject("select count(*) from maintenance_intakes where repository_id=?",Integer.class,repo));
        assertEquals(1,((List<?>)((Map<?,?>)intake.detail(admin,id,false)).get("messages")).size());
        assertThrows(ApiException.class,()->intake.supplement(admin,id,new MaintenanceIntake.Supplement("note-1","Different")));
        assertThrows(ApiException.class,()->intake.supplement(admin,id,new MaintenanceIntake.Supplement("note-2","Not requested")));
    }
    @Test void scopedFeedbackPagesDoNotLoseVisibleRowsBehindOtherZones() {
        String visible=submit("visible");db.update("update maintenance_intakes set created_at=1 where id=?",visible);
        String other=(String)maintenance.repository(admin,new MaintenanceService.Repository("example/hidden","main","sandbox",List.of(new MaintenanceService.Check("diff",List.of("git","diff","--check"),30)))).get("repositoryId");
        for(int n=0;n<105;n++)db.update("insert into maintenance_intakes(id,tenant_id,repository_id,fingerprint,title,body,platform,state,created_at,updated_at) values(?,?,?,?,?,?,'macos','RECEIVED',?,?)",AuthService.id(),tenant,other,"key-"+n,"Hidden "+n,"Body",1000+n,1000+n);
        db.update("update maintenance_repositories set zone='hidden' where id=?",other);
        var items=(List<?>)intake.list(admin);assertEquals(1,items.size());assertEquals(visible,((Map<?,?>)items.get(0)).get("id"));
        var mine=intake.page(admin,"",1,"","",true);assertEquals(1,((List<?>)mine.get("items")).size());
        assertFalse(((Map<?,?>)((List<?>)mine.get("items")).get(0)).containsKey("zone"));
    }


    @Test void httpFeedbackFollowUpKeepsReporterIsolationAndManagementPermissions() throws Exception {
        String token=AuthService.id();db.update("insert into api_tokens(token_hash,principal_id,expires_at,revoked) values(?,?,?,false)",AuthService.hash(token),admin.id(),System.currentTimeMillis()+3600000);
        String id=submit("http-followup");db.update("update maintenance_intakes set state='NEEDS_INFORMATION' where id=?",id);
        http.perform(get("/api/account/sync/feedback").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
        http.perform(post("/api/account/sync/feedback/"+id+"/supplements").header("Authorization","Bearer "+token).contentType("application/json").content(json.write(new MaintenanceIntake.Supplement("http-note","Exact reproduction")))).andExpect(status().isOk());
        String other=AuthService.id();auth.create(other,tenant,"USER","Other reporter",Set.of("VIEWER"),Set.of("sandbox"),other);var stranger=auth.authenticate(other);
        assertThrows(ApiException.class,()->intake.supplement(stranger,id,new MaintenanceIntake.Supplement("steal","Private")));
        assertTrue(((List<?>)intake.page(stranger,"",10,"","",true).get("items")).isEmpty());
        assertNotNull(ManagementIdentity.permission("GET","/api/v1/maintenance/intakes/page"));
        assertNotNull(ManagementIdentity.permission("GET","/api/v1/maintenance/intake-configs/"+repo+"/source-failures"));
    }


    @Test void pageCursorRemainsUsableWhenItsFeedbackChangesState() {
        for(int n=0;n<3;n++)intake.feedback(admin,new MaintenanceIntake.Feedback(repo,"page-"+n,"Distinct "+n,"Observed","Expected","Steps","macos","1"));
        var page=intake.page(admin,"",1,"","RECEIVED",false);String cursor=(String)page.get("nextCursor");assertFalse(cursor.isEmpty());
        db.update("update maintenance_intakes set state='DECLINED' where id=?",cursor);
        assertEquals(1,((List<?>)intake.page(admin,cursor,1,"","RECEIVED",false).get("items")).size());
    }

    @Test void legacyClientCannotTurnProtectedFeedbackIntoARepair()throws Exception{
        String id=(String)((Map<?,?>)intake.feedback(admin,new MaintenanceIntake.Feedback(repo,"legacy-protected","Change usage","Current behavior","不限token使用","Open app","macos","1"))).get("intakeId");claim();intake.report(admin,device,attempt,receipt(ready(List.of("scripts/checks/example.json"))));assertEquals("NEEDS_INFORMATION",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));assertEquals(0,tasks());
        assertEquals("MAINTENANCE_PROTECTED_PLAN",assertThrows(ApiException.class,()->maintenance.create(admin,"protected-direct",new MaintenanceService.Problem(repo,"Remove backend","删除后台服务文件","Works","macos","a".repeat(40)))).code);
    }

    @Test void originalFeedbackRetryAfterSupplementRemainsIdempotent(){String id=submit("original-retry");db.update("update maintenance_intakes set state='NEEDS_INFORMATION' where id=?",id);intake.supplement(admin,id,new MaintenanceIntake.Supplement("note-one","A reproducible example"));assertEquals(id,submit("original-retry"));assertEquals(1,db.queryForObject("select count(*) from maintenance_feedback_messages where intake_id=?",Integer.class,id));}

    @Test void protectedNoteIsStoredSeparatelyWithoutPoisoningSharedFeedback(){String id=submit("shared-safe");String otherToken=AuthService.id();auth.create(otherToken,tenant,"USER","Other",Set.of("VIEWER"),Set.of("sandbox"),otherToken);var other=auth.authenticate(otherToken);intake.feedback(other,feedback("shared-other"));db.update("update maintenance_intakes set state='NEEDS_INFORMATION' where id=?",id);String before=db.queryForObject("select body from maintenance_intakes where id=?",String.class,id);var note=new MaintenanceIntake.Supplement("held-note","不限token使用");assertEquals(true,((Map<?,?>)intake.supplement(other,id,note)).get("held"));assertEquals(true,((Map<?,?>)intake.supplement(other,id,note)).get("reused"));assertEquals(before,db.queryForObject("select body from maintenance_intakes where id=?",String.class,id));assertEquals("NEEDS_INFORMATION",db.queryForObject("select state from maintenance_intakes where id=?",String.class,id));assertEquals(1,db.queryForObject("select revision from maintenance_intakes where id=?",Integer.class,id));assertEquals("HELD",db.queryForObject("select review_state from maintenance_feedback_messages where intake_id=?",String.class,id));assertEquals(0,((List<?>)((Map<?,?>)intake.detail(admin,id,false)).get("messages")).size());}
    @Test void identicalRenderedBodiesWithDifferentStructuredIntentDoNotMerge(){var first=new MaintenanceIntake.Feedback(repo,"typed-a","Ambiguous rendering","Observed\n\nExpected:\nSafe","Target","Steps","macos","1");var second=new MaintenanceIntake.Feedback(repo,"typed-b","Ambiguous rendering","Observed","Safe\n\nExpected:\nTarget","Steps","macos","1");assertEquals(MaintenanceIntake.feedbackBody(first),MaintenanceIntake.feedbackBody(second));String a=(String)((Map<?,?>)intake.feedback(admin,first)).get("intakeId"),b=(String)((Map<?,?>)intake.feedback(admin,second)).get("intakeId");assertNotEquals(a,b);assertEquals(2,db.queryForObject("select count(*) from maintenance_intakes where tenant_id=?",Integer.class,tenant));}

}
