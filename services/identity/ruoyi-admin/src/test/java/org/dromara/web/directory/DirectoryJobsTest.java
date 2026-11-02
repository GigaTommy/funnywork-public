package org.dromara.web.directory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev") @Tag("prod") @Tag("local")
class DirectoryJobsTest {
    JdbcTemplate db;DirectoryRegistry registry;DirectoryAdapter adapter;DirectoryRegistry.Source source;DirectorySync sync;
    JdbcTemplate postgresAdmin;String postgresSchema;
    @BeforeEach void setup() throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        String postgres=System.getenv("FUNNYWORK_DIRECTORY_TEST_JDBC_URL");
        if(postgres!=null&&!postgres.isBlank()){
            String user=System.getenv().getOrDefault("FUNNYWORK_DIRECTORY_TEST_USER",System.getProperty("user.name"));
            String password=System.getenv().getOrDefault("FUNNYWORK_DIRECTORY_TEST_PASSWORD","");
            postgresAdmin=new JdbcTemplate(new DriverManagerDataSource(postgres,user,password));
            postgresSchema="fw_directory_test_"+UUID.randomUUID().toString().replace("-","");
            postgresAdmin.execute("create schema "+postgresSchema);
            data=new DriverManagerDataSource(postgres+(postgres.contains("?")?"&":"?")+"currentSchema="+postgresSchema,user,password);
        }
        db=new JdbcTemplate(data);
        db.execute("create table sys_tenant(tenant_id varchar(20) primary key)");
        db.execute("create table sys_dept(dept_id bigint primary key,tenant_id varchar(20),status varchar(1),del_flag varchar(1))");
        db.execute("create table sys_user(user_id bigint primary key,tenant_id varchar(20),dept_id bigint,user_name varchar(30),nick_name varchar(30),user_type varchar(10),password varchar(100),status varchar(1),del_flag varchar(1),create_by bigint,create_time timestamp,update_by bigint,update_time timestamp)");
        db.execute("create table sys_user_role(user_id bigint,role_id bigint)");
        var migration=Path.of("../../../scripts/setup/ruoyi-identity-directory.sql").toAbsolutePath().normalize();
        try {
            // PostgreSQL partial indexes are verified separately against PostgreSQL; H2 covers service transactions.
            String schema=java.nio.file.Files.readString(migration);
            if(postgresAdmin==null)schema=schema.replace("CREATE UNIQUE INDEX IF NOT EXISTS fw_sys_user_active_name_idx ON sys_user(tenant_id,user_name) WHERE del_flag='0';","");
            new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(data);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
        db.update("insert into sys_tenant values('000000')");
        db.update("insert into sys_dept values(103,'000000','0','0')");
        registry=mock(DirectoryRegistry.class);adapter=mock(DirectoryAdapter.class);
        source=new DirectoryRegistry.Source("corporate","000000","https://id.example/auth","https://id.example/users","FUNNYWORK_DIRECTORY_KEY",Map.of("engineering",103L));
        when(registry.source("000000","corporate")).thenReturn(source);when(registry.adapter(source)).thenReturn(adapter);
        sync=new DirectorySync(db,registry,new DataSourceTransactionManager(data));
        queueSetup();
    }
    DirectoryAdapter.User user(String id,String username,long version,boolean active){return new DirectoryAdapter.User(id,username,"Test user","engineering",version,active);}
    void page(DirectoryAdapter.User... users){when(adapter.users(anyString(),anyString())).thenReturn(new DirectoryAdapter.Page("snapshot","","checkpoint",List.of(users)));}
    Map<String,Object> run(){return sync.sync("000000","corporate",1,true);}
    long id(){return db.queryForObject("select user_id from fw_directory_users where external_id='stable-1'",Long.class);}

