package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.security.*;
import java.util.*;

/** Bounded opaque blobs. No file path, HTML rendering or execution is accepted here. */
@Service
public class ArtifactService {
    public record Create(@NotBlank @Size(max=120) String name,@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone,@NotNull @Size(max=2796204) String contentBase64) {}
    public record Attachment(@NotBlank @Size(max=64) String artifactId,@Min(1) int version) {}
    public record Revision(@Min(1) int baseVersion,@NotNull @Size(max=2796204) String contentBase64) {}
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final Clock clock;
    private final Json json;
    public ArtifactService(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock,Json json) {
        this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;this.json=json;
    }
    private Identity reader(Identity actor) {
        actor=auth.current(actor);
        if(!actor.kind().equals("USER") || !(actor.roles().contains("VIEWER") || actor.roles().contains("OPERATOR")))throw ApiException.forbidden();
        return actor;
    }
    private Map<String,Object> visible(Identity actor,String id,boolean write) {
        actor=reader(actor);
        var rows=db.queryForList("select * from artifacts where id=? and tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();
        var a=rows.get(0);actor.requireZone((String)a.get("zone"));
        boolean owner=actor.id().equals(a.get("owner_id"));
        if(write) {actor.requireUser("OPERATOR");if(!owner)throw ApiException.forbidden();}
        else if(!owner && db.queryForObject("select count(*) from artifact_grants where artifact_id=? and principal_id=?",Integer.class,id,actor.id())==0)throw ApiException.missing();
        return a;
    }
    private void revision(String id,int version,String content) {
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(content);}catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_BASE64","Content must be base64");}
        String name=db.queryForObject("select name from artifacts where id=?",String.class,id);
        boolean office=name!=null && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(docx|pptx|xlsx)$");
        if(bytes.length>(office?2097152:262144))throw new ApiException(413,"ARTIFACT_TOO_LARGE","Maximum Office revision is 2 MiB; other revisions are limited to 256 KiB");
        if(db.queryForObject("select coalesce(sum(v.byte_size),0) from artifact_versions v join artifacts a on a.id=v.artifact_id where a.tenant_id=(select tenant_id from artifacts where id=?)",Long.class,id)+bytes.length>67108864L)throw new ApiException(429,"ARTIFACT_BYTE_QUOTA","Tenant artifact storage exceeds 64 MiB");
        String digest;
        try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        db.update("insert into artifact_versions(artifact_id,version,content_base64,sha256,byte_size,created_at) values(?,?,?,?,?,?)",id,version,Base64.getEncoder().encodeToString(bytes),digest,bytes.length,clock.millis());
    }
    @Transactional
    public Map<String,Object> create(Identity actor,Create body) {return create(actor,body,null);}
    @Transactional
    public Map<String,Object> create(Identity actor,Create body,String key) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("OPERATOR");actor.requireZone(body.zone());
        if(key!=null) {
            if(!key.matches("[A-Za-z0-9._:-]{1,120}"))throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Invalid idempotency key");
            var old=db.queryForList("select * from artifact_requests where tenant_id=? and owner_id=? and request_key=?",actor.tenant(),actor.id(),key);
            if(!old.isEmpty()) {
                if(!old.get(0).get("request_hash").equals(AuthService.hash(json.write(body))))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
                String id=(String)old.get(0).get("artifact_id");
                if(db.queryForObject("select count(*) from artifacts where id=?",Integer.class,id)==0)throw new ApiException(410,"ARTIFACT_DELETED","Original artifact was deleted; this request key cannot recreate it");
                return Map.of("artifactId",id,"version",1);
            }
        }
        // Bound this initial database-backed store until object storage and tenant byte quotas exist.
        if(db.queryForObject("select count(*) from artifacts where tenant_id=?",Integer.class,actor.tenant())>=1000)throw new ApiException(429,"ARTIFACT_QUOTA","Artifact count limit reached");
        String id=AuthService.id();
        db.update("insert into artifacts values(?,?,?,?,?,1,?)",id,actor.tenant(),actor.id(),body.zone(),body.name(),clock.millis());
        revision(id,1,body.contentBase64());
        if(key!=null)db.update("insert into artifact_requests values(?,?,?,?,?)",actor.tenant(),actor.id(),key,AuthService.hash(json.write(body)),id);
        auth.audit(actor,"ARTIFACT_CREATED",Map.of("artifactId",id));
        return Map.of("artifactId",id,"version",1);
    }
    @Transactional
    public Map<String,Object> update(Identity actor,String id,Revision body) {
        locks.lock(actor.tenant());var a=visible(actor,id,true);
        requireMutable(id);
        int current=((Number)a.get("current_version")).intValue();
        if(current!=body.baseVersion())throw ApiException.conflict("ARTIFACT_VERSION_CONFLICT");
        if(current>=32)throw new ApiException(429,"ARTIFACT_VERSION_LIMIT","Maximum 32 revisions per artifact");
        revision(id,current+1,body.contentBase64());
        db.update("update artifacts set current_version=? where id=?",current+1,id);
        auth.audit(actor,"ARTIFACT_REVISED",Map.of("artifactId",id,"version",current+1));
        return Map.of("artifactId",id,"version",current+1);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> read(Identity actor,String id,int version) {
        var a=visible(actor,id,false);
        if(version==0)version=((Number)a.get("current_version")).intValue();
        var rows=db.queryForList("select version,content_base64,sha256,byte_size,created_at from artifact_versions where artifact_id=? and version=?",id,version);
        if(rows.isEmpty())throw ApiException.missing();
        var result=new LinkedHashMap<>(rows.get(0));result.put("name",a.get("name"));result.put("artifactId",id);
        return result;
    }
    @Transactional
    public void grant(Identity actor,String id,String principal,boolean enabled) {
        locks.lock(actor.tenant());var a=visible(actor,id,true);
        if(!enabled) {
            db.update("delete from artifact_grants where artifact_id=? and principal_id=?",id,principal);
        } else {
            var rows=db.queryForList("select * from principals where id=? and tenant_id=? and kind='USER' and enabled=true",principal,actor.tenant());
            if(rows.isEmpty())throw ApiException.missing();
            if(!json.strings((String)rows.get(0).get("zones")).contains(a.get("zone")))throw ApiException.forbidden();
            if(db.queryForObject("select count(*) from artifact_grants where artifact_id=? and principal_id=?",Integer.class,id,principal)==0)
                db.update("insert into artifact_grants values(?,?,?)",actor.tenant(),id,principal);
        }
        auth.audit(actor,enabled?"ARTIFACT_SHARED":"ARTIFACT_UNSHARED",Map.of("artifactId",id,"principalId",principal));
    }
    private Map<String,Object> ownedTask(Identity actor,String taskId) {
        actor=reader(actor);
        var rows=db.queryForList("select zone from tasks where id=? and tenant_id=? and owner_id=?",taskId,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);
    }
    @Transactional
    public void attach(Identity actor,String taskId,Attachment body) {
        locks.lock(actor.tenant());auth.current(actor).requireUser("OPERATOR");var task=ownedTask(actor,taskId);var artifact=visible(actor,body.artifactId(),true);
        if(!task.get("zone").equals(artifact.get("zone")))throw ApiException.forbidden();
        if(db.queryForObject("select count(*) from artifact_versions where artifact_id=? and version=?",Integer.class,body.artifactId(),body.version())==0)throw ApiException.missing();
        if(db.queryForObject("select count(*) from task_artifacts where task_id=? and artifact_id=? and version=?",Integer.class,taskId,body.artifactId(),body.version())==0) {
            db.update("insert into task_artifacts values(?,?,?,?,?)",actor.tenant(),taskId,body.artifactId(),body.version(),clock.millis());
            auth.audit(actor,"TASK_ARTIFACT_ATTACHED",Map.of("taskId",taskId,"artifactId",body.artifactId(),"version",body.version()));
        }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> taskArtifacts(Identity actor,String taskId) {
        ownedTask(actor,taskId);
        return db.queryForList("select l.artifact_id,l.version,l.attached_at,a.name,v.sha256,v.byte_size from task_artifacts l join artifacts a on a.id=l.artifact_id join artifact_versions v on v.artifact_id=l.artifact_id and v.version=l.version where l.tenant_id=? and l.task_id=? order by l.attached_at,l.artifact_id,l.version",actor.tenant(),taskId);
    }
    /** Reads only the immutable original model output of an owned task step. */
    @Transactional(readOnly=true)
    public Map<String,Object> taskResult(Identity actor,String taskId,String unitId) {
        ownedTask(actor,taskId);
        var rows=db.queryForList("select r.result_json from work_units u join unit_results r on r.unit_id=u.id where u.task_id=? and u.id=?",taskId,unitId);
        if(rows.isEmpty())throw ApiException.missing();
        var value=(Map<?,?>)json.read((String)rows.get(0).get("result_json"));
        if(!"MODEL_OUTPUT".equals(value.get("kind"))||!(value.get("artifactId") instanceof String id))throw ApiException.missing();
        var content=read(actor,id,1);
        if(!Objects.equals(value.get("sha256"),content.get("sha256")))throw ApiException.conflict("RESULT_CONTENT_CHANGED");
        return Map.of("artifactId",id,"version",1,"contentBase64",content.get("content_base64"),
            "sha256",content.get("sha256"),"reviewState","UNREVIEWED","businessVerified",false);
    }
    @Transactional
    public void delete(Identity actor,String id) {
        locks.lock(actor.tenant());visible(actor,id,true);
        requireMutable(id);
        db.update("delete from task_artifacts where artifact_id=?",id);
        db.update("delete from artifact_grants where artifact_id=?",id);
        db.update("delete from artifact_versions where artifact_id=?",id);
        db.update("delete from artifacts where id=?",id);
        auth.audit(actor,"ARTIFACT_DELETED",Map.of("artifactId",id));
    }
    private void requireMutable(String id) {
        if(db.queryForObject("select count(*) from annual_report_jobs where artifact_id=? and state='DELIVERED'",Integer.class,id)>0)
            throw ApiException.conflict("FORMAL_DELIVERY_PROTECTED");
    }
}
