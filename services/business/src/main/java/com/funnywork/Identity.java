package com.funnywork;

import java.util.Set;

public record Identity(String id, String tenant, String kind, Set<String> roles, Set<String> zones, long authVersion) {
    public void requireUser(String role) {
        if (!kind.equals("USER") || !roles.contains(role)) throw ApiException.forbidden();
    }
    public void requireZone(String zone) { if (!zones.contains(zone)) throw ApiException.forbidden(); }
    public void requireNode() { if (!kind.equals("NODE")) throw ApiException.forbidden(); }
}
