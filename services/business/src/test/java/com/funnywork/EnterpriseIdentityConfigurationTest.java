package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseIdentityConfigurationTest {
    @TempDir Path directory;
    static final KeyPair KEY=EnterpriseIdentityIntegrationTest.key();
    static String pem(KeyPair key){return "-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n";}
    Map<String,Object> entry(){return new LinkedHashMap<>(Map.of("id","bank","tenant","bank-1","issuer","https://sso.internal.example","audience","funnywork","publicKeys",List.of(pem(KEY))));}
    String save(Object document)throws Exception {
        Path file=directory.resolve("identity.json");Files.writeString(file,new ObjectMapper().writeValueAsString(document));return file.toString();
    }
    @Test void localConfigLoadsImmutablePinnedProviders()throws Exception {
        var config=new EnterpriseIdentityConfiguration(save(Map.of("providers",List.of(entry()))));
        var providers=config.providers();assertEquals(1,providers.size());assertEquals(KEY.getPublic(),providers.get(0).keys().get(0));
        assertThrows(UnsupportedOperationException.class,()->providers.clear());
        assertTrue(new EnterpriseIdentityConfiguration("").providers().isEmpty());
        assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load("relative.json"));
        assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load(directory.resolve("missing").toString()));
    }
    @Test void rejectsMalformedDuplicateUnknownAndOversizedTrustFiles()throws Exception {
        var item=entry();item.put("jwksUrl","https://arbitrary.invalid");
        assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load(save(Map.of("providers",List.of(item)))));
        assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load(save(Map.of("providers",List.of(entry(),entry())))));
        for(String text:List.of("{\"providers\":[],\"providers\":[]}","{\"providers\":[]} {}"," ".repeat(65537),"null")) {
            var file=directory.resolve("invalid.json");Files.writeString(file,text);
            assertEquals("Invalid enterprise identity trust configuration",assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load(file.toString())).getMessage());
        }
    }
    @Test void rejectsInvalidKeyMaterialAndWeakOrDuplicateKeys()throws Exception {
        var weak=KeyPairGenerator.getInstance("RSA");weak.initialize(1024);
        for(var keys:List.of(List.<String>of(),List.of("not-a-key"),List.of(pem(weak.generateKeyPair())),List.of(pem(KEY),pem(KEY)))) {
            var item=entry();item.put("publicKeys",keys);
            assertThrows(IllegalArgumentException.class,()->EnterpriseIdentityConfiguration.load(save(Map.of("providers",List.of(item)))));
        }
    }
}
