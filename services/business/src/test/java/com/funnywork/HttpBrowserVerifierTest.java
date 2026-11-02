package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpBrowserVerifierTest {
    HttpServer server;URI base;ObjectMapper mapper=new ObjectMapper();
    BrowserVerifiers.Query query=new BrowserVerifiers.Query("unit-1","business-key","a".repeat(64),"{}");
    AtomicReference<String> response=new AtomicReference<>();AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger();
    List<String> methods=new ArrayList<>(),credentials=new ArrayList<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();methods.add(exchange.getRequestMethod());credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("/browser-results/"+AuthService.hash(query.businessKey()),exchange.getRequestURI().toString());
            assertEquals(query.unitId(),exchange.getRequestHeaders().getFirst("X-Funnywork-Unit"));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            if(status.get()==302)exchange.getResponseHeaders().set("Location",base+"unexpected");
            exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");response.set(mapper.writeValueAsString(receipt()));
    }
    @AfterEach void stop(){server.stop(0);}
    Map<String,Object> receipt(){return new HashMap<>(Map.of("unitId",query.unitId(),"businessKeyHash",AuthService.hash(query.businessKey()),"proofHash",query.proofHash(),"status","CONFIRMED","receipt","source-receipt"));}
    HttpBrowserVerifier verifier(){return new HttpBrowserVerifier(base,()->"readonly-token",mapper,1000);}
    @Test void getOnlyAndCredentialRefreshConfirmMatchingEvidence() {
        var token=new AtomicReference<>("first-token");var verifier=new HttpBrowserVerifier(base,token::get,mapper,1000);
        assertEquals(BrowserVerifiers.Outcome.CONFIRMED,verifier.lookup(query).outcome());token.set("second-token");
        assertEquals(BrowserVerifiers.Outcome.CONFIRMED,verifier.lookup(query).outcome());
        assertEquals(List.of("GET","GET"),methods);assertEquals(List.of("Bearer first-token","Bearer second-token"),credentials);
    }
    @Test void mismatchedIdentityProofAndMalformedResponsesStayUnknown()throws Exception {
        for(String field:List.of("unitId","businessKeyHash","proofHash","status","receipt")) {
            var invalid=receipt();invalid.put(field,field.equals("receipt")?"":"wrong");response.set(mapper.writeValueAsString(invalid));
            assertEquals(BrowserVerifiers.Outcome.UNKNOWN,verifier().lookup(query).outcome());
        }
        for(String invalid:List.of("null","{}","{\"status\":1,\"status\":2}",mapper.writeValueAsString(receipt())+" {}","x".repeat(16385))) {
            response.set(invalid);assertEquals(BrowserVerifiers.Outcome.UNKNOWN,verifier().lookup(query).outcome());
        }
    }
    @Test void redirectsMissingAndErrorNeverConfirmOrFollow() {
        for(int code:List.of(302,404,500)) {status.set(code);assertEquals(BrowserVerifiers.Outcome.UNKNOWN,verifier().lookup(query).outcome());}
        assertEquals(3,calls.get());assertEquals(List.of("GET","GET","GET"),methods);
    }
    @Test void invalidConfigurationAndCredentialsNeverSendRequests() {
        for(String uri:List.of("http://internal/","https://user@internal/","https://internal/path","https://internal/%2e/","https://internal/?q=1"))
            assertThrows(IllegalArgumentException.class,()->new HttpBrowserVerifier(URI.create(uri),()->"x",mapper,1000));
        assertEquals(BrowserVerifiers.Outcome.UNKNOWN,new HttpBrowserVerifier(base,()->"bad\ncredential",mapper,1000).lookup(query).outcome());
        assertEquals(0,calls.get());
    }
}
