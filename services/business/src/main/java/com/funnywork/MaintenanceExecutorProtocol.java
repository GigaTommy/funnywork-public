package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Explicit, versioned opt-in. Advertising a plugin never enables repository mutation. */
final class MaintenanceExecutorProtocol {
    static final String CODEX="funnywork-codex-v1";
    static final Set<String> CAPABILITIES=Set.of("start","events","cancel","reconcile","artifacts","sync-check");
    private MaintenanceExecutorProtocol() {}
    static void validate(JsonNode executors) {
        if(!executors.isArray()||executors.size()>1)invalid();
        for(var executor:executors) {
            if(!executor.isObject()||executor.size()!=5||!CODEX.equals(executor.path("executor").asText())
                ||!executor.path("protocolVersion").isIntegralNumber()||executor.path("protocolVersion").asInt()!=1
                ||!text(executor.path("executorVersion"))||!text(executor.path("gitVersion"))
                ||!executor.path("capabilities").isArray())invalid();
            var names=new HashSet<String>();
            for(var capability:executor.path("capabilities"))if(!capability.isTextual()||!CAPABILITIES.contains(capability.asText())||!names.add(capability.asText()))invalid();
            if(!names.contains("sync-check"))invalid();
        }
    }
    static boolean available(JsonNode choices) {
        if(choices==null||!choices.has("maintenanceExecutors"))return false;
        try{validate(choices.path("maintenanceExecutors"));}catch(ApiException invalid){return false;}
        return choices.path("maintenanceExecutors").size()==1&&!"unavailable".equals(choices.path("maintenanceExecutors").get(0).path("gitVersion").asText());
    }
    private static boolean text(JsonNode value){return value.isTextual()&&value.asText().matches("[A-Za-z0-9 ._+()/-]{1,120}");}
    private static void invalid(){throw new ApiException(400,"INVALID_MAINTENANCE_EXECUTOR","Unsupported maintenance executor capability");}
}