    DirectoryJobs jobs;DirectoryAccess access;DirectoryPreviews previews;
    void queueSetup() throws Exception {
        String schema=java.nio.file.Files.readString(Path.of("../../../scripts/setup/ruoyi-directory-jobs.sql")).split("INSERT INTO sys_menu")[0];
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());
        new ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("../../../scripts/setup/ruoyi-directory-previews.sql")).execute(db.getDataSource());
        new ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("../../../scripts/setup/ruoyi-directory-audit.sql")).execute(db.getDataSource());
        access=mock(DirectoryAccess.class);when(access.allowed(anyString(),anyLong(),anyBoolean())).thenReturn(true);
        previews=new DirectoryPreviews(db,registry,sync,access,new DataSourceTransactionManager(db.getDataSource()));
        jobs=new DirectoryJobs(db,registry,sync,access,previews,new DataSourceTransactionManager(db.getDataSource()));
    }
    @AfterEach void close(){if(jobs!=null)jobs.stop();if(postgresAdmin!=null&&postgresSchema!=null)postgresAdmin.execute("drop schema "+postgresSchema+" cascade");}
    @Test void repeatedSubmissionReturnsActiveJobAndWorkerCommitsOnce(){
        page(user("stable-1","alice",1,true));
        var first=jobs.submit("000000","corporate",1,true);
        assertEquals(first.get("id"),jobs.submit("000000","corporate",1,true).get("id"));
        jobs.tick();jobs.tick();
        assertEquals("SUCCEEDED",jobs.list("000000","corporate").get(0).get("state"));
        assertEquals(1,db.queryForObject("select count(*) from sys_user",Integer.class));
        verify(adapter,times(1)).users(anyString(),anyString());
    }
    @Test void crashedWorkerRecoversCommittedRunWithoutRepeatingImport(){
        page(user("stable-1","alice",1,true));jobs.submit("000000","corporate",1,true);jobs.tick();
        db.update("update fw_directory_jobs set state='RUNNING',updated_at=0");clearInvocations(adapter);
        jobs.tick();
        assertEquals("SUCCEEDED",jobs.list("000000","corporate").get(0).get("state"));
        verifyNoInteractions(adapter);
    }
    @Test void invalidSnapshotFailsWithoutAutomaticRetry(){
        page(user("stable-1","alice",1,true),user("stable-2","alice",1,true));
        jobs.submit("000000","corporate",1,true);jobs.tick();
        var row=jobs.list("000000","corporate").get(0);
        assertEquals("FAILED",row.get("state"));assertEquals("SYNC_DUPLICATE_USER",row.get("error_code"));
        assertEquals(0,db.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void transientFailureRetriesWithBoundedAttempts(){
        when(adapter.users(anyString(),anyString())).thenThrow(new IllegalStateException("private upstream error"));
        jobs.submit("000000","corporate",1,true);
        for(int i=0;i<3;i++){db.update("update fw_directory_jobs set due_at=0");jobs.tick();}
        var row=jobs.list("000000","corporate").get(0);
        assertEquals("FAILED",row.get("state"));assertEquals(3,((Number)row.get("attempts")).intValue());
        assertEquals("SYNC_FAILED",row.get("error_code"));
    }
    @Test void revokedOperatorCannotExecuteQueuedWork(){
        jobs.submit("000000","corporate",1,true);
        doThrow(new ServiceException("revoked",403)).when(access).require("000000",1,true);
        jobs.tick();assertEquals("FAILED",jobs.list("000000","corporate").get(0).get("state"));verifyNoInteractions(adapter);
    }
    @Test void scheduleRunsIncrementallyAndStopsWhenAccessIsRevoked(){
        page(user("stable-1","alice",1,true));jobs.schedule("000000","corporate",1,60,true);
        db.update("update fw_directory_schedules set next_at=0");jobs.tick();
        assertEquals(false,jobs.list("000000","corporate").get(0).get("full_sync"));
        when(access.allowed("000000",1,true)).thenReturn(false);
        db.update("update fw_directory_schedules set next_at=0");jobs.tick();
        assertEquals(false,jobs.schedule("000000","corporate").get("enabled"));
    }

    @Test void directoryGrantRequiresEnabledAccountAndTenantWideRole(){
        db.execute("create table sys_role(role_id bigint,tenant_id varchar(20),status varchar(1),del_flag varchar(1),data_scope varchar(1))");
        db.execute("create table sys_role_menu(role_id bigint,menu_id bigint)");
        db.execute("create table sys_menu(menu_id bigint,status varchar(1),perms varchar(100))");
        db.update("insert into sys_user(user_id,tenant_id,user_name,status,del_flag) values(42,'000000','operator','0','0')");
        db.update("insert into sys_user_role values(42,73)");
        db.update("insert into sys_role values(73,'000000','0','0','2')");
        db.update("insert into sys_role_menu values(73,8020)");
        db.update("insert into sys_menu values(8020,'0','funnyworker:directory:read')");
        var actual=new DirectoryAccess(db);
        assertFalse(actual.allowed("000000",42,false));
        db.update("update sys_role set data_scope='1'");
        assertTrue(actual.allowed("000000",42,false));assertFalse(actual.allowed("000000",42,true));
        assertFalse(actual.allowed("other",42,false));
        db.update("update sys_user set status='1' where user_id=42");assertFalse(actual.allowed("000000",42,false));
    }

    String preview(){jobs.submitPreview("000000","corporate",1,true);jobs.tick();return (String)jobs.list("000000","corporate").get(0).get("run_id");}
    @Test void previewWritesNoAccountsAndApprovalUsesExactlyStoredSnapshot(){
        page(user("stable-1","alice",1,true));String id=preview();
        assertEquals(0,db.queryForObject("select count(*) from sys_user",Integer.class));
        assertEquals(1,previews.get("000000","corporate",1,id,0,50).get("added"));
        page(user("different","changed-remotely",2,true));clearInvocations(adapter);
        previews.approve("000000","corporate",1,id);
        assertEquals("alice",db.queryForObject("select user_name from sys_user",String.class));verifyNoInteractions(adapter);
        assertEquals(true,previews.approve("000000","corporate",1,id).get("alreadyApplied"));
        assertEquals(1,db.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void previewExplainsLocalNameConflictAndCannotBeApplied(){
        db.update("insert into sys_user(user_id,tenant_id,user_name,status,del_flag) values(42,'000000','alice','0','0')");
        page(user("stable-1","alice",1,true));String id=preview();
        var report=previews.get("000000","corporate",1,id,0,50);
        assertEquals(1,report.get("conflicts"));assertTrue(report.toString().contains("SYNC_USERNAME_CONFLICT"));
        assertThrows(ServiceException.class,()->previews.approve("000000","corporate",1,id));
        assertEquals(0,db.queryForObject("select count(*) from fw_directory_users",Integer.class));
    }
    @Test void localChangesAfterPreviewRequireAnotherPreview(){
        page(user("stable-1","alice",1,true));run();
        page(user("stable-1","alice",2,false));String id=preview();
        assertEquals(1,previews.get("000000","corporate",1,id,0,50).get("disabled"));
        db.update("update sys_user set nick_name='manual change'");
        assertThrows(ServiceException.class,()->previews.approve("000000","corporate",1,id));
        assertEquals("0",db.queryForObject("select status from sys_user",String.class));
    }
    @Test void interveningSyncInvalidatesPreviewEvenWhenProfilesAreUnchanged(){
        page(user("stable-1","alice",1,true));run();String id=preview();run();
        assertThrows(ServiceException.class,()->previews.approve("000000","corporate",1,id));
    }
    @Test void expirationPurgesProfilesAndPreservesSummary(){
        page(user("stable-1","alice",1,true));String id=preview();db.update("update fw_directory_previews set expires_at=0");
        previews.expire();var report=previews.get("000000","corporate",1,id,0,50);
        assertEquals("EXPIRED",report.get("state"));assertEquals(1,report.get("added"));assertEquals(true,report.get("detailsPurged"));
        assertFalse(report.toString().contains("alice"));assertEquals("",db.queryForObject("select payload from fw_directory_previews",String.class));
        assertThrows(ServiceException.class,()->previews.approve("000000","corporate",1,id));
    }
    @Test void previewIsScopedAndReadPermissionCannotApprove(){
        page(user("stable-1","alice",1,true));String id=preview();
        assertThrows(ServiceException.class,()->previews.get("other","corporate",1,id,0,50));
        doThrow(new ServiceException("denied",403)).when(access).require("000000",42,true);
        assertThrows(ServiceException.class,()->previews.approve("000000","corporate",42,id));
        assertEquals(0,db.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void auditShowsJobsAndPreviewConfirmationWithoutSnapshotData(){
        page(user("stable-1","alice",1,true));
        var submitted=jobs.submitPreview("000000","corporate",1,true);jobs.tick();
        var job=jobs.list("000000","corporate").get(0);
        String runId=String.valueOf(job.get("run_id"));
        previews.approve("000000","corporate",1,runId);
        var audit=new DirectoryAudit(db,access);
        var result=audit.page("000000",1,System.currentTimeMillis()-60000,System.currentTimeMillis()+1000,"corporate",runId,"",50);
        // Use a bounded recent window, including both job and run records.
        assertEquals(2,((List<?>)result.get("items")).size());
        String publicJson=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result).toString();
        assertFalse(publicJson.contains("alice"));assertFalse(publicJson.contains("stable-1"));
        assertFalse(publicJson.contains("payload"));assertFalse(publicJson.contains("checkpoint"));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        var run=items.stream().filter(r->"RUN".equals(r.get("kind"))).findFirst().orElseThrow();
        assertEquals("APPLIED",run.get("preview_state"));assertEquals("1",run.get("approved_by"));
        assertNotNull(run.get("applied_at"));
        assertEquals(submitted.get("id"),items.stream().filter(r->"JOB".equals(r.get("kind"))).findFirst().orElseThrow().get("id"));
    }
    @Test void auditKeysetKeepsSameTimeRecordsAndIsolatesTenants(){
        long now=System.currentTimeMillis();
        for(String id:List.of("a","b","c"))db.update("insert into fw_directory_runs(id,tenant_id,source_id,actor_id,mode,state,generation,created_at) values(?,'000000','corporate',1,'FULL','FAILED',1,?)",id,now);
        db.update("insert into fw_directory_runs(id,tenant_id,source_id,actor_id,mode,state,generation,created_at) values('foreign','other','corporate',1,'FULL','FAILED',1,?)",now);
        var audit=new DirectoryAudit(db,access);List<String> ids=new ArrayList<>();String cursor="";
        do {var result=audit.page("000000",1,now-1000,now+1000,"","",cursor,1);
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("items");
            rows.forEach(r->ids.add(String.valueOf(r.get("id"))));cursor=String.valueOf(result.get("nextCursor"));
        }while(!cursor.isEmpty());
        assertEquals(List.of("c","b","a"),ids);
        assertTrue(((List<?>)audit.page("000000",1,now-1000,now+1000,"missing","","",10).get("items")).isEmpty());
        assertThrows(ServiceException.class,()->audit.page("000000",1,now-1000,now+1000,"","","invalid",10));
        assertThrows(ServiceException.class,()->audit.page("000000",1,0,94L*86400000,"","","",10));
    }
    @Test void auditRequiresDirectoryAuthorityBeforeReadingDatabase(){
        var denied=mock(DirectoryAccess.class);var untouched=mock(JdbcTemplate.class);
        doThrow(new ServiceException("denied",403)).when(denied).require("000000",9,false);
        assertThrows(ServiceException.class,()->new DirectoryAudit(untouched,denied).page("000000",9,1,2,"","","",10));
        verifyNoInteractions(untouched);
    }
}
