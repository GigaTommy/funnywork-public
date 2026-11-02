package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;

/** Bounded IO workers, no waiting requests held open and no automatic upstream retries. */
@Service
public class ModelInferenceProxy {
    private final ModelMiddleware middleware;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog;
    public ModelInferenceProxy(ModelMiddleware middleware,ObjectMapper mapper,
                               @Value("${funnywork.model-inference-workers:16}") int count){
        if(count<1||count>256)throw new IllegalArgumentException("Model inference workers must be 1..256");
        this.middleware=middleware;this.mapper=mapper;
        workers=new ThreadPoolExecutor(count,count,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"model-inference");t.setDaemon(true);return t;});
        watchdog=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"model-inference-watchdog");t.setDaemon(true);return t;});
    }
    @PreDestroy void close(){workers.shutdownNow();watchdog.shutdownNow();}
    public interface Observer { void bytes(byte[] value,int count); void finished(String state); default boolean continueAfterDisconnect(){return false;} default void complete(String state,Runnable commitLedger){commitLedger.run();finished(state);} }
    public void execute(Identity actor,String id,byte[] body,HttpServletRequest request,HttpServletResponse response){execute(actor,id,body,request,response,null);}
    public void execute(Identity actor,String id,byte[] body,HttpServletRequest request,HttpServletResponse response,Observer observer){
        // Only whitelisted protocol headers may cross the boundary; credentials never do.
        String version=header(request,"anthropic-version"),beta=header(request,"anthropic-beta");
        AsyncContext async=request.startAsync();async.setTimeout(0);
        try{workers.execute(()->{
            try{forward(actor,id,body,version,beta,(HttpServletResponse)async.getResponse(),observer);}
            catch(ApiException e){error((HttpServletResponse)async.getResponse(),e.status,e.code);}
            catch(Exception e){error((HttpServletResponse)async.getResponse(),503,"MODEL_EXECUTION_UNAVAILABLE");}
            finally{complete(async);}
        });}catch(RejectedExecutionException e){error(response,503,"MODEL_PROXY_BUSY");complete(async);}
    }
    // The container may already complete a stream after the client disconnects.
    private void complete(AsyncContext async){try{async.complete();}catch(IllegalStateException alreadyCompleted){}}
    private String header(HttpServletRequest request,String name){String value=request.getHeader(name);if(value!=null&&(value.length()>1024||value.chars().anyMatch(c->c<32||c>126)))throw new ApiException(400,"INVALID_PROTOCOL_HEADER","Invalid protocol header");return value;}
    private void error(HttpServletResponse response,int status,String code){try{if(!response.isCommitted()){response.reset();response.setStatus(status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");mapper.writeValue(response.getOutputStream(),Map.of("code",code));}}catch(IOException ignored){}}
    private void forward(Identity actor,String id,byte[] bytes,String version,String beta,HttpServletResponse response,Observer observer)throws Exception{
        var dispatch=middleware.begin(actor,id,bytes);var d=dispatch.deployment();String protocol=(String)d.get("protocol");
        var usage=new ModelUsage(mapper,protocol);Long first=null;String state="UNKNOWN",outcome="UPSTREAM_UNCONFIRMED";
        HttpURLConnection connection=null;ScheduledFuture<?> cancellation=null;boolean connected=false;
        try{
            String path="MESSAGES".equals(protocol)?"/messages":"/chat/completions";
            URI url=URI.create(((String)d.get("base_url")).replaceAll("/$","")+path);
            connection=(HttpURLConnection)url.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setConnectTimeout(5000);connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json, text/event-stream");
            if(dispatch.credential()!=null){if("MESSAGES".equals(protocol))connection.setRequestProperty("x-api-key",dispatch.credential());else connection.setRequestProperty("Authorization","Bearer "+dispatch.credential());}
            if("MESSAGES".equals(protocol)){connection.setRequestProperty("anthropic-version",version==null?"2023-06-01":version);if(beta!=null)connection.setRequestProperty("anthropic-beta",beta);}
            final HttpURLConnection active=connection;long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
            cancellation=watchdog.scheduleWithFixedDelay(()->{
                try{if(System.nanoTime()>=deadline||middleware.stopRequested(id))active.disconnect();}
                catch(Exception failure){active.disconnect();} // Fail closed on loss of admission state.
            },1,1,TimeUnit.SECONDS);
            // Establish TCP/TLS before any POST headers or body may be sent.
            connection.connect();connected=true;
            try(var output=connection.getOutputStream()){output.write(bytes);}
            int status=connection.getResponseCode();
            if(status!=200){outcome="UPSTREAM_HTTP_"+status;
                // Only an explicit rejection is evidence that no inference is running.
                if(status>=400&&status<500&&status!=408){state="FAILED";}error(response,502,outcome);return;}
            boolean stream=mapper.readTree(bytes).path("stream").asBoolean(false);
            String contentType=connection.getContentType();
            if(stream&&(contentType==null||!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream"))){outcome="INVALID_UPSTREAM_CONTENT_TYPE";error(response,502,outcome);return;}
            response.setStatus(200);response.setContentType(stream?"text/event-stream":"application/json");response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");response.setHeader("X-FunnyWorker-Call",id);
            try(var input=connection.getInputStream()){
                if(stream){byte[] buffer=new byte[8192];int size;boolean delivering=true;
                    while((size=input.read(buffer))!=-1){if(first==null)first=System.currentTimeMillis();usage.bytes(buffer,size);if(observer!=null)observer.bytes(buffer,size);
                        if(delivering)try{response.getOutputStream().write(buffer,0,size);response.flushBuffer();}
                        catch(IOException|IllegalStateException disconnected){if(observer==null||!observer.continueAfterDisconnect())throw disconnected;delivering=false;}
                    }
                }
                else{byte[] result=input.readNBytes(4194305);if(result.length>4194304){outcome="UPSTREAM_RESPONSE_TOO_LARGE";error(response,502,outcome);return;}first=System.currentTimeMillis();usage.response(result);response.getOutputStream().write(result);}
            }
            if(usage.complete()){state="SUCCEEDED";outcome="COMPLETED";}else outcome="UPSTREAM_STREAM_INCOMPLETE";
        }catch(IOException failure){if(!connected){state="FAILED";outcome="UPSTREAM_CONNECT_FAILED";}else if(usage.complete()){state="SUCCEEDED";outcome="COMPLETED_DELIVERY_INTERRUPTED";}else outcome="UPSTREAM_OR_CLIENT_DISCONNECTED";error(response,502,outcome);}
        finally{
            if(cancellation!=null)cancellation.cancel(false);if(connection!=null)connection.disconnect();
            // Failure to commit leaves RUNNING durable, later classified UNKNOWN, never freed blindly.
            final String finalState=state,finalOutcome=outcome;final Long finalFirst=first;
            Runnable commitLedger=()->middleware.finish(id,finalState,finalOutcome,usage,finalFirst);
            if(observer==null)commitLedger.run();else observer.complete(state,commitLedger);
        }
    }
}
