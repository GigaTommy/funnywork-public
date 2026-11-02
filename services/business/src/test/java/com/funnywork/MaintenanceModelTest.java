package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceModelTest {
    @Test void bothConfiguredProtocolsUseAdmissionAndCommitActualUsage()throws Exception{
        for(String protocol:List.of("MESSAGES","CHAT_COMPLETIONS")){
            var mapper=new ObjectMapper();var middleware=mock(ModelMiddleware.class);var actor=new Identity("service","tenant","USER",Set.of("OPERATOR"),Set.of("sandbox"),1);
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var observed=new java.util.concurrent.atomic.AtomicReference<String>();
            server.createContext("/",exchange->{observed.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String result="MESSAGES".equals(protocol)?"{\"type\":\"message\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"decision\\\":\\\"IGNORE\\\"}\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":8}}":"{\"choices\":[{\"message\":{\"content\":\"{\\\"decision\\\":\\\"IGNORE\\\"}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}";
                byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
            try{
                when(middleware.catalog(actor)).thenReturn(List.of(Map.of("id","deployment","model","deepseek-flash","protocol",protocol,"max_output_tokens",4096)));
                when(middleware.admit(eq(actor),any())).thenReturn(Map.of("id","ticket","state","GRANTED"));
                when(middleware.begin(eq(actor),eq("ticket"),any())).thenReturn(new ModelMiddleware.Dispatch(Map.of(),Map.of("base_url","http://127.0.0.1:"+server.getAddress().getPort()),"private-key"));
                var model=new MaintenanceModel(middleware,mapper);assertEquals("IGNORE",model.decide(actor,"deployment","run","Rules","Source").path("decision").asText());
                var body=mapper.readTree(observed.get());assertFalse(body.path("stream").asBoolean());assertEquals(4096,body.path("max_tokens").asInt());assertEquals("deepseek-flash",body.path("model").asText());assertFalse(body.has("tools"));assertEquals("disabled",body.path("thinking").path("type").asText());
                assertEquals("MESSAGES".equals(protocol),body.has("system"));
                ArgumentCaptor<ModelUsage> usage=ArgumentCaptor.forClass(ModelUsage.class);verify(middleware).finish(eq("ticket"),eq("SUCCEEDED"),eq("COMPLETED"),usage.capture(),anyLong());assertEquals(12L,usage.getValue().input());assertEquals(8L,usage.getValue().output());
            }finally{server.stop(0);}
        }
    }
    @Test void queuedModelPermitNeverStartsUnadmittedInference()throws Exception{
        var middleware=mock(ModelMiddleware.class);var actor=new Identity("service","tenant","USER",Set.of("OPERATOR"),Set.of("sandbox"),1);
        when(middleware.catalog(actor)).thenReturn(List.of(Map.of("id","d","model","deepseek-flash","protocol","MESSAGES","max_output_tokens",4096)));
        when(middleware.admit(eq(actor),any())).thenReturn(Map.of("id","ticket","state","WAITING"));
        var model=new MaintenanceModel(middleware,new ObjectMapper());assertEquals("MAINTENANCE_MODEL_WAITING",assertThrows(ApiException.class,()->model.decide(actor,"d","r","Rules","Source")).code);verify(middleware,never()).begin(any(),anyString(),any());
    }
    @Test void connectionFailureIsExplicitAndSettledAsUnsent()throws Exception{
        var middleware=mock(ModelMiddleware.class);var actor=new Identity("service","tenant","USER",Set.of("OPERATOR"),Set.of("sandbox"),1);
        var socket=new java.net.ServerSocket(0);int port=socket.getLocalPort();socket.close();
        when(middleware.catalog(actor)).thenReturn(List.of(Map.of("id","d","model","deepseek-flash","protocol","MESSAGES","max_output_tokens",4096)));
        when(middleware.admit(eq(actor),any())).thenReturn(Map.of("id","ticket","state","GRANTED"));
        when(middleware.begin(eq(actor),eq("ticket"),any())).thenReturn(new ModelMiddleware.Dispatch(Map.of(),Map.of("base_url","http://127.0.0.1:"+port),"private-key"));
        assertEquals("MAINTENANCE_MODEL_CONNECT_FAILED",assertThrows(ApiException.class,()->new MaintenanceModel(middleware,new ObjectMapper()).decide(actor,"d","r","Rules","Source")).code);
        verify(middleware).finish(eq("ticket"),eq("FAILED"),eq("UPSTREAM_CONNECT_FAILED"),any(ModelUsage.class),isNull());
    }
}
