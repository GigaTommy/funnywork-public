package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Incremental GitHub reads happen outside database locks; durable source keys absorb overlap and retries. */
@Service
public class MaintenanceGithub {
    private final JdbcTemplate db;private final MaintenanceIntake intake;private final AuthService auth;private final Environment env;private final ObjectMapper mapper;
    private final HttpClient client;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"maintenance-github");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired public MaintenanceGithub(JdbcTemplate db,MaintenanceIntake intake,AuthService auth,Environment env,ObjectMapper mapper){this(db,intake,auth,env,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());}
    MaintenanceGithub(JdbcTemplate db,MaintenanceIntake intake,AuthService auth,Environment env,ObjectMapper mapper,HttpClient client){this.db=db;this.intake=intake;this.auth=auth;this.env=env;this.mapper=mapper;this.client=client;}
    @PreDestroy void close(){worker.shutdownNow();}
    @Scheduled(fixedDelayString="${funnywork.maintenance-github-poll-ms:60000}") public void poll(){
        if(!env.getProperty("funnywork.scheduling-enabled",Boolean.class,true)||!running.compareAndSet(false,true))return;
        worker.submit(()->{try{for(var c:db.queryForList("select c.*,r.slug,r.enabled from maintenance_intake_configs c join maintenance_repositories r on r.id=c.repository_id where c.github_enabled=true and r.enabled=true"))read(c);}finally{running.set(false);}});
    }
    public Object sync(Identity actor,String id){actor=auth.current(actor);actor.requireUser("ADMIN");var rows=db.queryForList("select c.*,r.slug,r.zone,r.enabled from maintenance_intake_configs c join maintenance_repositories r on r.id=c.repository_id where c.repository_id=? and c.tenant_id=? and c.github_enabled=true and r.enabled=true",id,actor.tenant());if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return read(rows.get(0));}
    private Object read(Map<String,Object> c){
        String repo=(String)c.get("repository_id"),tenant=(String)c.get("tenant_id"),since=(String)c.get("github_since");String latest=since;int count=0;
        try{
            String tokenRef=(String)c.get("github_token_ref");String token=tokenRef.isEmpty()?null:env.getProperty(tokenRef);if(!tokenRef.isEmpty()&&(token==null||token.isBlank()))throw new IllegalStateException("GITHUB_CREDENTIAL_UNAVAILABLE");
            for(int page=1;page<=100;page++){
                String query="state=all&sort=updated&direction=asc&per_page=50&page="+page+"&since="+URLEncoder.encode(Instant.parse(since).minusSeconds(1).toString(),StandardCharsets.UTF_8);
                var request=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+c.get("slug")+"/issues?"+query)).timeout(Duration.ofSeconds(15)).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","FunnyWork-maintenance");if(token!=null)request.header("Authorization","Bearer "+token);
                var response=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;try(var body=response.body()){bytes=body.readNBytes(2000001);}if(response.statusCode()!=200||bytes.length>2000000)throw new IllegalStateException("GITHUB_READ_UNAVAILABLE");var items=mapper.readTree(bytes);if(!items.isArray())throw new IllegalStateException("GITHUB_INVALID_RESPONSE");
                for(var issue:items){String updated=issue.path("updated_at").asText();try{Instant.parse(updated);}catch(java.time.DateTimeException invalid){if(!issue.has("pull_request"))intake.quarantine(tenant,repo,issue,"INVALID_GITHUB_REVISION");continue;}if(Instant.parse(updated).isAfter(Instant.parse(latest)))latest=updated;if(!issue.has("pull_request")){try{intake.github(tenant,repo,issue);intake.sourceResolved(tenant,repo,issue.path("number").asText());count++;}
                    catch(ApiException invalid){if(!Set.of("INVALID_FEEDBACK","INVALID_GITHUB_ISSUE","INVALID_GITHUB_REVISION").contains(invalid.code))throw invalid;intake.quarantine(tenant,repo,issue,invalid.code);}}}
                if(items.size()<50){db.update("update maintenance_intake_configs set github_since=?,github_error=null where repository_id=? and github_since=?",latest,repo,since);return Map.of("imported",count);}
            }
            throw new IllegalStateException("GITHUB_PAGE_LIMIT");
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();String code=failure instanceof IllegalStateException?failure.getMessage():"GITHUB_SYNC_FAILED";if(code==null||!code.matches("GITHUB_[A-Z_]+"))code="GITHUB_SYNC_FAILED";db.update("update maintenance_intake_configs set github_error=? where repository_id=?",code,repo);return Map.of("imported",count,"error",code);}
    }
}
