package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEvidenceTrustTest {
    @TempDir Path directory;
    static KeyPair keys;final ObjectMapper mapper=new ObjectMapper();
    final long now=1800000000000L;
    final RecoveryEvidenceTrust.Context context=new RecoveryEvidenceTrust.Context("tenant","zone","case","attempt","node","generation","STOP_ATTESTATION");
    @BeforeAll static void key() throws Exception {var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(3072);keys=gen.generateKeyPair();}
    String config() throws Exception {return mapper.writeValueAsString(Map.of("issuers",List.of(Map.of("id","issuer","tenants",List.of("tenant"),"zones",List.of("zone"),"kinds",List.of("STOP_ATTESTATION"),"publicKeyDer",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())))));}
    RecoveryEvidenceTrust trust() throws Exception {var path=directory.resolve("trust.json");Files.writeString(path,config());return new RecoveryEvidenceTrust(path.toString());}
    Map<String,Object> payload(){var p=new LinkedHashMap<String,Object>();p.put("format",1);p.put("issuerId","issuer");p.put("tenant","tenant");p.put("zone","zone");p.put("caseId","case");p.put("attemptId","attempt");p.put("nodeId","node");p.put("recoveryGeneration","generation");p.put("kind","STOP_ATTESTATION");p.put("outcome","STOPPED");p.put("issuedAt",now);p.put("expiresAt",now+60000);return p;}
    String sign(byte[] payload) throws Exception {
        var signature=Signature.getInstance("RSASSA-PSS");signature.setParameter(new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));signature.initSign(keys.getPrivate());signature.update("funnywork-recovery-evidence/v1\n".getBytes(StandardCharsets.UTF_8));signature.update(payload);
        return mapper.writeValueAsString(Map.of("format",1,"issuerId","issuer","payload",Base64.getEncoder().encodeToString(payload),"signature",Base64.getEncoder().encodeToString(signature.sign())));
    }
    String sign(Map<String,Object> p) throws Exception{return sign(mapper.writeValueAsBytes(p));}
    @Test void exactSignedScopeAcceptedAndUnconfiguredIssuerRejected() throws Exception {
        String signed=sign(payload());var result=trust().verify(signed,context,now);
        assertEquals("STOPPED",result.outcome());assertEquals(64,result.keySha256().length());
        assertThrows(ApiException.class,()->new RecoveryEvidenceTrust("").verify(signed,context,now));
        assertThrows(ApiException.class,()->trust().verify(signed,new RecoveryEvidenceTrust.Context("other","zone","case","attempt","node","generation","STOP_ATTESTATION"),now));
    }
    @Test void EveryIdentityFieldTimeOutcomeAndSignatureAreBound() throws Exception {
        var trust=trust();
        for(String key:List.of("tenant","zone","caseId","attemptId","nodeId","recoveryGeneration","kind","issuerId","outcome")){
            var p=payload();p.put(key,"wrong");String signed=sign(p);assertThrows(ApiException.class,()->trust.verify(signed,context,now));
        }
        for(long[] times:List.of(new long[]{now-60000,now},new long[]{now+31000,now+60000},new long[]{now,now+300001})){
            var p=payload();p.put("issuedAt",times[0]);p.put("expiresAt",times[1]);String signed=sign(p);assertThrows(ApiException.class,()->trust.verify(signed,context,now));
        }
        var envelope=mapper.readValue(sign(payload()),Map.class);envelope.put("payload",Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ApiException.class,()->trust.verify(mapper.writeValueAsString(envelope),context,now));
    }
    @Test void DuplicateUnknownCoercedAndTrailingSignedFieldsAreRejected() throws Exception {
        var trust=trust();String body=mapper.writeValueAsString(payload());
        for(String text:List.of(body+" {}",body.replace("\"format\":1","\"format\":1,\"format\":1"),body.replace("\"format\":1","\"format\":\"1\""),body.replace("\"format\":1","\"extra\":true,\"format\":1"))){
            String signed=sign(text.getBytes(StandardCharsets.UTF_8));assertThrows(ApiException.class,()->trust.verify(signed,context,now));
        }
    }
    @Test void InvalidTrustFilesFailClosed() throws Exception {
        for(String text:List.of(config()+" {}",config().replace("\"issuers\":","\"extra\":true,\"issuers\":"),config().replace("STOP_ATTESTATION","OPERATOR_NOTE"),config().replace("[\"zone\"]","[\"zone\",\"zone\"]"),"x".repeat(131073))){
            Path file=directory.resolve("invalid.json");Files.writeString(file,text);assertThrows(IllegalArgumentException.class,()->new RecoveryEvidenceTrust(file.toString()));
        }
        assertThrows(IllegalArgumentException.class,()->new RecoveryEvidenceTrust("relative.json"));
    }
}
