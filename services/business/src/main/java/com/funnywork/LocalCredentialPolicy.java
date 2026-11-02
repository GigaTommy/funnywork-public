package com.funnywork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Deployment-owned policy. No administrator or USER role bypass. */
@Component
public class LocalCredentialPolicy {
    private final Set<String> enterpriseOnly;
    public LocalCredentialPolicy(@Value("${funnywork.enterprise-only-tenants:}") String value) {
        Set<String> tenants=new HashSet<>();
        if(!value.isBlank())for(String part:value.split(",",-1)) {
            String tenant=part.trim();
            if(!tenant.matches("[a-zA-Z0-9_-]{1,64}") || !tenants.add(tenant))
                throw new IllegalArgumentException("Invalid enterprise-only tenant configuration");
        }
        enterpriseOnly=Set.copyOf(tenants);
    }
    public Set<String> tenants(){return enterpriseOnly;}
    public boolean permits(String tenant,String kind){return !"USER".equals(kind) || !enterpriseOnly.contains(tenant);}
}
