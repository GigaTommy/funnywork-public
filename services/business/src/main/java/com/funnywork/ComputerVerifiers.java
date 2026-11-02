package com.funnywork;
import org.springframework.stereotype.Service;
import java.util.*;

/** Deployment-owned source lookups, scoped to tenant and business zone. No GUI input. */
@Service
public class ComputerVerifiers {
    public enum Outcome { UNKNOWN, CONFIRMED, REJECTED }
    public record Query(String unitId,String businessKey,String proofHash,String policyJson) {}
    public record Evidence(Outcome outcome,String proofHash,String receipt) {}
    public interface Verifier { Evidence lookup(Query query); }
    public record Binding(String id,Set<String> tenants,Set<String> zones,Verifier verifier) {
        public Binding {
            if(id==null || !id.matches("[a-z0-9][a-z0-9.-]{0,119}") || tenants==null || tenants.isEmpty()
                || zones==null || zones.isEmpty() || verifier==null)throw new IllegalArgumentException("Invalid desktop verifier");
            tenants=Set.copyOf(tenants);zones=Set.copyOf(zones);
        }
    }
    private final Map<String,Binding> bindings;
    @org.springframework.beans.factory.annotation.Autowired
    public ComputerVerifiers(List<Binding> configured,ComputerVerifierConfiguration configuration){
        this(java.util.stream.Stream.concat(configured.stream(),configuration.bindings().stream()).toList());
    }
    public ComputerVerifiers(List<Binding> configured){
        var values=new HashMap<String,Binding>();
        for(var binding:configured)if(values.putIfAbsent(binding.id(),binding)!=null)throw new IllegalArgumentException("Duplicate desktop verifier");
        bindings=Map.copyOf(values);
    }
    public Verifier resolve(String tenant,String id,String zone){
        var binding=bindings.get(id);
        if(binding==null || !binding.tenants().contains(tenant) || !binding.zones().contains(zone))throw ApiException.forbidden();
        return binding.verifier();
    }
}
