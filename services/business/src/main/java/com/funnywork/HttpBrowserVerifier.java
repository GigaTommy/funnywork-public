package com.funnywork;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.Supplier;

/** Fixed deployment endpoint. Lookup only; this class cannot dispatch browser/business operations. */
public final class HttpBrowserVerifier implements BrowserVerifiers.Verifier {
    private final URI base;private final Supplier<String> credential;private final ObjectMapper mapper;private final int timeoutMs;
    public HttpBrowserVerifier(URI base,Supplier<String> credential,ObjectMapper mapper,int timeoutMs) {
        String host=base.getHost();
        if(host==null || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && Set.of("127.0.0.1","[::1]").contains(host)))
            || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null || !base.getPath().endsWith("/")
            || base.getPath().contains("..") || base.getRawPath().contains("%") || base.getPort()==0 || base.getPort()>65535
            || credential==null || mapper==null || timeoutMs<100 || timeoutMs>30000)throw new IllegalArgumentException("Invalid browser source configuration");
        this.base=base;this.credential=credential;this.mapper=mapper;this.timeoutMs=timeoutMs;
    }
    @Override public BrowserVerifiers.Evidence lookup(BrowserVerifiers.Query query) {
        var unknown=new BrowserVerifiers.Evidence(BrowserVerifiers.Outcome.UNKNOWN,query.proofHash(),null);
        if(query.unitId()==null || !query.unitId().matches("[A-Za-z0-9-]{1,64}") || query.proofHash()==null || !query.proofHash().matches("[a-f0-9]{64}")
            || query.businessKey()==null || query.businessKey().isBlank() || query.businessKey().length()>200)return unknown;
        String token=credential.get();
        if(token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126))return unknown;
        String keyHash=AuthService.hash(query.businessKey());HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)base.resolve("browser-results/"+keyHash).toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(timeoutMs);connection.setReadTimeout(timeoutMs);connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("X-Funnywork-Unit",query.unitId());connection.setRequestProperty("X-Funnywork-Proof",query.proofHash());
            if(connection.getResponseCode()!=200)return unknown;
            byte[] bytes;try(var stream=connection.getInputStream()){bytes=stream.readNBytes(16385);}
            if(bytes.length>16384)return unknown;
            JsonNode value;
            try {value=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);}
            catch(Exception invalid){return unknown;}
            if(value==null || !value.isObject() || value.size()!=5
                || !value.path("unitId").isTextual() || !query.unitId().equals(value.path("unitId").textValue())
                || !value.path("businessKeyHash").isTextual() || !keyHash.equals(value.path("businessKeyHash").textValue())
                || !value.path("proofHash").isTextual() || !query.proofHash().equals(value.path("proofHash").textValue())
                || !value.path("status").isTextual() || !value.path("receipt").isTextual())return unknown;
            String status=value.path("status").textValue(),receipt=value.path("receipt").textValue();
            if(!Set.of("CONFIRMED","REJECTED").contains(status) || receipt.isBlank() || receipt.length()>200 || receipt.chars().anyMatch(c->c<32 || c==127))return unknown;
            return new BrowserVerifiers.Evidence(BrowserVerifiers.Outcome.valueOf(status),query.proofHash(),receipt);
        } catch(IOException unavailable){return unknown;}
        finally {if(connection!=null)connection.disconnect();}
    }
}
