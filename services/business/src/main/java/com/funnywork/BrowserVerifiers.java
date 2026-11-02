package com.funnywork;

import org.springframework.stereotype.Service;
import java.util.*;

/** Deployment-owned read-only verification; intentionally has no dispatch method. */
@Service
public class BrowserVerifiers {
    public enum Outcome { UNKNOWN, CONFIRMED, REJECTED }
    public record Query(String unitId,String businessKey,String proofHash,String policyJson) {}
    public record Evidence(Outcome outcome,String proofHash,String receipt) {}
    public interface Verifier { Evidence lookup(Query query); }
    public record Binding(String id,Set<String> tenants,String origin,Verifier verifier) {
        public Binding {
            if(id==null || !id.matches("[a-z0-9][a-z0-9.-]{0,119}") || tenants==null || tenants.isEmpty() || verifier==null)throw new IllegalArgumentException("Invalid browser verifier");
            tenants=Set.copyOf(tenants);origin=BrowserExecution.origin(origin);
        }
    }
    private final Map<String,Binding> bindings;
    public BrowserVerifiers(List<Binding> configured) {
        var values=new HashMap<String,Binding>();
        for(var binding:configured)if(values.putIfAbsent(binding.id(),binding)!=null)throw new IllegalArgumentException("Duplicate browser verifier");
        bindings=Map.copyOf(values);
    }
    public Verifier resolve(String tenant,String id,String origin) {
        var binding=bindings.get(id);
        if(binding==null || !binding.tenants().contains(tenant) || !binding.origin().equals(origin))throw ApiException.forbidden();
        return binding.verifier();
    }
}
