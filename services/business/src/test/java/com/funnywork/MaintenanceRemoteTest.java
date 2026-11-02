package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceRemoteTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void onlyReadOnlyFullCommitComparisonsPassTheDotPathGuard()throws Exception{
        var credentials=mock(MaintenanceGithubCredentials.class);var remote=new MaintenanceRemote(new MockEnvironment(),mapper,credentials);
        String comparison="/compare/"+"a".repeat(40)+"..."+"b".repeat(40);
        for(String path:List.of("/contents/../secret","/compare/main..."+"b".repeat(40),comparison+"/../secret",comparison+"?redirect=elsewhere"))
            assertEquals("POLICY_DENIED",assertThrows(ApiException.class,()->remote.request("example/project","FUNNYWORK_GITHUB_READ","GET",path,null)).code);
        assertThrows(ApiException.class,()->remote.request("example/project","FUNNYWORK_GITHUB_READ","POST",comparison,null));
        assertThrows(ApiException.class,()->remote.request("example/project","FUNNYWORK_GITHUB_READ","GET",comparison,Map.of()));
        verifyNoInteractions(credentials);
        var reachedCredentials=new IllegalStateException("validated comparison reached credential lookup");
        when(credentials.token("FUNNYWORK_GITHUB_READ","example/project")).thenThrow(reachedCredentials);
        assertSame(reachedCredentials,assertThrows(IllegalStateException.class,()->remote.request("example/project","FUNNYWORK_GITHUB_READ","GET",comparison,null)));
    }
    @Test void remoteTreeModeRejectsSymlinkEvenWhenClientReportsSuccessfulChecks()throws Exception{
        var remote=spy(new MaintenanceRemote(new MockEnvironment(),mapper,mock(MaintenanceGithubCredentials.class)));
        var mode=new java.util.concurrent.atomic.AtomicReference<>("100644");
        doAnswer(inv->{String path=inv.getArgument(3);
            if(path.equals("/pulls/1"))return mapper.valueToTree(Map.of("head",Map.of("sha","b".repeat(40),"repo",Map.of("full_name","example/project")),"base",Map.of("ref","main","sha","a".repeat(40)),"state","open","changed_files",1));
            if(path.contains("/files?"))return mapper.valueToTree(List.of(Map.of("filename","apps/file.ts","status","modified","patch","+fixed")));
            if(path.startsWith("/git/commits/"))return mapper.valueToTree(Map.of("tree",Map.of("sha","root")));
            if(path.equals("/git/trees/root"))return mapper.valueToTree(Map.of("tree",List.of(Map.of("path","apps","type","tree","sha","apps-tree"))));
            if(path.equals("/git/trees/apps-tree"))return mapper.valueToTree(Map.of("tree",List.of(Map.of("path","file.ts","type","blob","mode",mode.get(),"sha","blob","size",1))));
            throw new IllegalStateException(path);
        }).when(remote).request(anyString(),anyString(),anyString(),anyString(),any());
        var policy=new HashMap<String,Object>();policy.put("slug","example/project");policy.put("read_token_ref","FUNNYWORK_GITHUB_READ");policy.put("base_branch","main");policy.put("environment_policy","DEVELOPMENT_FUNCTIONAL");
        var report=Map.of("prUrl","https://github.com/example/project/pull/1","resultSha","b".repeat(40));
        assertEquals("b".repeat(40),remote.candidate(policy,report,List.of("apps/file.ts")).get("headSha"));
        mode.set("120000");assertEquals("MAINTENANCE_CANDIDATE_FILE_TYPE_INVALID",assertThrows(ApiException.class,()->remote.candidate(policy,report,List.of("apps/file.ts"))).code);
        assertEquals("MAINTENANCE_CANDIDATE_CHANGED",assertThrows(ApiException.class,()->remote.candidate(policy,Map.of("prUrl",report.get("prUrl"),"resultSha","c".repeat(40)),List.of("apps/file.ts"))).code);
    }
    @Test void protectedPolicyFilesCannotBeApprovedThroughOrdinaryChangeScope(){
        assertTrue(MaintenanceRemote.sensitive(".github/workflows/check.yml"));
        assertTrue(MaintenanceRemote.sensitive("apps/desktop/AGENTS.md"));
        assertTrue(MaintenanceRemote.sensitive("services/business/src/main/java/com/funnywork/MaintenanceService.java"));
        assertFalse(MaintenanceRemote.sensitive("apps/desktop/apps/desktop/src/locale.ts"));
    }

    @Test void baselineSamplesSwiftAndKotlinAndSkipsOverBudgetFiles() throws Exception {
        var remote=spy(new MaintenanceRemote(new MockEnvironment(),mapper,mock(MaintenanceGithubCredentials.class)));
        String sha="a".repeat(40);
        doReturn(mapper.readTree("{\"commit\":{\"sha\":\""+sha+"\"}}" )).when(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/branches/main",null);
        doReturn(mapper.readTree("{\"tree\":{\"sha\":\"root\"}}" )).when(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/git/commits/"+sha,null);
        doReturn(mapper.readTree("{\"tree\":[{\"path\":\"apps\",\"type\":\"tree\",\"sha\":\"apps-tree\"}]}" )).when(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/git/trees/root",null);
        doReturn(mapper.readTree("{\"tree\":[{\"path\":\"huge.json\",\"type\":\"blob\",\"sha\":\"huge\",\"size\":50000},{\"path\":\"Screen.swift\",\"type\":\"blob\",\"sha\":\"swift\",\"size\":1},{\"path\":\"Screen.kt\",\"type\":\"blob\",\"sha\":\"kotlin\",\"size\":1}]}" )).when(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/git/trees/apps-tree?recursive=1",null);
        for(String blob:List.of("swift","kotlin"))doReturn(mapper.readTree("{\"encoding\":\"base64\",\"content\":\"eA==\"}" )).when(remote).request("example/project","FUNNYWORK_GITHUB_READ","GET","/git/blobs/"+blob,null);
        var result=remote.baseline(Map.of("slug","example/project","read_token_ref","FUNNYWORK_GITHUB_READ","base_branch","main"),List.of("apps"));
        assertEquals(2,((List<?>)result.get("sourceSnippets")).size());
        verify(remote,never()).request("example/project","FUNNYWORK_GITHUB_READ","GET","/git/blobs/huge",null);
    }

}
