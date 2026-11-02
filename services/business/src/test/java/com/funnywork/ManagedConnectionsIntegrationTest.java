package com.funnywork;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:managed-connections;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.gateway-hosts=127.0.0.1","funnywork.model-probe-hosts=127.0.0.1","FUNNYWORK_GATEWAY_FIXTURE=fixture-secret","FUNNYWORK_MODEL_FIXTURE=fixture-model"})
class ManagedConnectionsIntegrationTest {
    @Autowired ManagedGateways gateways;@Autowired GatewayRegistry registry;@Autowired ToolRegistry tools;
    @Autowired ModelProfiles profiles;@Autowired ModelConnections connections;@Autowired AuthService auth;
    Identity admin,other,owner;
    @BeforeEach void setup(){String tenant=AuthService.id();admin=user(tenant,"ADMIN");owner=user(tenant,"OPERATOR");other=user(AuthService.id(),"ADMIN");}
    Identity user(String tenant,String role){String secret=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),secret);return auth.authenticate(secret);}
    ToolContract contract(){return new ToolContract("fixture.managed.put",ToolContract.Effect.IDEMPOTENT_WRITE,Map.of(),"vault://fixture/source","fixture.receipt");}
    ManagedGateways.Config config(String id,String url,boolean enabled,int version){return new ManagedGateways.Config(id,"Gateway","sandbox",url,"FUNNYWORK_GATEWAY_FIXTURE",tools.fingerprint(contract()),enabled,version);}
    @Test void versionIsolationImmutableDestinationAndImmediateDisable(){
        gateways.save(admin,config("managed","http://127.0.0.1:8090/",true,0));
        assertEquals(1,((List<?>)gateways.list(admin,"sandbox","",50).get("items")).size());assertEquals(0,((List<?>)gateways.list(other,"sandbox","",50).get("items")).size());
        assertEquals(403,assertThrows(ApiException.class,()->gateways.list(owner,"sandbox","",50)).status);
        assertEquals(409,assertThrows(ApiException.class,()->gateways.save(admin,config("managed","http://127.0.0.1:8090/",true,0))).status);
        assertEquals(409,assertThrows(ApiException.class,()->gateways.save(admin,config("managed","http://127.0.0.1:8091/",true,1))).status);
        assertEquals(400,assertThrows(ApiException.class,()->gateways.save(admin,config("bad","https://unapproved.example/",true,0))).status);
        assertEquals(403,assertThrows(ApiException.class,()->registry.resolve(admin.tenant(),"managed",contract(),"other")).status);
        assertEquals(403,assertThrows(ApiException.class,()->registry.resolve(other.tenant(),"managed",contract(),"sandbox")).status);
        var frozen=registry.resolve(admin.tenant(),"managed",contract(),"sandbox");gateways.save(admin,config("managed","http://127.0.0.1:8090/",false,1));
        assertThrows(ApiException.class,()->frozen.dispatch("operation",Map.of()));
        assertEquals(403,assertThrows(ApiException.class,()->registry.resolve(admin.tenant(),"managed",contract(),"sandbox")).status);
    }
    @Test void realModelDiscoveryCredentialsAndSanitizedErrors()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/models",exchange->{assertEquals("Bearer fixture-model",exchange.getRequestHeaders().getFirst("Authorization"));byte[] bytes="{\"data\":[{\"id\":\"fixture\"}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{String id=(String)profiles.save(admin,null,new ModelProfiles.Profile("Test","sandbox","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",true,true,0)).get("id");
            assertEquals("CREDENTIAL_UNAVAILABLE",connections.probe(admin,id).get("outcome"));
            connections.save(admin,id,new ModelConnections.Config("FUNNYWORK_MODEL_FIXTURE",0));
            var result=connections.probe(admin,id);assertEquals("DISCOVERABLE",result.get("outcome"));assertEquals(false,result.get("inferenceVerified"));assertFalse(result.toString().contains("fixture-model"));
            assertEquals(404,assertThrows(ApiException.class,()->connections.read(other,id)).status);assertEquals(403,assertThrows(ApiException.class,()->connections.probe(owner,id)).status);
            assertEquals(409,assertThrows(ApiException.class,()->connections.save(admin,id,new ModelConnections.Config("",0))).status);
            server.removeContext("/v1/models");server.createContext("/v1/models",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:9/");exchange.sendResponseHeaders(302,-1);exchange.close();});
            assertEquals("REDIRECT_REJECTED",connections.probe(admin,id).get("outcome"));
            profiles.save(admin,id,new ModelProfiles.Profile("Test","sandbox","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",true,false,1));
            assertEquals(409,assertThrows(ApiException.class,()->connections.probe(admin,id)).status);
        }finally{server.stop(0);}
    }
    @Test void responsesProbeCannotBypassTheModelBudget() {
        var error=assertThrows(ApiException.class,()->connections.probe(admin,"unmetered",true));
        assertEquals(409,error.status);assertEquals("METERED_INFERENCE_REQUIRED",error.code);
    }
}
