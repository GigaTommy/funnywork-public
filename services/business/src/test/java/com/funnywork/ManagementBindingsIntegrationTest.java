package com.funnywork;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:binding-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=bindings-test-bootstrap-012345678901234567890","funnywork.scheduling-enabled=false"})
@Transactional
class ManagementBindingsIntegrationTest {
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ManagementBindingsController bindings;

    @Test void revokedBindingsRemainVisibleWithoutLeakingOtherScopes() {
        Identity admin=auth.authenticate("bindings-test-bootstrap-012345678901234567890");
        auth.create("revoked-user","demo","USER","Revoked",Set.of("VIEWER"),Set.of("sandbox"),auth.newToken());
        auth.create("other-zone","demo","USER","Hidden",Set.of("VIEWER"),Set.of("private"),auth.newToken());
        auth.create("other-tenant","other","USER","Hidden tenant",Set.of("VIEWER"),Set.of("sandbox"),auth.newToken());
        db.update("insert into management_identity_links values('000000','20','revoked-user')");
        db.update("insert into management_identity_links values('000000','21','other-zone')");
        db.update("insert into management_identity_links values('other','22','other-tenant')");
        auth.disable(admin,"revoked-user");
        var rows=bindings.list(admin);
        assertEquals(1,rows.size());
        assertEquals("revoked-user",rows.get(0).get("id"));
        assertEquals(false,rows.get(0).get("enabled"));
    }

    @Test void ordinaryUsersCannotReadTheAuthorizationDirectory() {
        auth.create("viewer","demo","USER","Viewer",Set.of("VIEWER"),Set.of("sandbox"),"viewer-test-token");
        assertThrows(ApiException.class,()->bindings.list(auth.authenticate("viewer-test-token")));
    }
}
