package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApnsProviderTest {
    private final ObjectMapper json=new ObjectMapper();
    private KeyPair key() throws Exception {var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));return generator.generateKeyPair();}
    @Test void signsProviderTokenAndBuildsBoundedAppleRequest() throws Exception {
        var keys=key();var captured=new AtomicReference<HttpRequest>();
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("");
        var provider=new ApnsProvider(json,Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC),"TEAMID1234","KEYID12345","com.funnyworker.ios",(ECPrivateKey)keys.getPrivate(),request->{captured.set(request);return response;});
        String jwt=provider.providerToken();assertEquals(jwt,provider.providerToken());String[] parts=jwt.split("\\.");assertEquals(3,parts.length);
        var header=json.readTree(Base64.getUrlDecoder().decode(parts[0]));assertEquals("ES256",header.path("alg").asText());assertEquals("KEYID12345",header.path("kid").asText());
        var claims=json.readTree(Base64.getUrlDecoder().decode(parts[1]));assertEquals("TEAMID1234",claims.path("iss").asText());assertEquals(1800000000L,claims.path("iat").asLong());
        byte[] signature=Base64.getUrlDecoder().decode(parts[2]);assertEquals(64,signature.length);
        var verifier=Signature.getInstance("SHA256withECDSAinP1363Format");verifier.initVerify(keys.getPublic());verifier.update((parts[0]+"."+parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertTrue(verifier.verify(signature));
        assertTrue(provider.send("ab".repeat(32),"sandbox","notification-1",Map.of("aps",Map.of("alert","Ready")),1800000300000L).accepted());
        var request=captured.get();assertEquals("api.sandbox.push.apple.com",request.uri().getHost());assertEquals(HttpClient.Version.HTTP_2,request.version().orElseThrow());
        assertEquals("alert",request.headers().firstValue("apns-push-type").orElseThrow());assertEquals("notification-1",request.headers().firstValue("apns-collapse-id").orElseThrow());
        assertEquals("1800000300",request.headers().firstValue("apns-expiration").orElseThrow());assertEquals("bearer "+jwt,request.headers().firstValue("authorization").orElseThrow());
        provider.send("ab".repeat(32),"production","notification-1",Map.of("aps",Map.of("alert","Ready")),1800000300000L);
        assertEquals("api.push.apple.com",captured.get().uri().getHost());
        assertThrows(IllegalArgumentException.class,()->provider.send("invalid","sandbox","a",Map.of(),0));
        assertThrows(IllegalArgumentException.class,()->provider.send("ab".repeat(32),"sandbox","a",Map.of("text","x".repeat(4097)),0));
    }
    @Test void providerResponsesDistinguishInvalidTokensAndRetryableFailures() {
        assertTrue(new ApnsProvider.Result(410,"Unregistered").invalidToken());
        assertTrue(new ApnsProvider.Result(400,"BadDeviceToken").invalidToken());
        assertFalse(new ApnsProvider.Result(403,"ExpiredProviderToken").invalidToken());
        assertTrue(new ApnsProvider.Result(429,"TooManyRequests").retryable());assertTrue(new ApnsProvider.Result(503,"ServiceUnavailable").retryable());
        assertFalse(new ApnsProvider.Result(400,"BadPayload").retryable());
    }
}
