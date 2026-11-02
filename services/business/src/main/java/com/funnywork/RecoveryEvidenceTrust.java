package com.funnywork;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;

/** Startup-owned trust; uploaded evidence never supplies its own trust anchor. */
@Component
public class RecoveryEvidenceTrust {
    public record Issuer(String id,List<String> tenants,List<String> zones,List<String> kinds,String publicKeyDer){}
    record Configuration(List<Issuer> issuers){}
    record Envelope(Integer format,String issuerId,String payload,String signature){}
    record Payload(Integer format,String issuerId,String tenant,String zone,String caseId,String attemptId,String nodeId,String recoveryGeneration,String kind,String outcome,Long issuedAt,Long expiresAt){}
    public record Context(String tenant,String zone,String caseId,String attemptId,String nodeId,String generation,String kind){}
    public record Verified(String issuer,String keySha256,String policySha256,String outcome,long issuedAt,long expiresAt){}
    private record Trusted(Issuer scope,RSAPublicKey key,String keyHash,String policyHash){}
    private final Map<String,Trusted> issuers;
    private static final byte[] DOMAIN="funnywork-recovery-evidence/v1\n".getBytes(StandardCharsets.UTF_8);
    private static ObjectMapper mapper(){
        return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    public RecoveryEvidenceTrust(@Value("${funnywork.recovery-evidence-trust:}") String file){
        var loaded=new HashMap<String,Trusted>();
        try{
            if(!file.isBlank()){
                Path path=Path.of(file);
                if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
                byte[] bytes;try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(131073);}
                if(bytes.length>131072)throw new IllegalArgumentException();
                var configuration=mapper().readValue(bytes,Configuration.class);
                if(configuration==null || configuration.issuers()==null || configuration.issuers().isEmpty() || configuration.issuers().size()>32)throw new IllegalArgumentException();
                for(var issuer:configuration.issuers()){
                    if(issuer==null || issuer.id()==null || !issuer.id().matches("[A-Za-z0-9_-]{1,64}") || loaded.containsKey(issuer.id()))throw new IllegalArgumentException();
                    var tenants=scope(issuer.tenants());var zones=scope(issuer.zones());var kinds=scope(issuer.kinds());
                    if(!Set.of("STOP_ATTESTATION","BUSINESS_RECEIPT","NODE_COMPARISON").containsAll(kinds))throw new IllegalArgumentException();
                    byte[] der=decode(issuer.publicKeyDer(),4096);
                    var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                    if(key.getModulus().bitLength()<3072 || key.getModulus().bitLength()>8192)throw new IllegalArgumentException();
                    String keyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
                    String policy=AuthService.hash(issuer.id()+"\n"+tenants+"\n"+zones+"\n"+kinds+"\n"+keyHash);
                    loaded.put(issuer.id(),new Trusted(new Issuer(issuer.id(),List.copyOf(tenants),List.copyOf(zones),List.copyOf(kinds),issuer.publicKeyDer()),key,keyHash,policy));
                }
            }
        }catch(Exception error){throw new IllegalArgumentException("Invalid recovery evidence trust configuration");}
        issuers=Map.copyOf(loaded);
    }
    private static SortedSet<String> scope(List<String> values){
        if(values==null || values.isEmpty() || values.size()>64)throw new IllegalArgumentException();
        var out=new TreeSet<String>();for(String value:values)if(value==null || !value.matches("[A-Za-z0-9_-]{1,64}") || !out.add(value))throw new IllegalArgumentException();return out;
    }
    private static byte[] decode(String value,int max){
        if(value==null || value.length()>max*2)throw new IllegalArgumentException();
        byte[] bytes=Base64.getDecoder().decode(value);
        if(bytes.length>max || !Base64.getEncoder().encodeToString(bytes).equals(value))throw new IllegalArgumentException();return bytes;
    }
    public Verified verify(String content,Context expected,long now){
        try{
            if(content==null || content.length()>16384)throw new IllegalArgumentException();
            var envelope=mapper().readValue(content,Envelope.class);
            if(envelope==null || !Integer.valueOf(1).equals(envelope.format()))throw new IllegalArgumentException();
            var trusted=issuers.get(envelope.issuerId());if(trusted==null)throw new IllegalArgumentException();
            var scope=trusted.scope();
            if(!scope.tenants().contains(expected.tenant()) || !scope.zones().contains(expected.zone()) || !scope.kinds().contains(expected.kind()))throw new IllegalArgumentException();
            byte[] bytes=decode(envelope.payload(),8192),signature=decode(envelope.signature(),1024);
            Signature verifier=Signature.getInstance("RSASSA-PSS");verifier.setParameter(new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));verifier.initVerify(trusted.key());verifier.update(DOMAIN);verifier.update(bytes);
            if(!verifier.verify(signature))throw new IllegalArgumentException();
            var payload=mapper().readValue(bytes,Payload.class);
            if(payload==null || !Integer.valueOf(1).equals(payload.format()) || !scope.id().equals(payload.issuerId())
                || !expected.tenant().equals(payload.tenant()) || !expected.zone().equals(payload.zone()) || !expected.caseId().equals(payload.caseId())
                || !expected.attemptId().equals(payload.attemptId()) || !expected.nodeId().equals(payload.nodeId()) || !expected.generation().equals(payload.recoveryGeneration())
                || !expected.kind().equals(payload.kind()) || payload.issuedAt()==null || payload.expiresAt()==null
                || payload.issuedAt()<0 || payload.issuedAt()>now+30000 || payload.expiresAt()<=now || payload.expiresAt()<=payload.issuedAt()
                || payload.expiresAt()-payload.issuedAt()>300000)throw new IllegalArgumentException();
            Set<String> outcomes=switch(expected.kind()){
                case "STOP_ATTESTATION" -> Set.of("STOPPED","UNKNOWN");
                case "BUSINESS_RECEIPT" -> Set.of("CONFIRMED","REJECTED","UNKNOWN");
                case "NODE_COMPARISON" -> Set.of("MATCHED","DIFFERENCES","INCOMPLETE");
                default -> Set.of();
            };
            if(payload.outcome()==null || !outcomes.contains(payload.outcome()))throw new IllegalArgumentException();
            return new Verified(scope.id(),trusted.keyHash(),trusted.policyHash(),payload.outcome(),payload.issuedAt(),payload.expiresAt());
        }catch(Exception error){throw new ApiException(400,"RECOVERY_EVIDENCE_UNTRUSTED","Evidence signature, scope or time is invalid");}
    }
}
