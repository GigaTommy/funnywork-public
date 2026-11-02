package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Immutable settings; secret values and signing material stay deployment-owned. */
@Service
public class DesktopModelReleases {
    public record Settings(@NotNull @Min(1) Long expectedVersion,
        @NotNull @Pattern(regexp="https://api\\.deepseek\\.com(?:/v1)?") String endpoint,
        @NotNull @Pattern(regexp="[A-Za-z0-9_.-]{1,100}") String model,
        @NotNull @Pattern(regexp="enabled|disabled") String thinking,
        @NotNull @Min(128) @Max(ModelDefaults.MAX_OUTPUT_TOKENS) Integer maxTokens,
        @NotNull @Pattern(regexp="DEPLOYMENT_DEFAULT|FUNNYWORK_MODEL_[A-Z0-9_]{1,80}") String credentialRef) {}
    public record Rollback(@NotNull @Min(1) Long expectedVersion,@NotNull @Min(1) Long targetVersion) {}
    private final Path file;private final AuthService auth;private final ObjectMapper mapper;private final Clock clock;
    private final JdbcTemplate db;private final TenantLocks locks;private final Environment environment;
    public DesktopModelReleases(@Value("${funnywork.desktop-model-file:}") String file,AuthService auth,ObjectMapper mapper,Clock clock,JdbcTemplate db,TenantLocks locks,Environment environment){
        this.file=file.isBlank()?null:Path.of(file);this.auth=auth;this.mapper=mapper;this.clock=clock;this.db=db;this.locks=locks;this.environment=environment;
    }
    private DesktopModelPublication.Configuration source(String tenant){
        if(file==null)throw unavailable();
        try {
            var value=mapper.readValue(Files.readAllBytes(file),DesktopModelPublication.Configuration.class);
            if(!tenant.equals(value.tenant()))throw ApiException.forbidden();
            if(value.version()<1||value.signingKey()==null||value.signingPublicKey()==null)throw unavailable();
            var key=java.security.KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(value.signingKey())));
            byte[] raw=Base64.getDecoder().decode(value.signingPublicKey());if(raw.length!=32)throw unavailable();
            byte[] encoded=new byte[44];System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"),0,encoded,0,12);System.arraycopy(raw,0,encoded,12,32);
            var publicKey=java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
            var signer=java.security.Signature.getInstance("Ed25519");signer.initSign(key);signer.update(new byte[]{1});byte[] signature=signer.sign();
            signer.initVerify(publicKey);signer.update(new byte[]{1});if(!signer.verify(signature))throw unavailable();
            return value;
        }catch(ApiException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static ApiException unavailable(){return new ApiException(503,"MODEL_NOT_CONFIGURED","Desktop model configuration is unavailable");}
    private Map<String,Object> latest(String tenant){var rows=db.queryForList("select * from desktop_model_releases where tenant_id=? order by version desc limit 1",tenant);return rows.isEmpty()?null:rows.get(0);}
    private Settings settings(Map<String,Object> row){return new Settings(((Number)row.get("version")).longValue(),(String)row.get("endpoint"),(String)row.get("model"),(String)row.get("thinking"),((Number)row.get("max_tokens")).intValue(),(String)row.get("credential_ref"));}
    private Settings initial(DesktopModelPublication.Configuration c){return new Settings(c.version(),c.endpoint(),c.model(),c.thinking(),c.maxTokens(),"DEPLOYMENT_DEFAULT");}
    private void validate(Settings s){
        if(s.expectedVersion()==null||s.expectedVersion()<1||s.expectedVersion()>9007199254740991L||s.endpoint()==null||!Set.of("https://api.deepseek.com","https://api.deepseek.com/v1").contains(s.endpoint())
            ||s.model()==null||!s.model().matches("[A-Za-z0-9_.-]{1,100}")||s.thinking()==null||!Set.of("enabled","disabled").contains(s.thinking())||s.maxTokens()==null||s.maxTokens()<128||s.maxTokens()>ModelDefaults.MAX_OUTPUT_TOKENS
            ||s.credentialRef()==null||!s.credentialRef().matches("DEPLOYMENT_DEFAULT|FUNNYWORK_MODEL_[A-Z0-9_]{1,80}"))throw new ApiException(400,"INVALID_MODEL_RELEASE","Invalid desktop model settings");
    }
    private String credential(Settings s,DesktopModelPublication.Configuration c){
        String value=s.credentialRef().equals("DEPLOYMENT_DEFAULT")?c.apiKey():environment.getProperty(s.credentialRef());
        if(value==null||value.isBlank()||value.length()>8192||value.chars().anyMatch(ch->ch<33||ch>126))throw new ApiException(503,"MODEL_CREDENTIAL_UNAVAILABLE","Configured model credential is unavailable");
        return value;
    }
    private Identity administrator(Identity actor){
        actor=auth.current(actor);actor.requireUser("ADMIN");
        // This release affects the entire tenant, including users outside an administrator's zones.
        for(String scope:db.queryForList("select zones from principals where tenant_id=? and kind='USER'",String.class,actor.tenant())){
            try {if(!actor.zones().containsAll(Arrays.asList(mapper.readValue(scope,String[].class))))throw ApiException.forbidden();}
            catch(ApiException e){throw e;}catch(Exception e){throw ApiException.forbidden();}
        }
        return actor;
    }
    /** Non-secret target version for scoped device inventory; unavailable deployment configuration remains unknown. */
    Long targetVersion(Identity actor){
        actor=auth.current(actor);if(!actor.kind().equals("USER"))throw ApiException.forbidden();var row=latest(actor.tenant());if(row!=null)return ((Number)row.get("version")).longValue();
        try{var initial=initial(source(actor.tenant()));validate(initial);return initial.expectedVersion();}
        catch(ApiException unavailable){if(Set.of("MODEL_NOT_CONFIGURED","POLICY_DENIED","INVALID_MODEL_RELEASE").contains(unavailable.code))return null;throw unavailable;}
    }
    public DesktopModelPublication.Configuration resolve(Identity actor){
        actor=auth.current(actor);if(!actor.kind().equals("USER"))throw ApiException.forbidden();
        var c=source(actor.tenant());var row=latest(actor.tenant());var s=row==null?initial(c):settings(row);validate(s);
        return new DesktopModelPublication.Configuration(c.tenant(),c.signingKey(),c.signingPublicKey(),s.endpoint(),s.model(),credential(s,c),s.thinking(),s.maxTokens(),s.expectedVersion());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> read(Identity actor){
        actor=administrator(actor);var c=source(actor.tenant());var row=latest(actor.tenant());var s=row==null?initial(c):settings(row);validate(s);
        boolean ready=true;try{credential(s,c);}catch(ApiException e){ready=false;}
        return Map.of("settings",s,"source",row==null?"DEPLOYMENT_FILE":"PUBLISHED","scope","TENANT","credentialReady",ready,
            "checks",db.queryForList("select check_type,outcome,http_status,latency_ms,observed_at,settings_json from desktop_model_checks where tenant_id=? order by observed_at desc limit 20",actor.tenant()),
            "history",db.queryForList("select * from desktop_model_releases where tenant_id=? order by version desc limit 100",actor.tenant()));
    }
    DesktopModelPublication.Configuration forCheck(Identity actor,Settings s){
        actor=administrator(actor);validate(s);var c=source(actor.tenant());
        return new DesktopModelPublication.Configuration(c.tenant(),c.signingKey(),c.signingPublicKey(),s.endpoint(),s.model(),credential(s,c),s.thinking(),s.maxTokens(),s.expectedVersion());
    }
    @Transactional
    public void recordCheck(Identity actor,Settings s,String type,String outcome,int status,long latency){
        locks.lock(actor.tenant());actor=administrator(actor);
        String settings;try{settings=mapper.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException("Cannot serialize model settings");}
        db.update("insert into desktop_model_checks values(?,?,?,?,?,?,?,?,?)",AuthService.id(),actor.tenant(),actor.id(),settings,type,outcome,status,latency,clock.millis());
        auth.audit(actor,"DESKTOP_MODEL_CHECKED",Map.of("type",type,"outcome",outcome,"model",s.model(),"httpStatus",status,"latencyMs",latency));
    }
    @Transactional
    public Map<String,Object> publish(Identity actor,Settings settings){locks.lock(actor.tenant());actor=administrator(actor);return append(actor,settings,null);}
    @Transactional
    public Map<String,Object> rollback(Identity actor,Rollback request){
        locks.lock(actor.tenant());actor=administrator(actor);
        var rows=db.queryForList("select * from desktop_model_releases where tenant_id=? and version=?",actor.tenant(),request.targetVersion());if(rows.isEmpty())throw ApiException.missing();
        var old=settings(rows.get(0));return append(actor,new Settings(request.expectedVersion(),old.endpoint(),old.model(),old.thinking(),old.maxTokens(),old.credentialRef()),request.targetVersion());
    }
    private Map<String,Object> append(Identity actor,Settings s,Long restoredFrom){
        validate(s);var c=source(actor.tenant());var row=latest(actor.tenant());long current=row==null?c.version():((Number)row.get("version")).longValue();
        if(s.expectedVersion()!=current)throw ApiException.conflict("MODEL_RELEASE_VERSION_CONFLICT");
        if(current>=9007199254740991L)throw ApiException.conflict("MODEL_RELEASE_VERSION_EXHAUSTED");credential(s,c);
        // Capture the legacy baseline so the first managed publication can be rolled back as well.
        if(row==null){var baseline=initial(c);validate(baseline);insert(actor,baseline,current,null,"BASELINE");}
        long version=current+1;insert(actor,s,version,restoredFrom,"PUBLISHED");
        auth.audit(actor,"DESKTOP_MODEL_PUBLISHED",Map.of("version",version,"model",s.model(),"scope","TENANT","restoredFrom",restoredFrom==null?0:restoredFrom));
        return Map.of("version",version,"state","PUBLISHED","applicationState","UNKNOWN");
    }
    private void insert(Identity actor,Settings s,long version,Long restoredFrom,String origin){db.update("insert into desktop_model_releases values(?,?,?,?,?,?,?,?,?,?,?)",actor.tenant(),version,s.endpoint(),s.model(),s.thinking(),s.maxTokens(),s.credentialRef(),actor.id(),clock.millis(),restoredFrom,origin);}
}
