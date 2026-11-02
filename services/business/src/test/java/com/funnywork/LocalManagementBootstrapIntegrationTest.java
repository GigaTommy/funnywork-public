package com.funnywork;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:local-management-bootstrap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=local-management-bootstrap-012345678901234567890","funnywork.local-management-bootstrap=true","funnywork.scheduling-enabled=false"})
class LocalManagementBootstrapIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired AuthService auth;
 @Test void freshLocalAdminIsBoundOnceAndRevocationIsPreserved(){
  assertEquals("bootstrap-admin",db.queryForObject("select principal_id from management_identity_links where ruoyi_tenant='000000' and ruoyi_user='1'",String.class));
  auth.run(new DefaultApplicationArguments());
  assertEquals(1,db.queryForObject("select count(*) from management_identity_links",Integer.class));
  db.update("update principals set enabled=false where id='bootstrap-admin'");
  auth.run(new DefaultApplicationArguments());
  assertFalse(db.queryForObject("select enabled from principals where id='bootstrap-admin'",Boolean.class));
  db.update("delete from management_identity_links");
  auth.run(new DefaultApplicationArguments());
  assertEquals(0,db.queryForObject("select count(*) from management_identity_links",Integer.class));
 }
}
