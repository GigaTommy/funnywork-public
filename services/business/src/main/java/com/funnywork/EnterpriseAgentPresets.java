package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Current persona text applied to the client's installed standard Harness composition. */
@Service
public class EnterpriseAgentPresets {
    static final int CATALOG_MAX_ITEMS=512, CATALOG_MAX_BYTES=4194304;
    public record Publish(@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone,
        @NotBlank @Size(max=120) String name,@NotNull @Size(max=1000) String description,
        @NotBlank @Size(max=20000) String persona,boolean enabled,@Min(0) int expectedVersion) {}
    public record Policy(@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone,
        boolean allowPersonalPresets,@NotNull @Size(max=64) String defaultPresetId,@Min(0) int expectedVersion) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    private final TransactionTemplate tx;private final Clock clock;private final Json json;
    public EnterpriseAgentPresets(JdbcTemplate db,AuthService auth,TenantLocks locks,PlatformTransactionManager transactions,Clock clock,Json json){
        this.db=db;this.auth=auth;this.locks=locks;this.tx=new TransactionTemplate(transactions);this.clock=clock;this.json=json;
    }
    private Identity user(Identity actor){actor=auth.current(actor);if(!actor.kind().equals("USER"))throw ApiException.forbidden();return actor;}
    private Identity admin(Identity actor){actor=user(actor);actor.requireUser("ADMIN");return actor;}
    private static int version(Map<String,Object> row){return ((Number)row.get("version")).intValue();}
    private List<Map<String,Object>> authorized(Identity actor){return db.queryForList("select id,zone,version,enabled,name,description,persona from enterprise_agent_presets where tenant_id=? order by zone,id",actor.tenant()).stream().filter(r->actor.zones().contains(r.get("zone"))).toList();}
    public Map<String,Object> administration(Identity actor){actor=admin(actor);Identity live=actor;return Map.of("items",authorized(actor),"policies",db.queryForList("select * from enterprise_agent_policies where tenant_id=? order by zone",actor.tenant()).stream().filter(r->live.zones().contains(r.get("zone"))).toList(),"zones",actor.zones().stream().sorted().toList());}
    public Map<String,Object> publish(Identity actor,String id,Publish body){
        // Persona is literal text. Template expressions, YAML and plugin fields are never evaluated.
        if(body.persona().contains("{{")||body.persona().contains("}}")||body.persona().indexOf('\0')>=0)throw new ApiException(400,"INVALID_PERSONA","Persona must contain literal text without template expressions");
        return tx.execute(status->{locks.lock(actor.tenant());Identity live=admin(actor);live.requireZone(body.zone());
            String selected=id==null?AuthService.id():id;int previous=0;
            if(id!=null){var rows=db.queryForList("select * from enterprise_agent_presets where tenant_id=? and id=?",live.tenant(),id);if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);live.requireZone((String)row.get("zone"));if(!body.zone().equals(row.get("zone")))throw ApiException.conflict("PRESET_ZONE_IMMUTABLE");previous=version(row);}
            if(previous!=body.expectedVersion())throw ApiException.conflict("PRESET_VERSION_CONFLICT");
            if(!body.enabled()&&db.queryForObject("select count(*) from enterprise_agent_policies where tenant_id=? and default_preset=?",Integer.class,live.tenant(),selected)>0)throw ApiException.conflict("PRESET_IS_DEFAULT");
            int next=previous+1;long now=clock.millis();
            var prospective=new ArrayList<>(db.queryForList("select id,version,name,description,persona,enabled from enterprise_agent_presets where tenant_id=?",live.tenant()));
            prospective.removeIf(row->selected.equals(row.get("id")));
            prospective.add(Map.of("id",selected,"version",next,"name",body.name(),"description",body.description(),"persona",body.persona(),"enabled",body.enabled()));
            requireCatalogCapacity(prospective);
            if(id==null)db.update("insert into enterprise_agent_presets(tenant_id,id,zone,version,enabled,updated_at,name,description,persona,created_by) values(?,?,?,?,?,?,?,?,?,?)",live.tenant(),selected,body.zone(),next,body.enabled(),now,body.name(),body.description(),body.persona(),live.id());
            else db.update("update enterprise_agent_presets set version=?,enabled=?,updated_at=?,name=?,description=?,persona=?,created_by=? where tenant_id=? and id=?",next,body.enabled(),now,body.name(),body.description(),body.persona(),live.id(),live.tenant(),selected);
            auth.audit(live,"ENTERPRISE_PRESET_PUBLISHED",Map.of("id",selected,"version",next,"enabled",body.enabled()));return Map.of("id",selected,"version",next);
        });
    }
    /** Bound the tenant-wide union so every authorized multi-zone client can receive it. */
    private void requireCatalogCapacity(List<Map<String,Object>> rows){
        if(rows.size()>CATALOG_MAX_ITEMS)throw ApiException.conflict("PRESET_CATALOG_LIMIT");
        var items=catalogItems(rows,true);
        String longest="standard";for(var item:items){String id=(String)item.get("id");if(id.length()>longest.length())longest=id;}
        var envelope=Map.of("schemaVersion",1,"allowPersonalPresets",false,"defaultPresetId",longest,"items",items);
        if(json.write(envelope).getBytes(StandardCharsets.UTF_8).length>CATALOG_MAX_BYTES)throw ApiException.conflict("PRESET_CATALOG_LIMIT");
    }
    private List<Map<String,Object>> catalogItems(List<Map<String,Object>> rows,boolean worstCase){
        var items=new ArrayList<Map<String,Object>>();for(var row:rows){String id=(String)row.get("id");int n=version(row);items.add(Map.of("id","enterprise-"+id+"-v"+n,"name",row.get("name"),"description",row.get("description"),"persona",row.get("persona"),"base","standard","version",n,"selectable",!worstCase&&Boolean.TRUE.equals(row.get("enabled"))));}return items;
    }
    public void policy(Identity actor,Policy body){tx.executeWithoutResult(status->{locks.lock(actor.tenant());Identity live=admin(actor);live.requireZone(body.zone());
        var rows=db.queryForList("select version from enterprise_agent_policies where tenant_id=? and zone=?",live.tenant(),body.zone());int previous=rows.isEmpty()?0:version(rows.get(0));if(previous!=body.expectedVersion())throw ApiException.conflict("PRESET_POLICY_CONFLICT");
        if(!body.defaultPresetId().isEmpty()&&db.queryForObject("select count(*) from enterprise_agent_presets where tenant_id=? and id=? and zone=? and enabled=true",Integer.class,live.tenant(),body.defaultPresetId(),body.zone())!=1)throw ApiException.conflict("PRESET_DEFAULT_UNAVAILABLE");
        if(rows.isEmpty())db.update("insert into enterprise_agent_policies(tenant_id,zone,version,allow_personal,default_preset,updated_at) values(?,?,?,?,?,?)",live.tenant(),body.zone(),1,body.allowPersonalPresets(),body.defaultPresetId(),clock.millis());
        else db.update("update enterprise_agent_policies set version=?,allow_personal=?,default_preset=?,updated_at=? where tenant_id=? and zone=?",previous+1,body.allowPersonalPresets(),body.defaultPresetId(),clock.millis(),live.tenant(),body.zone());
        auth.audit(live,"ENTERPRISE_PRESET_POLICY",Map.of("zone",body.zone(),"version",previous+1,"allowPersonal",body.allowPersonalPresets()));
    });}
    public Map<String,Object> catalog(Identity actor){actor=user(actor);var current=authorized(actor);var policies=db.queryForList("select * from enterprise_agent_policies where tenant_id=? order by zone",actor.tenant());boolean personal=!actor.zones().isEmpty();String defaultId="standard";
        for(String zone:actor.zones().stream().sorted().toList()){
            var matches=policies.stream().filter(p->zone.equals(p.get("zone"))).toList();if(matches.isEmpty()){personal=false;continue;}var p=matches.get(0);personal&=Boolean.TRUE.equals(p.get("allow_personal"));String wanted=(String)p.get("default_preset");if(defaultId.equals("standard"))for(var r:current)if(wanted.equals(r.get("id"))&&Boolean.TRUE.equals(r.get("enabled")))defaultId="enterprise-"+wanted+"-v"+version(r);
        }
        var revisions=catalogItems(current,false);
        return Map.of("schemaVersion",1,"allowPersonalPresets",personal,"defaultPresetId",defaultId,"items",revisions);
    }
}
