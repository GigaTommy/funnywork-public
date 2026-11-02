package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Organization inventory exposes operational device metadata, never conversation bodies or local paths. */
@Service
public class ManagedDesktopDevices {
    private final JdbcTemplate db;private final AuthService auth;private final Json json;private final Clock clock;private final TenantLocks locks;private final DesktopHistorySync sync;private final DesktopModelReleases models;
    public ManagedDesktopDevices(JdbcTemplate db,AuthService auth,Json json,Clock clock,TenantLocks locks,DesktopHistorySync sync,DesktopModelReleases models){this.db=db;this.auth=auth;this.json=json;this.clock=clock;this.locks=locks;this.sync=sync;this.models=models;}
    private Identity administrator(Identity actor){actor=auth.current(actor);actor.requireUser("ADMIN");return actor;}
    private void scope(Identity actor,String zones){if(!actor.zones().containsAll(json.strings(zones)))throw ApiException.forbidden();}
    private void valid(boolean condition){if(!condition)throw new ApiException(400,"INVALID_DEVICE_QUERY","Invalid device query");}
    private boolean online(Map<String,Object> row,long now){long seen=((Number)row.get("state_seen")).longValue();return Boolean.TRUE.equals(row.get("owner_enabled"))&&!Boolean.TRUE.equals(row.get("revoked"))&&row.get("runtime_epoch")!=null&&seen>0&&seen<=now&&seen>=now-30000;}
    private Map<String,Object> view(Map<String,Object> row,long now,Long modelTarget,long policyTarget){
        Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","principal_id","owner_name","owner_enabled","name","platform","revoked","created_at","last_seen","state_seen","access_revision"))result.put(key,row.get(key));
        result.put("online",online(row,now));
        for(String key:List.of("client_version","model_issued_version","model_applied_version","model_reported_at","model_applied_at","model_expires_at","policy_issued_version","policy_applied_version","policy_applied_at"))result.put(key,row.get(key));
        // Device-bound telemetry is distinct from legacy model-publication receipts and is not attestation.
        var choices=row.get("model_choices_json")==null?Map.of():json.read((String)row.get("model_choices_json"));
        Object build=choices instanceof Map<?,?> catalog?catalog.get("clientBuild"):null;
        result.put("clientBuild",build);
        result.put("clientVersionSource",build instanceof Map<?,?>?"DEVICE_BUILD_MANIFEST":row.get("client_version")!=null?"LEGACY_MODEL_RECEIPT":"UNKNOWN");
        if(build instanceof Map<?,?> manifest)result.put("client_version",manifest.get("version"));
        result.put("modelSettings",row.get("model_settings")==null?null:json.read((String)row.get("model_settings")));result.put("modelTargetVersion",modelTarget);result.put("policyTargetVersion",policyTarget);
        String modelState="UNREPORTED";
        if(row.get("model_report_state")!=null){
            if(modelTarget!=null&&!Objects.equals(number(row.get("model_issued_version")),modelTarget))modelState="UPDATE_REQUIRED";
            else if(number(row.get("model_expires_at"))<=now)modelState="EXPIRED";
            else if("NOT_APPLIED".equals(row.get("model_report_state")))modelState="NOT_APPLIED";
            else if(!Objects.equals(row.get("model_issued_digest"),row.get("model_applied_digest")))modelState="PENDING";
            else modelState="APPLIED";
        }
        String policyState=row.get("policy_issued_version")==null?"UNREPORTED":row.get("policy_applied_version")==null?"PENDING":!Objects.equals(number(row.get("policy_applied_version")),policyTarget)?"UPDATE_REQUIRED":!Objects.equals(row.get("policy_issued_digest"),row.get("policy_applied_digest"))?"PENDING":"APPLIED";
        result.put("modelStatus",modelState);result.put("policyStatus",policyState);return result;
    }
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    /** Scan bounded keyset pages; the cursor advances over inaccessible rows without exposing their contents. */
    @Transactional(readOnly=true) public Map<String,Object> list(Identity actor,String after,String state,String query){
        actor=administrator(actor);valid(after!=null&&(after.isEmpty()||after.matches("[A-Za-z0-9_-]{1,64}")));valid(Set.of("ALL","ONLINE","OFFLINE","REVOKED").contains(state));valid(query!=null&&query.length()<=120);
        long now=clock.millis();String pattern="%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        Long modelTarget=models.targetVersion(actor);
        Long policyTarget=db.queryForObject("select max(version) from desktop_policy_releases where tenant_id=?",Long.class,actor.tenant());
        var rows=db.queryForList("select d.id,d.principal_id,d.name,d.platform,d.revoked,d.created_at,d.last_seen,d.state_seen,d.access_revision,d.runtime_epoch,p.display_name as owner_name,p.enabled as owner_enabled,p.zones as owner_zones,d.model_choices_json,m.client_version,m.settings_json as model_settings,m.issued_version as model_issued_version,m.applied_version as model_applied_version,m.reported_at as model_reported_at,m.applied_at as model_applied_at,m.expires_at as model_expires_at,m.report_state as model_report_state,m.issued_digest as model_issued_digest,m.applied_digest as model_applied_digest,r.issued_version as policy_issued_version,r.applied_version as policy_applied_version,r.applied_at as policy_applied_at,r.issued_digest as policy_issued_digest,r.applied_digest as policy_applied_digest from sync_devices d join principals p on p.id=d.principal_id and p.tenant_id=d.tenant_id left join desktop_model_receipts m on m.sync_device_id=d.id and m.tenant_id=d.tenant_id and m.principal_id=d.principal_id left join desktop_policy_receipts r on r.device_id=m.device_id and r.tenant_id=d.tenant_id and r.principal_id=d.principal_id where d.tenant_id=? and d.id>? and (lower(d.name) like ? escape '!' or lower(p.display_name) like ? escape '!') order by d.id limit 101",actor.tenant(),after,pattern,pattern);
        List<Map<String,Object>> items=new ArrayList<>();String cursor=after;
        for(var row:rows.subList(0,Math.min(100,rows.size()))){cursor=(String)row.get("id");if(!actor.zones().containsAll(json.strings((String)row.get("owner_zones"))))continue;boolean revoked=Boolean.TRUE.equals(row.get("revoked")),connected=online(row,now);if(state.equals("REVOKED")&&!revoked||state.equals("ONLINE")&&!connected||state.equals("OFFLINE")&&(connected||revoked))continue;items.add(view(row,now,modelTarget,policyTarget==null?0:policyTarget));}
        // One bounded aggregate for visible devices only; never count raw events or expose bodies.
        var history=new HashMap<String,Map<String,Object>>();
        if(!items.isEmpty()){
            var params=new ArrayList<Object>();params.add(actor.tenant());
            for(var item:items)params.add(item.get("id"));
            for(var totals:db.queryForList("select device_id,count(*) as session_count,sum(next_seq) as event_count,max(updated_at) as history_seen from sync_sessions where tenant_id=? and device_id in ("+String.join(",",Collections.nCopies(items.size(),"?"))+") group by device_id",params.toArray()))history.put((String)totals.get("device_id"),totals);
        }
        for(var item:items){var totals=history.get((String)item.get("id"));item.put("historySessionCount",totals==null?0L:number(totals.get("session_count")));item.put("historyEventCount",totals==null?0L:number(totals.get("event_count")));item.put("historyLastUpdatedAt",totals==null?null:totals.get("history_seen"));}
        return Map.of("items",items,"after",cursor,"hasMore",rows.size()>100,"observedAt",now);
    }
    @Transactional public Map<String,Object> access(Identity actor,String id,DesktopHistorySync.DeviceAccess request,boolean revoked){
        actor=administrator(actor);valid(id!=null&&id.matches("[A-Za-z0-9_-]{1,64}")&&request!=null&&request.revision()!=null);
        locks.lock(actor.tenant());
        var owners=db.queryForList("select principal_id from sync_devices where id=? and tenant_id=?",id,actor.tenant());if(owners.isEmpty())throw ApiException.missing();String owner=(String)owners.get(0).get("principal_id");
        db.queryForObject("select id from principals where id=? and tenant_id=? for update",String.class,owner,actor.tenant());actor=administrator(actor);
        var principal=db.queryForMap("select zones from principals where id=? and tenant_id=?",owner,actor.tenant());scope(actor,(String)principal.get("zones"));
        var row=db.queryForMap("select id,revoked,access_revision from sync_devices where id=? and tenant_id=? and principal_id=? for update",id,actor.tenant(),owner);
        boolean changed=Boolean.TRUE.equals(row.get("revoked"))!=revoked;sync.updateDeviceAccess(id,row,request,revoked);
        if(changed)auth.audit(actor,revoked?"DESKTOP_DEVICE_REVOKED":"DESKTOP_DEVICE_RESTORED",Map.of("deviceId",id,"principalId",owner,"revision",request.revision()+1));
        return Map.of("id",id,"revoked",revoked,"revision",request.revision()+(changed?1:0));
    }
}
