package com.funnywork;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MigrationIntegrationTest {
    @Test void upgradesPopulatedV3AndEnforcesExecutionRelationships() {
        String schema="migration_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv().getOrDefault("FUNNYWORK_TEST_DB_URL","jdbc:h2:mem:migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var ds=new DriverManagerDataSource(url,System.getenv().getOrDefault("FUNNYWORK_TEST_DB_USER","sa"),System.getenv().getOrDefault("FUNNYWORK_TEST_DB_PASSWORD",""));
        var db=new JdbcTemplate(ds);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            String prefix=schema+".";
            db.update("insert into "+prefix+"principals(id,tenant_id,kind,display_name,roles,zones,enabled) values('p','t','USER','p','[]','[]',true),('n','t','NODE','n','[]','[]',true)");
            db.update("insert into "+prefix+"nodes values('n','t','sandbox',1,'text.stats.v1','READY',0)");
            db.update("insert into "+prefix+"agent_versions values('a','t','a','1.0.0','text.stats.v1','PUBLISHED','p')");
            db.update("insert into "+prefix+"tasks values('t','t','p',1,'sandbox','a','RUNNING','key','hash',0)");
            db.update("insert into "+prefix+"work_units(id,task_id,ordinal,input_text,state,epoch,attempts) values('u','t',0,'retained input','RUNNING',1,1)");
            db.update("insert into "+prefix+"attempts values('try','u','n',1,'RUNNING',1000,0)");
            db.update("insert into "+prefix+"node_dispatch(id,tenant_id,node_id,attempt_id,state,available_at,created_at) values('d','t','n','try','ACKED',0,0)");
            db.update("insert into "+prefix+"node_inbox values('d','n',0)");
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
            assertEquals("t",db.queryForObject("select tenant_id from "+prefix+"attempts where id='try'",String.class));
            assertEquals("retained input",db.queryForObject("select input_text from "+prefix+"work_units where id='u'",String.class));
            assertEquals(1,db.queryForObject("select count(*) from "+prefix+"node_inbox",Integer.class));
            assertEquals(1,db.queryForObject("select plan_version from "+prefix+"work_units where id='u'",Integer.class));
            assertEquals(1,db.queryForObject("select input_count from "+prefix+"task_plans where task_id='t' and version=1",Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update "+prefix+"work_units set plan_version=2 where id='u'"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update "+prefix+"attempts set tenant_id='foreign' where id='try'"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("update "+prefix+"node_dispatch set node_id='p' where id='d'"));
        } finally {
            db.execute("drop schema if exists "+schema+" cascade");
        }
    }
}
