package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:connectorhttp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}",
    "funnywork.gateway-hosts=127.0.0.1","FUNNYWORK_GATEWAY_FIXTURE=fixture-source-token","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","server.address=127.0.0.1"})
@Import(ConnectorHttpIntegrationTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ConnectorHttpIntegrationTest {
    static class Source implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger posts=new AtomicInteger(),lookups=new AtomicInteger();
        final AtomicReference<Map<?,?>> saved=new AtomicReference<>();
        Source(Json json) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/operations/",exchange->{
                if(!"Bearer fixture-source-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401,-1);exchange.close();return;
                }
                if(exchange.getRequestMethod().equals("POST")) {
                    saved.set((Map<?,?>)json.read(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
                    posts.incrementAndGet();exchange.sendResponseHeaders(503,-1);
                } else {
                    Map<?,?> value=saved.get();
                    String reply=lookups.incrementAndGet()==1?"{}":json.write(Map.of("operationId",value.get("operationId"),
                        "inputHash",value.get("inputHash"),"status","CONFIRMED","receipt","fixture-receipt"));
                    byte[] body=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }
                exchange.close();
            });server.start();
        }
        public void close(){server.stop(0);}
    }
    @TestConfiguration static class Config {
        @Bean(destroyMethod="close") Source source(Json json) throws Exception {return new Source(json);}

    }
    @Autowired AuthService auth;
    @Autowired ControlService control;
    @Autowired Source source;
    @Autowired ManagedGateways managed;
    @Autowired ToolRegistry tools;
    @LocalServerPort int port;
    @Test void pythonNodeCompletesViaHttpDespiteSourceAndNodeResponseLoss() throws Exception {
        String token=UUID.randomUUID().toString();
        auth.create("connector-http-admin","connector-http","USER","HTTP fixture",Set.of("ADMIN","OPERATOR","VIEWER"),Set.of("sandbox"),token);
        var contract=new ToolContract("fixture.record.put.v1",ToolContract.Effect.IDEMPOTENT_WRITE,
            Map.of("value",new ToolContract.Field(ToolContract.Kind.STRING,true,100)),"vault://fixture/credential","fixture.receipt.v1");
        managed.save(auth.authenticate(token),new ManagedGateways.Config("fixture.http.v1","Managed fixture","sandbox",
            "http://127.0.0.1:"+source.server.getAddress().getPort()+"/","FUNNYWORK_GATEWAY_FIXTURE",tools.fingerprint(contract),true,0));
        Path secret=Files.createTempFile("connector-fixture-",".token");
        Path log=Files.createTempFile("connector-fixture-",".log");
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        Process process=null;
        try {
            Files.writeString(secret,token);
            scheduler.scheduleWithFixedDelay(()->control.tickTenant("connector-http"),0,100,TimeUnit.MILLISECONDS);
            Path script=Path.of(System.getProperty("funnywork.http-scenarios","../scripts"),"connector_http_scenario.py").toAbsolutePath();
            process=new ProcessBuilder("python3",script.toString(),"http://127.0.0.1:"+port,secret.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(35,TimeUnit.SECONDS),"Node HTTP test timed out");
            assertEquals(0,process.exitValue(),Files.readString(log));
            assertEquals(1,source.posts.get());assertEquals(2,source.lookups.get());
        } finally {
            if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor();}
            scheduler.shutdownNow();scheduler.awaitTermination(5,TimeUnit.SECONDS);
            Files.deleteIfExists(secret);Files.deleteIfExists(log);
        }
    }
}
