package org.dromara.web.directory;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class DirectorySyncTest {
    JdbcTemplate db;DirectoryRegistry registry;DirectoryAdapter adapter;DirectoryRegistry.Source source;DirectorySync sync;
    @BeforeEach void setup(){
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        db=new JdbcTemplate(data);
        db.execute("create table sys_tenant(tenant_id varchar(20) primary key)");
        db.execute("create table sys_dept(dept_id bigint primary key,tenant_id varchar(20),status varchar(1),del_flag varchar(1))");
        db.execute("create table sys_user(user_id bigint primary key,tenant_id varchar(20),dept_id bigint,user_name varchar(30),nick_name varchar(30),user_type varchar(10),password varchar(100),status varchar(1),del_flag varchar(1),create_by bigint,create_time timestamp,update_by bigint,update_time timestamp)");
        db.execute("create table sys_user_role(user_id bigint,role_id bigint)");
        var migration=Path.of("../../../scripts/setup/ruoyi-identity-directory.sql").toAbsolutePath().normalize();
        try {
            // PostgreSQL partial indexes are verified separately against PostgreSQL; H2 covers service transactions.
            String schema=java.nio.file.Files.readString(migration).replace("CREATE UNIQUE INDEX IF NOT EXISTS fw_sys_user_active_name_idx ON sys_user(tenant_id,user_name) WHERE del_flag='0';","");
            new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(data);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
        db.update("insert into sys_tenant values('000000')");
        db.update("insert into sys_dept values(103,'000000','0','0')");
        registry=mock(DirectoryRegistry.class);adapter=mock(DirectoryAdapter.class);
        source=new DirectoryRegistry.Source("corporate","000000","https://id.example/auth","https://id.example/users","FUNNYWORK_DIRECTORY_KEY",Map.of("engineering",103L));
        when(registry.source("000000","corporate")).thenReturn(source);when(registry.adapter(source)).thenReturn(adapter);
        sync=new DirectorySync(db,registry,new DataSourceTransactionManager(data));
    }
    DirectoryAdapter.User user(String id,String username,long version,boolean active){return new DirectoryAdapter.User(id,username,"Test user","engineering",version,active);}
    void page(DirectoryAdapter.User... users){when(adapter.users(anyString(),anyString())).thenReturn(new DirectoryAdapter.Page("snapshot","","checkpoint",List.of(users)));}
    Map<String,Object> run(){return sync.sync("000000","corporate",1,true);}
    long id(){return db.queryForObject("select user_id from fw_directory_users where external_id='stable-1'",Long.class);}
    @Test void repeatAndRenameKeepIdentityAndLocalAuthorization(){
        page(user("stable-1","alice",1,true));assertEquals(1,run().get("added"));long original=id();
        db.update("insert into sys_user_role values(?,7)",original);
        assertEquals(1,run().get("skipped"));
        page(user("stable-1","renamed",2,true));assertEquals(1,run().get("updated"));
        assertEquals(original,id());assertEquals("renamed",db.queryForObject("select user_name from sys_user where user_id=?",String.class,original));
        assertEquals(7,db.queryForObject("select role_id from sys_user_role where user_id=?",Integer.class,original));
        assertEquals("!external",db.queryForObject("select password from sys_user where user_id=?",String.class,original));
    }
    @Test void collisionRollsBackEveryUserAndCheckpoint(){
        db.update("insert into sys_user(user_id,tenant_id,user_name,status,del_flag) values(1,'000000','admin','0','0')");
        page(user("stable-1","new",1,true),user("stable-2","admin",1,true));
        assertThrows(ServiceException.class,this::run);
        assertEquals(0,db.queryForObject("select count(*) from fw_directory_users",Integer.class));
        assertEquals("",db.queryForObject("select checkpoint from fw_directory_sources",String.class));
        assertEquals("FAILED",db.queryForObject("select state from fw_directory_runs",String.class));
    }
    @Test void failureAfterFirstPageDoesNotImportOrDisableAnyone(){
        page(user("stable-1","alice",1,true));run();
        when(adapter.users("","" )).thenReturn(new DirectoryAdapter.Page("snapshot","page-2","next",List.of(user("new","bob",1,true))));
        when(adapter.users("","page-2")).thenThrow(new IllegalStateException("upstream secret must not escape"));
        var error=assertThrows(ServiceException.class,this::run);
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(1,db.queryForObject("select count(*) from sys_user",Integer.class));
        assertEquals("0",db.queryForObject("select status from sys_user",String.class));
        assertEquals("checkpoint",db.queryForObject("select checkpoint from fw_directory_sources",String.class));
    }
    @Test void explicitDisablePreservesHistoryAndDoesNotUndoManualDisable(){
        page(user("stable-1","alice",1,true));run();long original=id();
        page(user("stable-1","alice",2,false));run();
        assertEquals("1",db.queryForObject("select status from sys_user",String.class));
        page(user("stable-1","alice",3,true));run();
        assertEquals(original,id());assertEquals("1",db.queryForObject("select status from sys_user",String.class));
    }
    @Test void emptyFullSnapshotIsNotMassDeletion(){
        page(user("stable-1","alice",1,true));run();page();assertThrows(ServiceException.class,this::run);
        assertEquals("0",db.queryForObject("select status from sys_user",String.class));
    }
    @Test void oldVersionCannotOverwriteNewAndEqualVersionCannotChangeContent(){
        page(user("stable-1","alice",2,true));run();
        page(user("stable-1","old",1,false));assertEquals(1,run().get("skipped"));
        page(user("stable-1","different",2,true));assertThrows(ServiceException.class,this::run);
        assertEquals("alice",db.queryForObject("select user_name from sys_user",String.class));
    }
    @Test void departmentMustBelongToTenant(){
        db.update("update sys_dept set tenant_id='other'");page(user("stable-1","alice",1,true));
        assertThrows(ServiceException.class,this::run);assertEquals(0,db.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void cursorLoopAndChangedSnapshotAreRejected(){
        when(adapter.users("","")).thenReturn(new DirectoryAdapter.Page("snapshot","loop","next",List.of()));
        when(adapter.users("","loop")).thenReturn(new DirectoryAdapter.Page("snapshot","loop","next",List.of()));
        assertThrows(ServiceException.class,this::run);
        when(adapter.users("","loop")).thenReturn(new DirectoryAdapter.Page("changed","","next",List.of()));
        assertThrows(ServiceException.class,this::run);
    }
    @Test void incrementalUsesLastSuccessfulCheckpoint(){
        page(user("stable-1","alice",1,true));run();clearInvocations(adapter);
        page();sync.sync("000000","corporate",1,false);
        verify(adapter).users("checkpoint","");assertEquals(1,db.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void departmentalAdminsCannotTriggerOrReadTenantWideDirectory(){
        var access=mock(DirectoryAccess.class);
        doThrow(new ServiceException("Forbidden",403)).when(access).require(any(),anyLong(),anyBoolean());
        var controller=new DirectoryController(registry,sync,mock(DirectoryJobs.class),access,mock(DirectoryPreviews.class));
        try(var login=mockStatic(org.dromara.common.satoken.utils.LoginHelper.class)){
            login.when(org.dromara.common.satoken.utils.LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(org.dromara.common.satoken.utils.LoginHelper::getUserId).thenReturn(42L);
            assertThrows(ServiceException.class,controller::sources);
            assertThrows(ServiceException.class,()->controller.synchronize("corporate",true));
            assertThrows(ServiceException.class,()->controller.runs("corporate"));
        }
        assertEquals(0,db.queryForObject("select count(*) from fw_directory_runs",Integer.class));
    }
    @Test void concurrentSnapshotCannotOverwriteCommittedCheckpoint(){
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(adapter.users(anyString(),anyString())).thenAnswer(call->{
            if(first.getAndSet(false))run();
            return new DirectoryAdapter.Page("snapshot","","checkpoint",List.of(user("stable-1","alice",1,true)));
        });
        assertThrows(ServiceException.class,this::run);
        assertEquals(1,db.queryForObject("select count(*) from sys_user",Integer.class));
        assertEquals(1,db.queryForObject("select generation from fw_directory_sources",Integer.class));
    }
    @Test void authenticationRequiresStableSubjectAndLiveVersion(){
        page(user("stable-1","alice",1,true));run();
        var accounts=new DirectoryAccounts(db,registry);
        var user=new org.dromara.system.domain.vo.SysUserVo();user.setUserId(id());user.setDeptId(103L);user.setTenantId("000000");user.setUserName("alice");user.setExternalSource("corporate");
        assertThrows(ServiceException.class,()->accounts.requireVerified(user));
        when(adapter.authenticate("alice","password")).thenReturn("wrong-subject");assertFalse(accounts.password(user,"password"));
        when(adapter.authenticate("alice","password")).thenReturn("stable-1");assertTrue(accounts.password(user,"password"));accounts.requireVerified(user);
        page(user("stable-1","renamed",2,true));run();
        assertThrows(ServiceException.class,()->accounts.requireVerified(user));
        assertThrows(ServiceException.class,()->accounts.requireActive("000000",id(),"corporate",1L));
        accounts.clear();assertNull(accounts.verifiedVersion());
    }
}
