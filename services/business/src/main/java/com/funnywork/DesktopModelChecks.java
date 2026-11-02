package com.funnywork;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only the desktop protocol's fixed official destination is accepted by the release service. */
@Service
public class DesktopModelChecks {
    record Response(int status,byte[] body) {}
    interface Transport {Response send(String endpoint,String token,byte[] body)throws Exception;}
    private final DesktopModelReleases releases;private final ObjectMapper mapper;private final Transport transport;
    @Autowired public DesktopModelChecks(DesktopModelReleases releases,ObjectMapper mapper){this(releases,mapper,DesktopModelChecks::send);}
    DesktopModelChecks(DesktopModelReleases releases,ObjectMapper mapper,Transport transport){this.releases=releases;this.mapper=mapper;this.transport=transport;}
    private static Response send(String endpoint,String token,byte[] body)throws Exception {
        var connection=(HttpURLConnection)URI.create(endpoint).toURL().openConnection(Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(15000);connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);connection.setRequestProperty("Content-Type","application/json");try(var output=connection.getOutputStream()){output.write(body);}}
            int status=connection.getResponseCode();if(status!=200)return new Response(status,new byte[0]);
            try(var input=connection.getInputStream()){return new Response(status,input.readNBytes(262145));}
        }finally{connection.disconnect();}
    }
    public Map<String,Object> check(Identity actor,DesktopModelReleases.Settings settings,boolean inference){
        if(inference)throw new ApiException(409,"METERED_INFERENCE_REQUIRED","Inference checks must use a budgeted model ticket");
        var config=releases.forCheck(actor,settings);long start=System.nanoTime();int status=0;String outcome="UNREACHABLE";
        try {
            byte[] request=inference?mapper.writeValueAsBytes(Map.of("model",config.model(),"max_tokens",128,"stream",false,"thinking",Map.of("type",config.thinking()),"messages",List.of(Map.of("role","user","content","Reply with OK.")))):null;
            var response=transport.send(config.endpoint()+(inference?"/chat/completions":"/models"),config.apiKey(),request);status=response.status();
            outcome=status==401||status==403?"AUTH_REJECTED":status>=300&&status<400?"REDIRECT_REJECTED":"HTTP_ERROR";
            if(status==200){
                outcome="INVALID_RESPONSE";
                if(response.body().length<=262144){
                    JsonNode body=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
                    if(body!=null&&inference){var message=body.path("choices").path(0).path("message");if(!message.path("content").asText("").isBlank()||!message.path("reasoning_content").asText("").isBlank())outcome="INFERENCE_OK";}
                    else if(body!=null&&body.path("data").isArray()){outcome="MODEL_NOT_LISTED";for(var model:body.path("data"))if(config.model().equals(model.path("id").asText())){outcome="DISCOVERABLE";break;}}
                }
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){outcome="INVALID_RESPONSE";}
        catch(Exception unavailable){outcome="UNREACHABLE";}
        long latency=(System.nanoTime()-start)/1000000;String type=inference?"INFERENCE":"CONNECTION";
        // Revalidate scope after remote latency, then store only safe metadata, never model output.
        releases.recordCheck(actor,settings,type,outcome,status,latency);
        return Map.of("type",type,"outcome",outcome,"httpStatus",status,"latencyMs",latency,"inferenceVerified",outcome.equals("INFERENCE_OK"));
    }
}
