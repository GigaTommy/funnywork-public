package com.funnywork;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.*;
import java.time.Clock;
import java.util.*;

@Service
public class ModelConnections {
    public record Config(@NotNull @Pattern(regexp="(?:FUNNYWORK_MODEL_[A-Z0-9_]{1,80})?") String credentialRef,@NotNull @Min(0) Integer expectedVersion){}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Clock clock;private final ObjectMapper mapper;private final Set<String> hosts;private final org.springframework.core.env.Environment environment;
    public ModelConnections(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock,ObjectMapper mapper,@Value("${funnywork.model-probe-hosts:}") String allowed,org.springframework.core.env.Environment environment){this.environment=environment;
        this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;this.mapper=mapper;hosts=new HashSet<>();for(String host:allowed.split(","))if(!host.isBlank())hosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    private Map<String,Object> profile(Identity actor,String id){actor=auth.current(actor);actor.requireUser("ADMIN");var rows=db.queryForList("select * from model_profiles where id=? and tenant_id=?",id,actor.tenant());if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);}
    @Transactional(readOnly=true)
    public Map<String,Object> read(Identity actor,String id){profile(actor,id);var rows=db.queryForList("select credential_ref,version from model_connections where profile_id=? and tenant_id=?",id,actor.tenant());return rows.isEmpty()?Map.of("credential_ref","","version",0):rows.get(0);}
    @Transactional
    public Map<String,Object> save(Identity actor,String id,Config body){locks.lock(actor.tenant());profile(actor,id);
        if(body.expectedVersion()==0){if(!db.queryForList("select profile_id from model_connections where profile_id=?",id).isEmpty())throw ApiException.conflict("CONNECTION_VERSION_CONFLICT");db.update("insert into model_connections values(?,?,?,1,?)",id,actor.tenant(),body.credentialRef(),clock.millis());}
        else if(db.update("update model_connections set credential_ref=?,version=version+1,updated_at=? where profile_id=? and tenant_id=? and version=?",body.credentialRef(),clock.millis(),id,actor.tenant(),body.expectedVersion())!=1)throw ApiException.conflict("CONNECTION_VERSION_CONFLICT");
        auth.audit(auth.current(actor),"MODEL_CONNECTION_SAVED",Map.of("profileId",id));return Map.of("version",body.expectedVersion()+1);
    }
    /** Discovery is free of inference; explicit Responses checks use a fixed, bounded request. */
    public Map<String,Object> probe(Identity actor,String id){return probe(actor,id,false);}
    public Map<String,Object> probe(Identity actor,String id,boolean inference){
        if(inference)throw new ApiException(409,"METERED_INFERENCE_REQUIRED","Inference checks must use a budgeted model ticket");
        var p=profile(actor,id);if(!Boolean.TRUE.equals(p.get("enabled")))throw ApiException.conflict("PROFILE_DISABLED");
        URI base=URI.create((String)p.get("base_url"));if(!hosts.contains(base.getHost().toLowerCase(Locale.ROOT)))throw new ApiException(403,"PROBE_HOST_DENIED","Model probe host is not deployment-allowed");
        var settings=read(actor,id);String ref=(String)settings.get("credential_ref");String token=ref.isEmpty()?null:environment.getProperty(ref);
        if((Boolean.TRUE.equals(p.get("require_token")) || !ref.isEmpty()) && (token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126)))
            return result(actor,id,((Number)p.get("version")).intValue(),"CREDENTIAL_UNAVAILABLE",0,0,inference);
        long start=System.nanoTime();String outcome="UNREACHABLE";int status=0;HttpURLConnection connection=null;
        try {
            URI endpoint=URI.create(base.toString().replaceAll("/$","")+(inference?"/responses":"/models"));connection=(HttpURLConnection)endpoint.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(inference?15000:5000);connection.setUseCaches(false);connection.setRequestProperty("Accept","application/json");if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
            if(inference){byte[] request=mapper.writeValueAsBytes(Map.of("model",p.get("model"),"input","Reply with OK.","max_output_tokens",128,"stream",false));connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(request.length);connection.setRequestProperty("Content-Type","application/json");try(var output=connection.getOutputStream()){output.write(request);}}
            status=connection.getResponseCode();outcome=status==401||status==403?"AUTH_REJECTED":status>=300&&status<400?"REDIRECT_REJECTED":"HTTP_ERROR";
            if(status==200){try(var stream=connection.getInputStream()){byte[] body=stream.readNBytes(262145);outcome="INVALID_RESPONSE";if(body.length<=262144){JsonNode value=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);if(value!=null && inference && value.path("output").isArray()){for(JsonNode item:value.path("output"))for(JsonNode content:item.path("content"))if("output_text".equals(content.path("type").asText())&&!content.path("text").asText("").isBlank())outcome="INFERENCE_OK";}else if(value!=null && !inference && value.path("data").isArray()){outcome="MODEL_NOT_LISTED";for(JsonNode model:value.path("data"))if(p.get("model").equals(model.path("id").asText())){outcome="DISCOVERABLE";break;}}}}}
        }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){outcome="INVALID_RESPONSE";}catch(Exception failure){outcome="UNREACHABLE";}finally{if(connection!=null)connection.disconnect();}
        // Re-check authorization after remote latency before returning a result.
        profile(actor,id);return result(actor,id,((Number)p.get("version")).intValue(),outcome,status,(System.nanoTime()-start)/1000000,inference);
    }
    private Map<String,Object> result(Identity actor,String id,int version,String outcome,int status,long latency,boolean inference){
        var value=Map.<String,Object>of("profileId",id,"profileVersion",version,"outcome",outcome,"httpStatus",status,"latencyMs",latency,"observedAt",clock.millis(),"inferenceVerified",outcome.equals("INFERENCE_OK"),"checkType",inference?"INFERENCE":"CONNECTION");
        auth.audit(auth.current(actor),"MODEL_PROBED",value);return value;
    }
}
