package org.dromara.common.web.config;

import java.net.URI;
import java.util.Set;
import org.springframework.web.cors.CorsConfiguration;

/** Explicit browser origins for header-authenticated management APIs. */
public final class ManagementCors {
    private ManagementCors() { }

    public static CorsConfiguration configuration(String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowCredentials(false);
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        configuration.setMaxAge(1800L);
        for (String candidate : origins.split(",", -1)) {
            String origin = candidate.trim();
            if (origin.isEmpty()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid management CORS origin");
            }
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !uri.getRawPath().isEmpty() || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback)
                || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("Management CORS requires exact HTTPS origins; loopback HTTP is allowed for development");
            }
            configuration.addAllowedOrigin(origin);
        }
        return configuration;
    }
}
