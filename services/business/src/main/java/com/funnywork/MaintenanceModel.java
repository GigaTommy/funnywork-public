package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server roles use the existing admission, budget and usage ledger, without an external agent process. */
@Service
public class MaintenanceModel {
    private final ModelMiddleware middleware;
    private final ObjectMapper mapper;
    public MaintenanceModel(ModelMiddleware middleware,ObjectMapper mapper){this.middleware=middleware;this.mapper=mapper;}

    public JsonNode decide(Identity actor,String deployment,String run,String system,String input) throws Exception {
        var catalog=new ArrayList<>(middleware.catalog(actor));
        if(catalog.stream().noneMatch(d->deployment.equals(d.get("id"))))catalog.addAll(middleware.chatCatalog(actor));
        var model=catalog.stream().filter(d->deployment.equals(d.get("id"))).findFirst().orElseThrow(()->ApiException.conflict("MAINTENANCE_MODEL_UNAVAILABLE"));
        String protocol=(String)model.get("protocol");
        if(!Set.of("CHAT_COMPLETIONS","MESSAGES").contains(protocol))throw ApiException.conflict("MAINTENANCE_MODEL_PROTOCOL_UNSUPPORTED");
        int limit=(int)Math.min(4096,((Number)model.get("max_output_tokens")).longValue());
        var body=new TreeMap<String,Object>();body.put("model",model.get("model"));body.put("stream",false);body.put("max_tokens",limit);
        if(((String)model.get("model")).startsWith("deepseek"))body.put("thinking",Map.of("type","disabled"));
        if("MESSAGES".equals(protocol)){body.put("system",system);body.put("messages",List.of(Map.of("role","user","content",input)));}
        else body.put("messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",input)));
        byte[] bytes=mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(body);
        var ticket=middleware.admit(actor,new ModelMiddleware.Ticket(deployment,"maint:"+run,"maint:"+run,"","maintenance:"+run,"","","maintenance-role",ModelMiddleware.requestDigest(bytes),limit));
        String id=(String)ticket.get("id");
        if(!"GRANTED".equals(ticket.get("state")))throw ApiException.conflict("MAINTENANCE_MODEL_"+ticket.get("state"));
        var dispatch=middleware.begin(actor,id,bytes);var d=dispatch.deployment();
        var usage=new ModelUsage(mapper,protocol);String state="UNKNOWN",outcome="MAINTENANCE_INFERENCE_UNKNOWN";Long first=null;
        HttpURLConnection connection=null;boolean sent=false;
        try {
            connection=(HttpURLConnection)URI.create(((String)d.get("base_url")).replaceAll("/$","")+("MESSAGES".equals(protocol)?"/messages":"/chat/completions")).toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type","application/json");
            if("MESSAGES".equals(protocol)){connection.setRequestProperty("anthropic-version","2023-06-01");if(dispatch.credential()!=null)connection.setRequestProperty("x-api-key",dispatch.credential());}
            else if(dispatch.credential()!=null)connection.setRequestProperty("Authorization","Bearer "+dispatch.credential());
            connection.connect();sent=true;try(var output=connection.getOutputStream()){output.write(bytes);}
            int status=connection.getResponseCode();
            if(status!=200){if(status>=400&&status<500&&status!=408)state="FAILED";throw ApiException.conflict("MAINTENANCE_MODEL_HTTP_"+status);}
            byte[] result;try(var stream=connection.getInputStream()){result=stream.readNBytes(262145);}
            if(result.length>262144)throw ApiException.conflict("MAINTENANCE_MODEL_RESPONSE_TOO_LARGE");
            first=System.currentTimeMillis();usage.response(result);
            if(!usage.complete())throw ApiException.conflict("MAINTENANCE_MODEL_INCOMPLETE");
            state="SUCCEEDED";outcome="COMPLETED";
            JsonNode message=mapper.readTree(result);String content;
            if("max_tokens".equals(message.path("stop_reason").asText())||"length".equals(message.path("choices").path(0).path("finish_reason").asText()))throw ApiException.conflict("MAINTENANCE_MODEL_OUTPUT_TRUNCATED");
            if("MESSAGES".equals(protocol)){var text=new StringBuilder();for(var block:message.path("content"))if("text".equals(block.path("type").asText()))text.append(block.path("text").asText());content=text.toString();}
            else content=message.path("choices").path(0).path("message").path("content").asText();
            // No markdown fences, executable tools or inferred missing fields are accepted.
            JsonNode parsed=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
            if(parsed==null||!parsed.isObject())throw ApiException.conflict("MAINTENANCE_MODEL_INVALID_JSON");
            return parsed;
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            throw ApiException.conflict("MAINTENANCE_MODEL_INVALID_JSON");
        } catch(java.io.IOException failure){
            if(!sent)throw ApiException.conflict("MAINTENANCE_MODEL_CONNECT_FAILED");
            throw failure;
        } finally {
            if(!sent){state="FAILED";outcome="UPSTREAM_CONNECT_FAILED";}
            if(connection!=null)connection.disconnect();
            middleware.finish(id,state,outcome,usage,first);
        }
    }
}
