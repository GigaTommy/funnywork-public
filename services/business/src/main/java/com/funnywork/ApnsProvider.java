package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Token authentication stays on the server; HTTP redirects are never followed. */
@Component
public class ApnsProvider {
    public record Result(int status,String reason) {
        public boolean accepted(){return status==200;}
        public boolean invalidToken(){return status==410||status==400&&Set.of("BadDeviceToken","DeviceTokenNotForTopic").contains(reason);}
        public boolean retryable(){return status==429||status>=500;}
    }
    interface Transport { HttpResponse<String> send(HttpRequest request) throws Exception; }
    private final ObjectMapper json;
    private final Clock clock;
    private final String team,keyId,topic;
    private final ECPrivateKey key;
    private final Transport transport;
    private String jwt;
    private long issuedAt;
    @org.springframework.beans.factory.annotation.Autowired
    public ApnsProvider(ObjectMapper json,Clock clock,
        @Value("${FUNNYWORK_APNS_TEAM_ID:}") String team,
        @Value("${FUNNYWORK_APNS_KEY_ID:}") String keyId,
        @Value("${FUNNYWORK_APNS_TOPIC:com.funnyworker.ios}") String topic,
        @Value("${FUNNYWORK_APNS_KEY_FILE:}") String keyFile) {
        this(json,clock,team,keyId,topic,readKey(team,keyId,keyFile),defaultTransport());
    }
    ApnsProvider(ObjectMapper json,Clock clock,String team,String keyId,String topic,ECPrivateKey key,Transport transport) {
        this.json=json;this.clock=clock;this.team=team;this.keyId=keyId;this.topic=topic;this.key=key;this.transport=transport;
        if(key!=null&&(!team.matches("[A-Za-z0-9]{10}")||!keyId.matches("[A-Za-z0-9]{10}")||!topic.matches("[A-Za-z0-9.-]{1,200}")||key.getParams().getOrder().bitLength()!=256))throw new IllegalArgumentException("Invalid APNs configuration");
    }
    private static Transport defaultTransport(){
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        return request->client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static ECPrivateKey readKey(String team,String keyId,String path){
        if(team.isBlank()&&keyId.isBlank()&&path.isBlank())return null;
        if(team.isBlank()||keyId.isBlank()||path.isBlank())throw new IllegalArgumentException("Incomplete APNs configuration");
        try {
            if(Files.size(Path.of(path))>16384)throw new IllegalArgumentException();
            String pem=Files.readString(Path.of(path)).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            return (ECPrivateKey)KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }catch(Exception error){throw new IllegalArgumentException("Cannot load APNs signing key");}
    }
    public boolean enabled(){return key!=null;}
    private String base64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    synchronized String providerToken() throws Exception {
        long now=clock.instant().getEpochSecond();
        if(jwt!=null&&now>=issuedAt&&now-issuedAt<3000)return jwt;
        String unsigned=base64(json.writeValueAsBytes(Map.of("alg","ES256","kid",keyId)))+"."+base64(json.writeValueAsBytes(Map.of("iss",team,"iat",now)));
        Signature signature=Signature.getInstance("SHA256withECDSAinP1363Format");signature.initSign(key);signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        jwt=unsigned+"."+base64(signature.sign());issuedAt=now;return jwt;
    }
    public Result send(String token,String environment,String collapseId,Map<String,Object> payload,long expiresAt) throws Exception {
        if(!enabled())throw new IllegalStateException("APNs is not configured");
        if(token==null||!token.matches("[a-fA-F0-9]{32,512}")||token.length()%2!=0||!Set.of("sandbox","production").contains(environment)||!collapseId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid push destination");
        byte[] body=json.writeValueAsBytes(payload);if(body.length>4096)throw new IllegalArgumentException("Push payload too large");
        String host=environment.equals("sandbox")?"api.sandbox.push.apple.com":"api.push.apple.com";
        var request=HttpRequest.newBuilder(URI.create("https://"+host+"/3/device/"+token)).version(HttpClient.Version.HTTP_2).timeout(Duration.ofSeconds(10))
            .header("authorization","bearer "+providerToken()).header("apns-topic",topic).header("apns-push-type","alert").header("apns-priority","10")
            .header("apns-collapse-id",collapseId).header("apns-expiration",Long.toString(Math.max(0,expiresAt/1000)))
            .header("content-type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=transport.send(request);String reason="";
        if(response.body()!=null&&response.body().length()<=4096){try{reason=json.readTree(response.body()).path("reason").asText("");}catch(Exception ignored){}}
        return new Result(response.statusCode(),reason);
    }
}
