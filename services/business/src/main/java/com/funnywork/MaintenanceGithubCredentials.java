package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Credentials never enter task specs or model context. App tokens are repository scoped and renewed on demand. */
@Service
public class MaintenanceGithubCredentials {
    private record Cached(String value,Instant expires) {}
    private final Environment env;private final ObjectMapper mapper;
    private final Map<String,Cached> cache=new HashMap<>();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MaintenanceGithubCredentials(Environment env,ObjectMapper mapper){this.env=env;this.mapper=mapper;}
    public synchronized String token(String ref,String slug) throws Exception {
        if(!ref.matches("FUNNYWORK_GITHUB_[A-Z0-9_]{1,80}")||!slug.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw ApiException.forbidden();
        String app=env.getProperty(ref+"_APP_ID"),installation=env.getProperty(ref+"_INSTALLATION_ID"),keyFile=env.getProperty(ref+"_PRIVATE_KEY_FILE");
        if(app==null&&installation==null&&keyFile==null){String value=env.getProperty(ref);if(value==null||value.isBlank())throw ApiException.conflict("MAINTENANCE_GITHUB_CREDENTIAL_UNAVAILABLE");return value;}
        if(app==null||!app.matches("[0-9]+")||installation==null||!installation.matches("[0-9]+")||keyFile==null)throw ApiException.conflict("MAINTENANCE_GITHUB_APP_CONFIG_INVALID");
        String cacheKey=ref+":"+slug;var cached=cache.get(cacheKey);Instant now=Instant.now();
        if(cached!=null&&cached.expires().isAfter(now.plusSeconds(120)))return cached.value();
        Path file=Path.of(keyFile);if(!file.isAbsolute()||Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>16384)throw ApiException.conflict("MAINTENANCE_GITHUB_KEY_UNAVAILABLE");
        String pem=Files.readString(file);
        if(!pem.startsWith("-----BEGIN PRIVATE KEY-----"))throw ApiException.conflict("MAINTENANCE_GITHUB_PKCS8_REQUIRED");
        byte[] der=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
        var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        var encoding=Base64.getUrlEncoder().withoutPadding();
        String header=encoding.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload=encoding.encodeToString(mapper.writeValueAsBytes(Map.of("iat",now.getEpochSecond()-60,"exp",now.getEpochSecond()+540,"iss",app)));
        String signing=header+"."+payload;var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);signer.update(signing.getBytes(StandardCharsets.US_ASCII));String jwt=signing+"."+encoding.encodeToString(signer.sign());
        var request=HttpRequest.newBuilder(URI.create("https://api.github.com/app/installations/"+installation+"/access_tokens"))
            .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+jwt).header("Accept","application/vnd.github+json")
            .header("Content-Type","application/json").header("X-GitHub-Api-Version","2022-11-28")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("repositories",List.of(slug.split("/")[1]))))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(65537);}
        if(response.statusCode()!=201||bytes.length>65536)throw ApiException.conflict("MAINTENANCE_GITHUB_APP_TOKEN_UNAVAILABLE");
        var result=mapper.readTree(bytes);String token=result.path("token").asText();if(token.isBlank())throw ApiException.conflict("MAINTENANCE_GITHUB_APP_TOKEN_UNAVAILABLE");
        cache.put(cacheKey,new Cached(token,Instant.parse(result.path("expires_at").asText())));return token;
    }
    public synchronized void invalidate(String ref){cache.keySet().removeIf(k->k.startsWith(ref+":"));}
}
