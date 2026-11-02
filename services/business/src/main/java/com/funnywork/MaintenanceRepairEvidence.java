package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Read-only release registry provisioned by deployment, never by a desktop or feedback submission. */
@Service
public class MaintenanceRepairEvidence {
    /** Changes only when the read-only evidence verification implementation changes. */
    static final int VERIFICATION_REVISION=3;
    private final Environment env;private final ObjectMapper mapper;private final MaintenanceRemote remote;private final Clock clock;
    public MaintenanceRepairEvidence(Environment env,ObjectMapper mapper,MaintenanceRemote remote,Clock clock){this.env=env;this.mapper=mapper;this.remote=remote;this.clock=clock;}
    public Map<String,Object> verify(Map<String,Object> policy,Map<String,Object> problem,String digest)throws Exception{
        String configured=env.getProperty("funnywork.maintenance-repair-evidence-directory","");
        if(configured.isBlank()||!digest.matches("[a-f0-9]{64}"))throw ApiException.conflict("MAINTENANCE_REPAIR_REGISTRY_REQUIRED");
        Path root=Path.of(configured).toRealPath(),manifest=root.resolve(digest+".json");
        regular(manifest,65536);byte[] bytes=Files.readAllBytes(manifest);
        if(!digest.equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw ApiException.conflict("MAINTENANCE_REPAIR_EVIDENCE_CHANGED");
        JsonNode doc=mapper.readTree(bytes);
        if(!"funnywork.runtime-repair/v1".equals(doc.path("schema").asText())||!policy.get("slug").equals(doc.path("repository").asText())
            ||!problem.get("id").equals(doc.path("problemId").asText())||((Number)problem.get("version")).intValue()!=doc.path("problemVersion").asInt()
            ||!"DEVELOPMENT_FUNCTIONAL".equals(policy.get("environment_policy")))throw ApiException.conflict("MAINTENANCE_REPAIR_EVIDENCE_SCOPE");
        long issued=doc.path("issuedAt").asLong(),expires=doc.path("expiresAt").asLong();
        if(issued<=0||issued>clock.millis()+60000||expires<=clock.millis()||expires-issued>604800000L)throw ApiException.conflict("MAINTENANCE_REPAIR_EVIDENCE_EXPIRED");
        String sha=doc.path("sourceSha").asText(),artifact=doc.path("artifactSha256").asText();
        if(!sha.matches("[a-f0-9]{40}")||!artifact.matches("[a-f0-9]{64}")||!doc.path("deviceId").asText().matches("[A-Za-z0-9-]{1,64}")
            ||!doc.path("policyDeviceId").asText().matches("[a-f0-9]{32}")||!doc.path("clientVersion").asText().matches("[A-Za-z0-9.+_-]{1,80}"))throw ApiException.conflict("MAINTENANCE_REPAIR_EVIDENCE_INVALID");
        for(String check:List.of("codesign","stapler","gatekeeper","leaseRegressionTests","hostTypecheck"))
            if(!"PASS".equals(doc.path("checks").path(check).asText()))throw ApiException.conflict("MAINTENANCE_REPAIR_CHECKS_INCOMPLETE");
        if(((Number)problem.get("version")).intValue()==5) {
            if(!"MODEL_TRANSPORT".equals(doc.path("repairKind").asText())||!doc.path("predecessorEvidenceDigest").asText().matches("[a-f0-9]{64}"))throw ApiException.conflict("MAINTENANCE_REPAIR_CHAIN_INVALID");
            for(String check:List.of("transportRegressionTests","liveTransportProbe"))
                if(!"PASS".equals(doc.path("checks").path(check).asText()))throw ApiException.conflict("MAINTENANCE_REPAIR_CHECKS_INCOMPLETE");
        }
        if(!"Accepted".equals(doc.path("notarization").asText()))throw ApiException.conflict("MAINTENANCE_REPAIR_NOT_NOTARIZED");
        Path archive=root.resolve(artifact+".zip");regular(archive,1073741824L);
        var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(archive)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)hash.update(block,0,n);}
        if(!artifact.equals(hex(hash.digest())))throw ApiException.conflict("MAINTENANCE_REPAIR_ARTIFACT_CHANGED");
        JsonNode commit=sourceCommit(policy,sha);
        String repairSha=doc.path("repairSourceSha").asText(sha);
        if(!repairSha.matches("[a-f0-9]{40}"))throw ApiException.conflict("MAINTENANCE_REPAIR_EVIDENCE_INVALID");
        JsonNode repairCommit=commit;
        if(!repairSha.equals(sha)){
            JsonNode comparison=remote.request((String)policy.get("slug"),(String)policy.get("read_token_ref"),"GET","/compare/"+repairSha+"..."+sha,null);
            if(!"ahead".equals(comparison.path("status").asText())||!repairSha.equals(comparison.path("merge_base_commit").path("sha").asText()))
                throw ApiException.conflict("MAINTENANCE_REPAIR_SOURCE_NOT_INCLUDED");
            repairCommit=sourceCommit(policy,repairSha);
        }
        boolean tests=false;for(var file:repairCommit.path("files")){
            if(file.path("filename").asText().contains("/tests/"))tests=true;
        }
        if(!tests)throw ApiException.conflict("MAINTENANCE_REPAIR_REGRESSION_REQUIRED");
        var logs=new ArrayList<Map<String,Object>>();JsonNode declared=doc.path("releaseChecks").path("regressionLogSha256");
        if(!declared.isMissingNode()){
            if(!declared.isObject()||declared.size()>8)throw ApiException.conflict("MAINTENANCE_REPAIR_LOG_INVALID");
            var fields=declared.fields();while(fields.hasNext()){
                var field=fields.next();String name=field.getKey(),logDigest=field.getValue().asText();
                if(!name.matches("[A-Za-z0-9_.-]{1,80}")||!logDigest.matches("[a-f0-9]{64}"))throw ApiException.conflict("MAINTENANCE_REPAIR_LOG_INVALID");
                Path log=root.resolve(logDigest+".log");regular(log,65536);byte[] content=Files.readAllBytes(log);
                if(!logDigest.equals(hex(MessageDigest.getInstance("SHA-256").digest(content))))throw ApiException.conflict("MAINTENANCE_REPAIR_LOG_CHANGED");
                logs.add(Map.of("name",name,"sha256",logDigest,"text",new String(content,StandardCharsets.UTF_8)));
            }
        }
        return Map.of("registryManifest",doc,"sourceCommit",Map.of("sha",sha,"files",commit.path("files")),
            "repairCommit",Map.of("sha",repairSha,"files",repairCommit.path("files")),"repairIncludedInSource",true,
            "qualificationLogs",logs,"artifactDigestVerified",true,"origin","BACKEND_RELEASE_REGISTRY","assurance","DEVELOPMENT_FUNCTIONAL");
    }
    /** Fetch a complete immutable patch; ancestry is checked separately when the repair predates the build. */
    private JsonNode sourceCommit(Map<String,Object> policy,String sha)throws Exception{
        JsonNode commit=remote.request((String)policy.get("slug"),(String)policy.get("read_token_ref"),"GET","/commits/"+sha,null);
        if(!sha.equals(commit.path("sha").asText())||!commit.path("files").isArray()||commit.path("files").isEmpty()||commit.path("files").size()>=100)
            throw ApiException.conflict("MAINTENANCE_REPAIR_SOURCE_INCOMPLETE");
        for(var file:commit.path("files")){
            if(!file.hasNonNull("patch"))throw ApiException.conflict("MAINTENANCE_REPAIR_SOURCE_INCOMPLETE");
        }
        if(commit.path("files").toString().length()>100000)throw ApiException.conflict("MAINTENANCE_REPAIR_SOURCE_INCOMPLETE");
        return commit;
    }
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    private static void regular(Path path,long max)throws Exception{
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)
            throw ApiException.conflict("MAINTENANCE_REPAIR_REGISTRY_FILE_INVALID");
    }
}
