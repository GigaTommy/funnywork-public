package org.dromara.common.web.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ManagementCorsTest {
    @Test void allowedOriginCanPreflightHeaderAuthenticationWithoutCookies() throws Exception {
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", ManagementCors.configuration("https://admin.example"));
        var request = new MockHttpServletRequest("OPTIONS", "/system/user/list");
        request.addHeader("Origin", "https://admin.example");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "Authorization, clientid, Content-Type");
        var response = new MockHttpServletResponse();
        new CorsFilter(source).doFilter(request, response, (req, res) -> fail("Preflight must not call the controller"));
        assertEquals(200, response.getStatus());
        assertEquals("https://admin.example", response.getHeader("Access-Control-Allow-Origin"));
        assertNull(response.getHeader("Access-Control-Allow-Credentials"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").toLowerCase().contains("authorization"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").toLowerCase().contains("clientid"));
    }

    @Test void defaultRejectsCrossOriginButKeepsSameOrigin() throws Exception {
        var config = ManagementCors.configuration("");
        assertNull(config.checkOrigin("https://untrusted.example"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        var filter = new CorsFilter(source);
        var request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader("Origin", "https://untrusted.example");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals(403, response.getStatus());
        assertFalse(reached.get());
        request = new MockHttpServletRequest("GET", "/system/user/list");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertTrue(reached.get());
    }

    @Test void onlyExactOriginsMatchWithoutBrowserCredentials() {
        var config = ManagementCors.configuration("https://admin.example, http://localhost:5173, https://admin.example:8443");
        assertEquals("https://admin.example", config.checkOrigin("https://admin.example"));
        assertEquals("http://localhost:5173", config.checkOrigin("http://localhost:5173"));
        assertEquals("https://admin.example:8443", config.checkOrigin("https://admin.example:8443"));
        for (String origin : new String[]{"https://admin.example.attacker.test", "http://admin.example", "https://admin.example:9443", "null"}) {
            assertNull(config.checkOrigin(origin));
        }
        assertFalse(config.getAllowCredentials());
    }

    @Test void invalidPoliciesFailAtStartup() {
        for (String origin : new String[]{"*", "https://*.example", "https://admin.example/path", "https://user:pass@admin.example", "https://admin.example?x=1", "https://admin.example#x", "https://admin.example:99999", "https://admin.example:", "http://admin.example", "null"}) {
            assertThrows(IllegalArgumentException.class, () -> ManagementCors.configuration(origin), origin);
        }
    }
}
