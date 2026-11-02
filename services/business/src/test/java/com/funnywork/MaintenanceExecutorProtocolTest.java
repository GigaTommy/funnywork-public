package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceExecutorProtocolTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String descriptor="{\"executor\":\"funnywork-codex-v1\",\"protocolVersion\":1,\"executorVersion\":\"1\",\"gitVersion\":\"git 2.0\",\"capabilities\":[\"sync-check\"]}";
    @Test void acceptsOnlyExplicitKnownProtocolAndCapability() throws Exception {
        MaintenanceExecutorProtocol.validate(mapper.readTree("["+descriptor+"]"));
        assertFalse(MaintenanceExecutorProtocol.available(mapper.readTree("{}")));
        assertFalse(MaintenanceExecutorProtocol.available(mapper.readTree("{\"maintenanceExecutors\":[]}")));
        assertTrue(MaintenanceExecutorProtocol.available(mapper.readTree("{\"maintenanceExecutors\":["+descriptor+"]}")));
        for(String invalid:new String[]{descriptor.replace("funnywork-codex-v1","unknown"),descriptor.replace("\"protocolVersion\":1","\"protocolVersion\":2"),descriptor.replace("sync-check","write-code"),descriptor.replace("\"sync-check\"","\"sync-check\",\"sync-check\""),descriptor.replace("\"capabilities\"","\"unknown\""),descriptor.replace("\"git 2.0\"","\"token\\nsecret\"")}){
            assertThrows(ApiException.class,()->MaintenanceExecutorProtocol.validate(mapper.readTree("["+invalid+"]")));
        }
        assertThrows(ApiException.class,()->MaintenanceExecutorProtocol.validate(mapper.readTree("["+descriptor+","+descriptor+"]")));
    }
}
