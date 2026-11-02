package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real driver coverage: H2 does not expose PostgreSQL's nullable-parameter type inference. */
@EnabledIfEnvironmentVariable(named="FUNNYWORK_TEST_POSTGRES_URL",matches="jdbc:postgresql:.*")
class DesktopAttachmentsPostgresTest {
    SingleConnectionDataSource source;
    JdbcTemplate db;
    DesktopAttachments attachments;
    final Identity owner=new Identity("owner","tenant","USER",Set.of("VIEWER"),Set.of("sandbox"),1);
    @BeforeEach void setup() throws Exception {
        var env=System.getenv();
        source=new SingleConnectionDataSource(DriverManager.getConnection(env.get("FUNNYWORK_TEST_POSTGRES_URL"),
            env.getOrDefault("FUNNYWORK_TEST_POSTGRES_USER","postgres"),env.getOrDefault("FUNNYWORK_TEST_POSTGRES_PASSWORD","")),true);
        db=new JdbcTemplate(source);
        // Temporary tables stay on this owned connection and never modify an existing database schema.
        db.execute("create temporary table sync_sessions(id varchar(64),tenant_id varchar(64),principal_id varchar(64))");
        db.execute("create temporary table sync_attachment_refs(session_id varchar(64),hash varchar(64),metadata_json text)");
        db.execute("create temporary table sync_attachment_objects(tenant_id varchar(64),principal_id varchar(64),hash varchar(64),next_offset bigint,verified boolean)");
        db.update("insert into sync_sessions values('session','tenant','owner')");
        var auth=mock(AuthService.class);when(auth.current(owner)).thenReturn(owner);
        attachments=new DesktopAttachments(db,auth,new ObjectMapper(),33554432);
    }
    @AfterEach void cleanup(){if(source!=null)source.destroy();}
    @Test void nullableHashAndExactFilterUsePostgresParameterTypes(){
        assertTrue(((List<?>)attachments.manifest(owner,"session").get("items")).isEmpty());
        for(int i=0;i<101;i++)db.update("insert into sync_attachment_refs values(?,?,?)","session",String.format("%064x",i),"{}");
        var first=attachments.manifest(owner,"session");
        assertEquals(100,((List<?>)first.get("items")).size());
        var last=attachments.manifest(owner,"session",(String)first.get("after"),null);
        assertEquals(1,((List<?>)last.get("items")).size());assertEquals("",last.get("after"));
        assertEquals(1,((List<?>)attachments.manifest(owner,"session","",String.format("%064x",100)).get("items")).size());
    }
}
