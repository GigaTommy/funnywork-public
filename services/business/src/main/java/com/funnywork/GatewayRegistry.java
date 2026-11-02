package com.funnywork;

import org.springframework.stereotype.Service;
import java.util.*;

/** Deployment-owned bindings. No HTTP endpoint can register executable code. */
@Service
public class GatewayRegistry {
    public record Binding(String id, Set<String> tenants, String contractHash, ExternalOperations.Gateway gateway) {
        public Binding {
            if(id==null || !id.matches("[a-z0-9][a-z0-9.-]{0,119}") || tenants==null || tenants.isEmpty()
                || contractHash==null || !contractHash.matches("[a-f0-9]{64}") || gateway==null)
                throw new IllegalArgumentException("Invalid gateway binding");
            tenants=Set.copyOf(tenants);
        }
    }
    private final Map<String,Binding> bindings;
    private final ToolRegistry tools;
    private final ManagedGateways managed;
    @org.springframework.beans.factory.annotation.Autowired
    public GatewayRegistry(ToolRegistry tools,List<Binding> configured,ManagedGateways managed) {
        this.tools=tools;this.managed=managed;
        Map<String,Binding> result=new HashMap<>();
        for(var binding:configured) {
            if(result.putIfAbsent(binding.id(),binding)!=null)throw new IllegalArgumentException("Duplicate gateway identity");
        }
        bindings=Map.copyOf(result);
    }
    public GatewayRegistry(ToolRegistry tools,List<Binding> configured){this(tools,configured,null);}
    public ExternalOperations.Gateway resolve(String tenant,String id,ToolContract contract) {
        return resolve(tenant,id,contract,null);
    }
    public ExternalOperations.Gateway resolve(String tenant,String id,ToolContract contract,String zone) {
        var binding=id==null?null:bindings.get(id);
        if(binding==null && zone!=null && managed!=null)return managed.resolve(tenant,id,contract,tools.fingerprint(contract),zone);
        if(binding==null || !binding.tenants().contains(tenant) || !binding.contractHash().equals(tools.fingerprint(contract)))
            throw new ApiException(403,"GATEWAY_BINDING_DENIED","No matching trusted gateway binding");
        return binding.gateway();
    }
}
