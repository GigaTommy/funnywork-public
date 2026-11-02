package com.funnywork;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Local deployment trust file, read once at startup; never accepts token-supplied key URLs. */
@Component
public class EnterpriseIdentityConfiguration {
    private final List<EnterpriseIdentity.Provider> providers;
    public EnterpriseIdentityConfiguration(@Value("${funnywork.identity-config:}") String file) {
        providers=load(file);
    }
    public List<EnterpriseIdentity.Provider> providers(){return providers;}
    record Document(List<Entry> providers) {}
    record Entry(String id,String tenant,String issuer,String audience,List<String> publicKeys) {}
    static List<EnterpriseIdentity.Provider> load(String file) {
        if(file.isBlank())return List.of();
        try {
            Path path=Path.of(file);
            if(!path.isAbsolute() || !Files.isRegularFile(path))throw new IllegalArgumentException();
            byte[] bytes;
            try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(65537);}
            if(bytes.length>65536)throw new IllegalArgumentException();
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            Document doc=mapper.readValue(bytes,Document.class);
            if(doc==null || doc.providers()==null || doc.providers().isEmpty() || doc.providers().size()>8)throw new IllegalArgumentException();
            List<EnterpriseIdentity.Provider> result=new ArrayList<>();
            Set<String> ids=new HashSet<>(),issuers=new HashSet<>();
            for(var entry:doc.providers()) {
                if(entry==null || entry.publicKeys()==null || entry.publicKeys().isEmpty() || entry.publicKeys().size()>3)throw new IllegalArgumentException();
                List<RSAPublicKey> keys=new ArrayList<>();
                for(String pem:entry.publicKeys()) {
                    if(pem==null || pem.length()>4096 || !pem.startsWith("-----BEGIN PUBLIC KEY-----\n") || !pem.stripTrailing().endsWith("-----END PUBLIC KEY-----"))throw new IllegalArgumentException();
                    String encoded=pem.substring(27,pem.lastIndexOf("-----END PUBLIC KEY-----")).replaceAll("\\s", "");
                    keys.add((RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))));
                }
                var provider=new EnterpriseIdentity.Provider(entry.id(),entry.tenant(),entry.issuer(),entry.audience(),keys);
                if(!ids.add(provider.id()) || !issuers.add(provider.issuer()))throw new IllegalArgumentException();
                result.add(provider);
            }
            return List.copyOf(result);
        } catch(Exception invalid) {
            // Do not put file content, provider data or key material in startup diagnostics.
            throw new IllegalArgumentException("Invalid enterprise identity trust configuration");
        }
    }
}
