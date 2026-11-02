package org.dromara.web.security;

import cn.dev33.satoken.httpauth.basic.SaHttpBasicUtil;
import cn.hutool.extra.spring.SpringUtil;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.security.config.SecurityConfig;
import org.dromara.common.security.config.properties.SecurityProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ActuatorSecurityTest {
    @Test void monitoringFailuresReturnAnUnauthorizedHttpStatus() {
        try (var properties = mockStatic(SpringUtil.class); var servlet = mockStatic(ServletUtils.class)) {
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            servlet.when(ServletUtils::getResponse).thenReturn(response);
            var filter = new SecurityConfig(new SecurityProperties()).getSaServletFilter();
            filter.error.run(new SecurityException("Actuator credentials are not configured"));
            assertEquals(401, response.getStatus());
        }
    }

    @Test void missingMonitoringCredentialsNeverReachBasicAuthentication() {
        for (String password : new String[]{null, "", "   "}) {
            try (var properties = mockStatic(SpringUtil.class); var basic = mockStatic(SaHttpBasicUtil.class)) {
                properties.when(() -> SpringUtil.getProperty("spring.boot.admin.client.username")).thenReturn("monitor");
                properties.when(() -> SpringUtil.getProperty("spring.boot.admin.client.password")).thenReturn(password);
                var filter = new SecurityConfig(new SecurityProperties()).getSaServletFilter();
                assertThrows(SecurityException.class, () -> filter.auth.run(null));
                basic.verifyNoInteractions();
            }
        }
    }

    @Test void configuredMonitoringCredentialsStillUseBasicAuthentication() {
        try (var properties = mockStatic(SpringUtil.class); var basic = mockStatic(SaHttpBasicUtil.class)) {
            properties.when(() -> SpringUtil.getProperty("spring.boot.admin.client.username")).thenReturn("monitor");
            properties.when(() -> SpringUtil.getProperty("spring.boot.admin.client.password")).thenReturn("synthetic-monitoring-secret");
            var filter = new SecurityConfig(new SecurityProperties()).getSaServletFilter();
            assertDoesNotThrow(() -> filter.auth.run(null));
            basic.verify(() -> SaHttpBasicUtil.check("monitor:synthetic-monitoring-secret"));
        }
    }
}
