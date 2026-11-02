package com.funnywork;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:recoveryisolation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
    "funnywork.bootstrap-token=", "funnywork.scheduling-enabled=true", "funnywork.recovery-isolation=true"})
@AutoConfigureMockMvc
class RecoveryIsolationTest {
    @Autowired MockMvc http;
    @Autowired Scheduler scheduler;
    @SpyBean ControlService control;
    @SpyBean AuthService auth;

    @Test void rejectsReadsWritesEnrollmentAndHealthBeforeAuthentication() throws Exception {
        for(String path:new String[]{"/api/v1/me","/api/v1/tasks/example","/api/v1/nodes/messages","/actuator/health"}) {
            http.perform(get(path).header("Authorization","Bearer stale-restored-credential"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOVERY_ISOLATION"))
                .andExpect(header().string("Cache-Control","no-store"));
        }
        for(String path:new String[]{"/api/v1/tasks","/api/v1/nodes/heartbeat","/api/v1/node-enrollments/redeem","/api/v1/attempts/example/result"}) {
            http.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOVERY_ISOLATION"));
        }
        verify(auth,never()).authenticate(anyString());
    }

    @Test void schedulingFlagCannotOverrideRecoveryIsolation() {
        clearInvocations(control);
        scheduler.changed(new TenantWorkChanged("demo"));
        scheduler.tick();
        verify(control,never()).tick();
        verify(control,never()).tickTenant(anyString());
    }
}
