package com.funnywork;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Deployment-configured adapter for sources implementing the operation receipt protocol. */
public final class HttpReceiptGateway implements ExternalOperations.Gateway {
    private final URI base;
    private final Supplier<String> credential;
    private final Json json;
    private final ObjectMapper mapper;
    private final int timeoutMs;
    public HttpReceiptGateway(URI base,Supplier<String> credential,Json json,ObjectMapper mapper,int timeoutMs) {
        String host=base.getHost();
        boolean loopback="127.0.0.1".equals(host) || "[::1]".equals(host);
        if(host==null || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && loopback))
            || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
            || !base.getPath().endsWith("/") || base.getPath().contains("..") || base.getRawPath().contains("%")
            || credential==null || timeoutMs<100 || timeoutMs>30000)throw new IllegalArgumentException("Invalid source configuration");
        this.base=base;this.credential=credential;this.json=json;this.mapper=mapper;this.timeoutMs=timeoutMs;
    }
    private record Reply(int status,byte[] body) {}
    private Reply request(String id,byte[] body) throws IOException {
        if(id==null || !id.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid operation identity");
        String token=credential.get();
        if(token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126))
            throw new IOException("Source credential unavailable");
        var connection=(HttpURLConnection)base.resolve("operations/"+id).toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeoutMs);connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Accept","application/json");
        try {
            if(body!=null) {
                if(body.length>262144)throw new IOException("Source request too large");
                connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                connection.setRequestProperty("Idempotency-Key",id);
                connection.setFixedLengthStreamingMode(body.length);
                try(var output=connection.getOutputStream()){output.write(body);}
            }
            int status=connection.getResponseCode();
            if(status<200 || status>=300)return new Reply(status,new byte[0]);
            try(var input=connection.getInputStream()) {
                byte[] bytes=input.readNBytes(16385);
                if(bytes.length>16384)throw new IOException("Source response too large");
                return new Reply(status,bytes);
            }
        } finally {connection.disconnect();}
    }
    @Override public void dispatch(String id,Map<String,Object> input) throws IOException {
        String hash=AuthService.hash(json.write(input));
        byte[] body=json.write(Map.of("operationId",id,"inputHash",hash,"input",input)).getBytes(StandardCharsets.UTF_8);
        var response=request(id,body);
        if(response.status()<200 || response.status()>=300)throw new IOException("Source dispatch not acknowledged");
    }
    @Override public ExternalOperations.Evidence lookup(String id,String expectedHash) throws IOException {
        var unknown=new ExternalOperations.Evidence(ExternalOperations.Outcome.UNKNOWN,null);
        var response=request(id,null);
        if(response.status()!=200)return unknown; // Includes 404: absence is not permission to resend.
        JsonNode value;
        try {value=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());}
        catch(Exception invalid){return unknown;}
        if(value==null || !value.isObject() || value.size()!=4
            || !value.path("operationId").isTextual() || !id.equals(value.path("operationId").textValue())
            || !value.path("inputHash").isTextual() || !Objects.equals(expectedHash,value.path("inputHash").textValue())
            || !value.path("status").isTextual() || !value.path("receipt").isTextual())return unknown;
        String status=value.path("status").textValue(),receipt=value.path("receipt").textValue();
        if(!Set.of("CONFIRMED","REJECTED").contains(status) || receipt.isBlank() || receipt.length()>200
            || receipt.chars().anyMatch(c->c<32))return unknown;
        return new ExternalOperations.Evidence(ExternalOperations.Outcome.valueOf(status),receipt);
    }
}
