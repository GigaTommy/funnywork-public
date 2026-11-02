package com.funnywork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputerVerifierConfigurationTest {
    @TempDir Path directory;
    String valid="""
        {"sources":[{"id":"crm.result.v1","tenants":["tenant-a"],"zones":["sandbox"],
        "baseUrl":"https://crm.internal/","credentialEnv":"FUNNYWORK_SOURCE_CRM","timeoutMs":1000}]}
        """;
    Path file(String text) throws Exception {Path path=directory.resolve("sources.json");Files.writeString(path,text);return path;}
    @Test void fixedConfigurationLoadsWithoutNetworkAndResolvesOnlyAuthorizedScope() throws Exception {
        var bindings=ComputerVerifierConfiguration.load(file(valid).toString(),name->name.equals("FUNNYWORK_SOURCE_CRM")?"fixture-token":null);
        var registry=new ComputerVerifiers(bindings);
        assertNotNull(registry.resolve("tenant-a","crm.result.v1","sandbox"));
        assertThrows(ApiException.class,()->registry.resolve("tenant-b","crm.result.v1","sandbox"));
        assertThrows(ApiException.class,()->registry.resolve("tenant-a","crm.result.v1","other"));
        assertTrue(ComputerVerifierConfiguration.load("",name->null).isEmpty());
    }
    @Test void malformedTrustDocumentsAndInvalidEndpointsCannotStart() throws Exception {
        for(String invalid:List.of(valid+" {}",valid.replace("\"sources\":", "\"extra\":true,\"sources\":"),
                valid.replace("\"timeoutMs\":1000","\"timeoutMs\":1000,\"timeoutMs\":1000"),
                valid.replace("https://crm.internal/","http://crm.internal/"),
                valid.replace("FUNNYWORK_SOURCE_CRM","FUNNYWORK_DB_PASSWORD"),
                valid.replace("[\"sandbox\"]","[\"sandbox\",\"sandbox\"]"),
                valid.replace("1000","0"),"x".repeat(65537))){
            String path=file(invalid).toString();
            var error=assertThrows(IllegalArgumentException.class,()->ComputerVerifierConfiguration.load(path,name->"fixture-secret"));
            assertEquals("Invalid desktop verifier deployment configuration",error.getMessage());assertNull(error.getCause());
        }
        assertThrows(IllegalArgumentException.class,()->ComputerVerifierConfiguration.load("relative.json",name->"token"));
    }
    @Test void missingSecretFailsStartupAndLostCredentialDoesNotProduceEvidence() throws Exception {
        String path=file(valid).toString();
        assertThrows(IllegalArgumentException.class,()->ComputerVerifierConfiguration.load(path,name->null));
        var secret=new java.util.concurrent.atomic.AtomicReference<>("fixture-token");
        var bindings=ComputerVerifierConfiguration.load(path,name->secret.get());secret.set(null);
        assertEquals(ComputerVerifiers.Outcome.UNKNOWN,bindings.get(0).verifier().lookup(new ComputerVerifiers.Query("unit","key","a".repeat(64),"{}")).outcome());
    }
    @Test void duplicateIdsAcrossDeploymentAndCodeAreRejected() throws Exception {
        var bindings=ComputerVerifierConfiguration.load(file(valid).toString(),name->"token");
        assertThrows(IllegalArgumentException.class,()->new ComputerVerifiers(List.of(bindings.get(0),bindings.get(0))));
    }
}
