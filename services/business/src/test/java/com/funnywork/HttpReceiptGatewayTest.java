package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HttpReceiptGatewayTest {
    HttpServer source;
    ObjectMapper mapper=new ObjectMapper();
    Json json=new Json(mapper);
    AtomicInteger posts;
    String response,location;
    int status;
    Map<?,?> sent;
    @BeforeEach void start() throws Exception {
        posts=new AtomicInteger();status=200;response="{}";location=null;
        source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/operations/",exchange->{
            if(!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401,-1);exchange.close();return;
            }
            if("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                sent=(Map<?,?>)json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            }
            if(location!=null)exchange.getResponseHeaders().set("Location",location);
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });source.start();
    }
    @AfterEach void stop(){source.stop(0);}
    URI base(){return URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/");}
    HttpReceiptGateway gateway(){return new HttpReceiptGateway(base(),()->"fixture-token",json,mapper,1000);}
    String evidence(String id,String hash,String state){return json.write(Map.of("operationId",id,"inputHash",hash,"status",state,"receipt","source-42"));}
    @Test void realHttpSubmissionAndHashBoundReceipt() throws Exception {
        var input=Map.<String,Object>of("value","private-input");String hash=AuthService.hash(json.write(input));
        gateway().dispatch("op-1",input);
        assertEquals(1,posts.get());assertEquals(hash,sent.get("inputHash"));assertEquals("op-1",sent.get("operationId"));
        response=evidence("op-1",hash,"CONFIRMED");
        assertEquals(ExternalOperations.Outcome.CONFIRMED,gateway().lookup("op-1",hash).outcome());
        assertEquals(1,posts.get());
    }
    @Test void mismatchedMalformedAndAbsentReceiptsStayUnknown() throws Exception {
        String hash="a".repeat(64);
        for(String body:List.of(evidence("other",hash,"CONFIRMED"),evidence("op","b".repeat(64),"CONFIRMED"),
            evidence("op",hash,"SUCCESS"),"{}","not json",evidence("op",hash,"CONFIRMED")+" {}")) {
            response=body;assertEquals(ExternalOperations.Outcome.UNKNOWN,gateway().lookup("op",hash).outcome());
        }
        status=404;assertEquals(ExternalOperations.Outcome.UNKNOWN,gateway().lookup("op",hash).outcome());
        status=200;response=evidence("op",hash,"REJECTED");
        assertEquals(ExternalOperations.Outcome.REJECTED,gateway().lookup("op",hash).outcome());
    }
    @Test void redirectDoesNotSendCredentialOrRepeatPost() throws Exception {
        var other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new AtomicInteger();
        other.createContext("/",e->{received.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});other.start();
        try {
            status=307;location="http://127.0.0.1:"+other.getAddress().getPort()+"/stolen";
            assertThrows(java.io.IOException.class,()->gateway().dispatch("op",Map.of("value","x")));
            assertEquals(ExternalOperations.Outcome.UNKNOWN,gateway().lookup("op","a".repeat(64)).outcome());
            assertEquals(0,received.get());assertEquals(1,posts.get());
        } finally {other.stop(0);}
    }
    @Test void limitsAndUntrustedEndpointsFailClosed() {
        for(String url:List.of("http://example.com/","https://user:pass@example.com/","https://example.com/?secret=x","https://example.com/no-trailing"))
            assertThrows(IllegalArgumentException.class,()->new HttpReceiptGateway(URI.create(url),()->"t",json,mapper,1000));
        assertThrows(IllegalArgumentException.class,()->gateway().lookup("../escape","a"));
        response="x".repeat(16385);
        assertThrows(java.io.IOException.class,()->gateway().lookup("op","a"));
        assertThrows(java.io.IOException.class,()->new HttpReceiptGateway(base(),()->"bad\nheader",json,mapper,1000).dispatch("op",Map.of()));
        assertEquals(0,posts.get());
    }
}
