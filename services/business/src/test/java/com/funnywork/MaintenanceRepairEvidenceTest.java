package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MaintenanceRepairEvidenceTest {
    @TempDir Path root;
    final ObjectMapper mapper=new ObjectMapper();final MaintenanceRemote remote=mock(MaintenanceRemote.class);
    final long now=1800000000000L;
    Map<String,Object> policy=Map.of("slug","example/project","read_token_ref","FUNNYWORK_GITHUB_READ","environment_policy","DEVELOPMENT_FUNCTIONAL");
    Map<String,Object> problem=Map.of("id","problem-1","version",4);
    MaintenanceRepairEvidence verifier;Map<String,Object> manifest;
    String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    @BeforeEach void setup()throws Exception{
        verifier=new MaintenanceRepairEvidence(new MockEnvironment().withProperty("funnywork.maintenance-repair-evidence-directory",root.toString()),mapper,remote,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        byte[] artifact="backend-registered-release-artifact".getBytes();String digest=sha(artifact);Files.write(root.resolve(digest+".zip"),artifact);
        manifest=new LinkedHashMap<>(Map.of("schema","funnywork.runtime-repair/v1","repository","example/project","problemId","problem-1","problemVersion",4,"sourceSha","a".repeat(40),"artifactSha256",digest,"deviceId","original-device","policyDeviceId","f".repeat(32),"clientVersion","new-build","notarization","Accepted"));
        manifest.put("issuedAt",now-1000);manifest.put("expiresAt",now+86400000);
        manifest.put("checks",Map.of("codesign","PASS","stapler","PASS","gatekeeper","PASS","leaseRegressionTests","PASS","hostTypecheck","PASS"));
        when(remote.request(anyString(),anyString(),eq("GET"),eq("/commits/"+"a".repeat(40)),isNull())).thenReturn(mapper.readTree("{\"sha\":\""+"a".repeat(40)+"\",\"files\":[{\"filename\":\"apps/desktop/tests/lease.spec.ts\",\"patch\":\"+ expiry test\"}]}"));
    }
    String save()throws Exception{byte[] bytes=mapper.writeValueAsBytes(manifest);String d=sha(bytes);Files.write(root.resolve(d+".json"),bytes);return d;}
    @Test void backendRehashesTheRegisteredArtifactAndFetchesSourceIndependently()throws Exception{
        var result=verifier.verify(policy,problem,save());assertEquals(true,result.get("artifactDigestVerified"));
        assertEquals("BACKEND_RELEASE_REGISTRY",result.get("origin"));
        verify(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/commits/"+"a".repeat(40),null);
    }
    @Test void registryFileAndArtifactTamperingAreRejectedBeforeModelInput()throws Exception{
        String d=save();Files.writeString(root.resolve(d+".json"),"{}");assertEquals("MAINTENANCE_REPAIR_EVIDENCE_CHANGED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,d)).code);
        String digest=save();Files.writeString(root.resolve(manifest.get("artifactSha256")+".zip"),"modified");assertEquals("MAINTENANCE_REPAIR_ARTIFACT_CHANGED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,digest)).code);
        verifyNoInteractions(remote);
    }
    @Test void evidenceCannotMoveToAnotherIssueOrOutliveItsWindow()throws Exception{
        manifest.put("problemId","other-problem");String d=save();assertEquals("MAINTENANCE_REPAIR_EVIDENCE_SCOPE",assertThrows(ApiException.class,()->verifier.verify(policy,problem,d)).code);
        manifest.put("problemId","problem-1");manifest.put("expiresAt",now-1);String expired=save();assertEquals("MAINTENANCE_REPAIR_EVIDENCE_EXPIRED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,expired)).code);
        verifyNoInteractions(remote);
    }
    @Test void partialChecksAndMissingRegressionPatchFailClosed()throws Exception{
        manifest.put("checks",Map.of("codesign","PASS"));String d=save();assertEquals("MAINTENANCE_REPAIR_CHECKS_INCOMPLETE",assertThrows(ApiException.class,()->verifier.verify(policy,problem,d)).code);
    }
    void includedRepair(String status,String ancestor,String filename,String patch)throws Exception{
        manifest.put("repairSourceSha","b".repeat(40));
        when(remote.request(anyString(),anyString(),eq("GET"),eq("/commits/"+"a".repeat(40)),isNull())).thenReturn(mapper.valueToTree(Map.of("sha","a".repeat(40),"files",List.of(Map.of("filename","package.json","patch","- unavailable export")))));
        when(remote.request(anyString(),anyString(),eq("GET"),eq("/compare/"+"b".repeat(40)+"..."+"a".repeat(40)),isNull())).thenReturn(mapper.valueToTree(Map.of("status",status,"merge_base_commit",Map.of("sha",ancestor))));
        Map<String,Object> file=new LinkedHashMap<>();file.put("filename",filename);if(patch!=null)file.put("patch",patch);
        when(remote.request(anyString(),anyString(),eq("GET"),eq("/commits/"+"b".repeat(40)),isNull())).thenReturn(mapper.valueToTree(Map.of("sha","b".repeat(40),"files",List.of(file))));
    }
    @Test void aLaterBuildRetainsTheIndependentlyFetchedRepairAndRegressionPatch()throws Exception{
        includedRepair("ahead","b".repeat(40),"apps/desktop/tests/transport.spec.ts","+ no duplicate execution");
        var result=verifier.verify(policy,problem,save());
        assertEquals(true,result.get("repairIncludedInSource"));
        assertEquals("a".repeat(40),((Map<?,?>)result.get("sourceCommit")).get("sha"));
        assertEquals("b".repeat(40),((Map<?,?>)result.get("repairCommit")).get("sha"));
        verify(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/compare/"+"b".repeat(40)+"..."+"a".repeat(40),null);
    }
    @Test void anUnrelatedOrDivergedRepairCannotQualifyTheInstalledBuild()throws Exception{
        for(String status:List.of("behind","diverged","ahead")){
            includedRepair(status,"c".repeat(40),"apps/desktop/tests/transport.spec.ts","+ test");String d=save();
            assertEquals("MAINTENANCE_REPAIR_SOURCE_NOT_INCLUDED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,d)).code);
        }
        verify(remote,never()).request(anyString(),anyString(),eq("GET"),eq("/commits/"+"b".repeat(40)),isNull());
    }
    @Test void anIncludedRepairStillNeedsCompletePatchAndRegressionCoverage()throws Exception{
        includedRepair("ahead","b".repeat(40),"apps/desktop/tests/transport.spec.ts",null);String partial=save();
        assertEquals("MAINTENANCE_REPAIR_SOURCE_INCOMPLETE",assertThrows(ApiException.class,()->verifier.verify(policy,problem,partial)).code);
        includedRepair("ahead","b".repeat(40),"apps/desktop/src/transport.ts","+ timeout fix");String untested=save();
        assertEquals("MAINTENANCE_REPAIR_REGRESSION_REQUIRED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,untested)).code);
        manifest.put("repairSourceSha","main");String mutable=save();
        assertEquals("MAINTENANCE_REPAIR_EVIDENCE_INVALID",assertThrows(ApiException.class,()->verifier.verify(policy,problem,mutable)).code);
    }
    @Test void declaredQualificationLogsAreFetchedAndRehashedBeforeReview()throws Exception{
        byte[] log="Transport regression: 40 tests passed".getBytes();String hash=sha(log);
        manifest.put("releaseChecks",Map.of("regressionLogSha256",Map.of("transport-tests.log",hash)));Files.write(root.resolve(hash+".log"),log);
        var logs=(List<?>)verifier.verify(policy,problem,save()).get("qualificationLogs");
        assertEquals("Transport regression: 40 tests passed",((Map<?,?>)logs.get(0)).get("text"));
        Files.writeString(root.resolve(hash+".log"),"tampered");String digest=save();
        assertEquals("MAINTENANCE_REPAIR_LOG_CHANGED",assertThrows(ApiException.class,()->verifier.verify(policy,problem,digest)).code);
    }
    @Test void logReferencesCannotChooseAnArbitraryServerPath()throws Exception{
        manifest.put("releaseChecks",Map.of("regressionLogSha256",Map.of("transport-tests.log","../secret")));String digest=save();
        assertEquals("MAINTENANCE_REPAIR_LOG_INVALID",assertThrows(ApiException.class,()->verifier.verify(policy,problem,digest)).code);
    }
}
