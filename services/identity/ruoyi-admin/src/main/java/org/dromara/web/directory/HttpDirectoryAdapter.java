package org.dromara.web.directory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.core.env.Environment;
import org.dromara.common.core.exception.ServiceException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Normalized HTTPS bridge contract; customer-specific adapters implement DirectoryAdapter. */
public class HttpDirectoryAdapter implements DirectoryAdapter {
    private final DirectoryRegistry.Source source;
    private final Environment environment;
    private final HttpClient client;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public HttpDirectoryAdapter(DirectoryRegistry.Source source,Environment environment){
        this(source,environment,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    HttpDirectoryAdapter(DirectoryRegistry.Source source,Environment environment,HttpClient client){this.source=source;this.environment=environment;this.client=client;}
    @Override public String authenticate(String username,String password){
        try {
            JsonNode result=call(source.authenticationUrl(),mapper.writeValueAsString(Map.of("username",username,"password",password)),true);
            if(result==null)return null;
            if(!result.path("enabled").isBoolean()||!result.path("externalId").isTextual())throw new IllegalArgumentException();
            String subject=result.path("externalId").asText();
            if(subject.isBlank()||subject.length()>128)throw new IllegalArgumentException();
            return result.path("enabled").asBoolean()?subject:null;
        }catch(ServiceException e){throw e;}catch(Exception e){throw unavailable();}
    }
    @Override public Page users(String checkpoint,String cursor){
        try {
            JsonNode result=call(source.usersUrl()+"?checkpoint="+encode(checkpoint)+"&cursor="+encode(cursor),null,false);
            for(String field:List.of("snapshot","nextCursor","checkpoint"))if(!result.path(field).isTextual())throw new IllegalArgumentException();
            if(!result.path("users").isArray()||result.path("users").size()>500)throw new IllegalArgumentException();
            List<User> users=new ArrayList<>();
            for(JsonNode user:result.path("users")){
                for(String field:List.of("externalId","username","displayName","departmentId"))if(!user.path(field).isTextual())throw new IllegalArgumentException();
                if(!user.path("version").isIntegralNumber()||!user.path("version").canConvertToLong()||!user.path("enabled").isBoolean())throw new IllegalArgumentException();
                users.add(new User(user.path("externalId").asText(),user.path("username").asText(),user.path("displayName").asText(),user.path("departmentId").asText(),user.path("version").asLong(),user.path("enabled").asBoolean()));
            }
            return new Page(result.path("snapshot").asText(),result.path("nextCursor").asText(),result.path("checkpoint").asText(),List.copyOf(users));
        }catch(ServiceException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private JsonNode call(String url,String body,boolean login){
        String credential=environment.getProperty(source.credentialRef());
        if(credential==null||credential.isBlank()||credential.length()>8192||credential.chars().anyMatch(c->c<33||c>126))throw unavailable();
        try {
            var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(login?3:8)).header("Accept","application/json").header("Authorization","Bearer "+credential);
            var request=body==null?builder.GET().build():builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            // The response is capped by the subscriber; oversized bodies are not buffered in memory.
            var response=client.send(request,info -> boundedBody());
            if(login&&response.statusCode()==401)return null;
            if(response.statusCode()!=200||response.body().length()>1_048_576)throw unavailable();
            return mapper.readTree(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
    }
    static HttpResponse.BodySubscriber<String> boundedBody(){
        var downstream=HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        return new HttpResponse.BodySubscriber<>() {
            private java.util.concurrent.Flow.Subscription subscription;
            private long bytes;
            private boolean failed;
            public java.util.concurrent.CompletionStage<String> getBody(){return downstream.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;downstream.onSubscribe(value);}
            public void onNext(List<java.nio.ByteBuffer> buffers){
                if(failed)return;
                for(var buffer:buffers)bytes+=buffer.remaining();
                if(bytes>1_048_576){failed=true;subscription.cancel();downstream.onError(new java.io.IOException("Identity response too large"));}
                else downstream.onNext(buffers);
            }
            public void onError(Throwable error){downstream.onError(error);}
            public void onComplete(){if(!failed)downstream.onComplete();}
        };
    }
    private static ServiceException unavailable(){return new ServiceException("统一身份平台暂时不可用，请稍后重试",503);}
}
