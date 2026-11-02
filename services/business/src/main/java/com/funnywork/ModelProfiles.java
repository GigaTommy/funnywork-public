package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Central provider metadata. Secrets remain on the explicitly configured model node. */
@Service
public class ModelProfiles {
    public record Profile(@NotBlank @Size(max=120) String name,
                          @NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone,
                          @NotBlank @Size(max=120) String model,
                          @NotBlank @Size(max=1000) String baseUrl,
                          @NotNull Boolean requireToken,@NotNull Boolean enabled,
                          @NotNull @Min(0) Integer expectedVersion) {}
    public record Restore(@NotNull @Min(1) Integer expectedVersion,@NotNull @Min(1) Integer targetVersion) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Clock clock;
    public ModelProfiles(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock){this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;}
    private void validate(Profile profile){
        try {
            URI url=URI.create(profile.baseUrl());
            String host=url.getHost();
            boolean local=Set.of("127.0.0.1","[::1]","::1").contains(host==null?"":host);
            if(host==null || url.getUserInfo()!=null || url.getRawQuery()!=null || url.getRawFragment()!=null || url.getPort()>65535 ||
               !("https".equals(url.getScheme()) || "http".equals(url.getScheme())&&local) ||
               profile.model().chars().anyMatch(c->c<32) || profile.name().chars().anyMatch(c->c<32))throw new IllegalArgumentException();
        } catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_PROVIDER","Provider requires HTTPS and no embedded credentials, query or fragment");}
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(Identity actor,String zone,String after,int limit){
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        if(after==null || after.length()>64 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid profile page");
        var rows=db.queryForList("select * from model_profiles where tenant_id=? and zone=? and id>? order by id limit ?",actor.tenant(),zone,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    @Transactional
    public Map<String,Object> save(Identity actor,String id,Profile body){return saveVersion(actor,id,body,null);}
    private Map<String,Object> saveVersion(Identity actor,String id,Profile body,Integer restoredFrom){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(body.zone());validate(body);
        if(body.expectedVersion()==Integer.MAX_VALUE)throw ApiException.conflict("PROFILE_VERSION_EXHAUSTED");
        long now=clock.millis();
        if(id==null){
            if(body.expectedVersion()!=0)throw ApiException.conflict("PROFILE_VERSION_CONFLICT");
            id=AuthService.id();
            db.update("insert into model_profiles(id,tenant_id,zone,name,model,base_url,require_token,enabled,version,updated_at) values(?,?,?,?,?,?,?,?,1,?)",id,actor.tenant(),body.zone(),body.name(),body.model(),body.baseUrl(),body.requireToken(),body.enabled(),now);
        } else {
            var rows=db.queryForList("select zone from model_profiles where id=? and tenant_id=?",id,actor.tenant());
            if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));
            if(db.update("update model_profiles set zone=?,name=?,model=?,base_url=?,require_token=?,enabled=?,version=version+1,updated_at=? where id=? and tenant_id=? and version=?",body.zone(),body.name(),body.model(),body.baseUrl(),body.requireToken(),body.enabled(),now,id,actor.tenant(),body.expectedVersion())!=1)throw ApiException.conflict("PROFILE_VERSION_CONFLICT");
        }
        db.update("insert into model_profile_history(profile_id,tenant_id,version,zone,name,model,base_url,require_token,enabled,actor_id,updated_at,restored_from,origin) select id,tenant_id,version,zone,name,model,base_url,require_token,enabled,?,updated_at,?,? from model_profiles where id=? and tenant_id=?",actor.id(),restoredFrom,"SAVED",id,actor.tenant());
        auth.audit(actor,restoredFrom==null?"MODEL_PROFILE_SAVED":"MODEL_PROFILE_RESTORED",Map.of("profileId",id,"zone",body.zone(),"enabled",body.enabled(),"version",body.expectedVersion()+1,"restoredFrom",restoredFrom==null?0:restoredFrom));
        return Map.of("id",id,"version",body.expectedVersion()+1);
    }
    private Map<String,Object> authorized(Identity actor,String id){
        actor.requireUser("ADMIN");var rows=db.queryForList("select * from model_profiles where id=? and tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> history(Identity actor,String id,int before,int limit){
        actor=auth.current(actor);var current=authorized(actor,id);
        if(before<0||limit<1||limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid history page");
        var args=new ArrayList<Object>();args.add(actor.tenant());args.add(id);args.addAll(actor.zones());args.add(before);args.add(before);args.add(limit+1);
        String scope=String.join(",",Collections.nCopies(actor.zones().size(),"?"));
        var rows=db.queryForList("select * from model_profile_history where tenant_id=? and profile_id=? and zone in ("+scope+") and (?=0 or version<?) order by version desc limit ?",args.toArray());
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("currentVersion",current.get("version"));if(more)result.put("nextCursor",rows.get(rows.size()-1).get("version"));return result;
    }
    @Transactional
    public Map<String,Object> restore(Identity actor,String id,Restore request){
        locks.lock(actor.tenant());actor=auth.current(actor);authorized(actor,id);
        var rows=db.queryForList("select * from model_profile_history where profile_id=? and tenant_id=? and version=?",id,actor.tenant(),request.targetVersion());
        if(rows.isEmpty())throw ApiException.missing();var old=rows.get(0);actor.requireZone((String)old.get("zone"));
        return saveVersion(actor,id,new Profile((String)old.get("name"),(String)old.get("zone"),(String)old.get("model"),(String)old.get("base_url"),(Boolean)old.get("require_token"),(Boolean)old.get("enabled"),request.expectedVersion()),request.targetVersion());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> forNode(Identity node,String id){
        node=auth.current(node);node.requireNode();
        var nodes=db.queryForList("select zone,capability from nodes where id=? and tenant_id=?",node.id(),node.tenant());
        if(nodes.isEmpty() || !"model.read.v1".equals(nodes.get(0).get("capability")))throw ApiException.forbidden();
        node.requireZone((String)nodes.get(0).get("zone"));
        var profiles=db.queryForList("select id,model,base_url,require_token,version from model_profiles where id=? and tenant_id=? and zone=? and enabled=true",id,node.tenant(),nodes.get(0).get("zone"));
        if(profiles.isEmpty())throw ApiException.missing();return profiles.get(0);
    }
}
