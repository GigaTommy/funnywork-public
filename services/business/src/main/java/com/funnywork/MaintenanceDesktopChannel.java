package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Maintenance extends the original authenticated desktop state exchange, without client node credentials. */
@Service
public class MaintenanceDesktopChannel {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MaintenanceDesktopChannel.class);
    public record Binding(@NotBlank String deviceId,@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone) {}
    public record Renewal(@NotBlank String runtimeEpoch,@Min(1) long epoch) {}
    public record Receipt(@NotBlank String runtimeEpoch,@jakarta.validation.Valid @NotNull MaintenanceService.Report report) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    private final ControlService control;private final MaintenanceService maintenance;private final DesktopPolicies policies;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceSyncChecks syncChecks;
    private final Json json;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceIntake intake;
    @org.springframework.beans.factory.annotation.Autowired private AnnualReports annual;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionResources executionResources;
    public MaintenanceDesktopChannel(JdbcTemplate db,AuthService auth,TenantLocks locks,ControlService control,MaintenanceService maintenance,DesktopPolicies policies,Json json){
        this.db=db;this.auth=auth;this.locks=locks;this.control=control;this.maintenance=maintenance;this.policies=policies;this.json=json;
    }
    private Map<String,Object> device(Identity actor,String id){
        actor=auth.current(actor);actor.requireUser("ADMIN");
        var rows=db.queryForList("select d.*,p.zones as owner_zones,p.enabled as owner_enabled from sync_devices d join principals p on p.id=d.principal_id and p.tenant_id=d.tenant_id where d.id=? and d.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        if(!actor.zones().containsAll(json.strings((String)row.get("owner_zones"))))throw ApiException.forbidden();
        return row;
    }
    @Transactional public Object bind(Identity admin,Binding body){
        locks.lock(admin.tenant());var source=device(admin,body.deviceId());admin=auth.current(admin);admin.requireZone(body.zone());
        if(!json.strings((String)source.get("owner_zones")).contains(body.zone())||Boolean.TRUE.equals(source.get("revoked"))||!Boolean.TRUE.equals(source.get("owner_enabled")))throw ApiException.forbidden();
        var existing=db.queryForList("select b.node_id,n.zone from maintenance_desktop_bindings b join nodes n on n.id=b.node_id where b.device_id=? and b.tenant_id=?",body.deviceId(),admin.tenant());
        if(!existing.isEmpty()){if(!body.zone().equals(existing.get(0).get("zone")))throw ApiException.conflict("MAINTENANCE_DEVICE_ZONE_CHANGED");return Map.of("deviceId",body.deviceId(),"nodeId",existing.get(0).get("node_id"));}
        var registered=control.registerNode(admin,new Requests.Node((String)source.get("name"),body.zone(),1,Capabilities.REPOSITORY));
        // The bearer returned by registerNode is intentionally never sent to or stored by the desktop.
        db.update("insert into maintenance_desktop_bindings(device_id,tenant_id,node_id) values(?,?,?)",body.deviceId(),admin.tenant(),registered.get("nodeId"));
        auth.audit(admin,"MAINTENANCE_DESKTOP_BOUND",Map.of("deviceId",body.deviceId(),"nodeId",registered.get("nodeId")));
        return Map.of("deviceId",body.deviceId(),"nodeId",registered.get("nodeId"));
    }
    private Identity sourceNode(Identity actor,String deviceId,String runtimeEpoch){
        actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();policies.requireControl(actor);
        var rows=db.queryForList("select b.node_id,d.principal_id,d.runtime_epoch,d.revoked,p.auth_version,p.zones,n.zone from maintenance_desktop_bindings b join sync_devices d on d.id=b.device_id and d.tenant_id=b.tenant_id join principals p on p.id=b.node_id and p.tenant_id=b.tenant_id join nodes n on n.id=b.node_id where b.device_id=? and b.tenant_id=? and b.enabled=true",deviceId,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        if(!actor.id().equals(row.get("principal_id"))||Boolean.TRUE.equals(row.get("revoked"))||!actor.zones().contains((String)row.get("zone")))throw ApiException.forbidden();
        if(!Objects.equals(runtimeEpoch,row.get("runtime_epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");
        return auth.current(new Identity((String)row.get("node_id"),actor.tenant(),"NODE",Set.of(),Set.of(),((Number)row.get("auth_version")).longValue()));
    }
    @Transactional public List<Map<String,Object>> dispatch(Identity actor,String deviceId,DesktopInteractions.State state){
        if(db.queryForObject("select count(*) from maintenance_desktop_bindings where device_id=? and tenant_id=? and enabled=true",Integer.class,deviceId,actor.tenant())==0||!policies.canControl(actor))return List.of();
        locks.lock(actor.tenant());Identity node=sourceNode(actor,deviceId,state.epoch());
        if(syncChecks.occupied(actor.tenant(),deviceId)){control.heartbeat(node,false);return List.of();}
        if(state.choices()==null||(!state.choices().path("maintenance").path("executor").asText().equals("funnywork-native-v1")&&!state.choices().path("annualReport").asText().equals("annual-report.v1"))){control.heartbeat(node,false);return List.of();}
        var source=db.queryForMap("select platform from sync_devices where id=? and tenant_id=?",deviceId,actor.tenant());
        String platform=switch((String)source.get("platform")){case "darwin","macos"->"macos";case "win32","windows"->"windows";case "linux"->"linux";default->"unknown";};
        if("unknown".equals(platform)){control.heartbeat(node,false);return List.of();}
        String git=state.choices().path("maintenance").path("gitVersion").asText("unavailable");
        maintenance.profile(node,new MaintenanceService.Profile(platform,state.choices().path("maintenance").path("delivery").asBoolean(false)?"funnywork-native-v2":"funnywork-native-v1",git));
        if(intake.occupied(node.id())){control.heartbeat(node,false);return intake.dispatch(actor,node,deviceId,state.epoch(),state.choices().path("maintenance").path("triage").asBoolean(false));}
        if(db.queryForObject("select count(*) from attempts a join maintenance_desktop_attempts m on m.attempt_id=a.id where a.node_id=? and a.state in ('RUNNING','RESERVED') and m.runtime_epoch<>?",Integer.class,node.id(),state.epoch())>0){control.heartbeat(node,false);return List.of();}
        if(state.choices().path("annualReport").asText().equals("annual-report.v1")){
            if(db.queryForObject("select count(*) from node_capabilities where node_id=? and capability=?",Integer.class,node.id(),Capabilities.ANNUAL)==0)db.update("insert into node_capabilities values(?,?)",node.id(),Capabilities.ANNUAL);
        }else db.update("delete from node_capabilities where node_id=? and capability=?",node.id(),Capabilities.ANNUAL);
        control.heartbeat(node,true);var work=control.poll(node);
        if(!"WORK".equals(work.get("status"))){var frames=intake.dispatch(actor,node,deviceId,state.epoch(),state.choices().path("maintenance").path("triage").asBoolean(false));if(!frames.isEmpty())control.heartbeat(node,false);return frames;}
        String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();
        var old=db.queryForList("select runtime_epoch from maintenance_desktop_attempts where attempt_id=?",attempt);
        if(old.isEmpty())db.update("insert into maintenance_desktop_attempts values(?,?,?,?)",attempt,deviceId,actor.tenant(),state.epoch());
        else if(!state.epoch().equals(old.get(0).get("runtime_epoch")))return List.of();
        boolean report=Capabilities.ANNUAL.equals(work.get("capability"));var spec=report?annual.frame(node,attempt,epoch):maintenance.spec(node,attempt,epoch);
        return List.of(Map.of("id",attempt,"kind",report?"annual-report":"maintenance","nativeId","","payload",Map.of("epoch",epoch,"runtimeEpoch",state.epoch(),"spec",spec.get("spec"),"specHash",spec.get("specHash"),"leaseDurationMs",work.get("leaseDurationMs"))));
    }
    Identity attemptNode(Identity actor,String deviceId,String attempt,String runtimeEpoch){
        Identity node=sourceNode(actor,deviceId,runtimeEpoch);
        if(db.queryForObject("select count(*) from maintenance_desktop_attempts m join attempts a on a.id=m.attempt_id where m.attempt_id=? and m.device_id=? and m.tenant_id=? and m.runtime_epoch=? and a.node_id=?",Integer.class,attempt,deviceId,actor.tenant(),runtimeEpoch,node.id())!=1)throw ApiException.forbidden();
        return node;
    }
    @Transactional public Object renew(Identity actor,String deviceId,String attempt,Renewal body){
        locks.lock(actor.tenant());Identity node=attemptNode(actor,deviceId,attempt,body.runtimeEpoch());control.heartbeat(node,true);try{return control.renew(node,attempt,body.epoch());}catch(ApiException denied){log.warn("Desktop lease rejected: attempt={} code={}",attempt,denied.code);throw denied;}
    }
    @Transactional public Object report(Identity actor,String deviceId,String attempt,Receipt body){
        locks.lock(actor.tenant());return maintenance.report(attemptNode(actor,deviceId,attempt,body.runtimeEpoch()),attempt,body.report());
    }
    @Transactional public Object annualFailure(Identity actor,String deviceId,String attempt,AnnualReports.Failure body){
        locks.lock(actor.tenant());return annual.failure(attemptNode(actor,deviceId,attempt,body.runtimeEpoch()),attempt,body);
    }
    @Transactional public Object annualCandidate(Identity actor,String deviceId,String attempt,AnnualReports.Candidate body){
        locks.lock(actor.tenant());try{return annual.candidate(attemptNode(actor,deviceId,attempt,body.runtimeEpoch()),attempt,body);}catch(ApiException denied){log.warn("Annual candidate rejected: attempt={} code={}",attempt,denied.code);throw denied;}
    }
    @Transactional public Object authorizeUpload(Identity actor,String deviceId,String attempt,ExecutionResources.UploadAuthorization body){
        locks.lock(actor.tenant());Identity node=attemptNode(actor,deviceId,attempt,body.runtimeEpoch());control.maintenanceLease(node,attempt,body.epoch(),false);
        return executionResources.authorizeUpload(actor,deviceId,attempt,body);
    }
    @Transactional public Object upload(Identity actor,String deviceId,String attempt,ExecutionResources.Upload body){
        locks.lock(actor.tenant());Identity node=attemptNode(actor,deviceId,attempt,body.runtimeEpoch());control.maintenanceLease(node,attempt,body.epoch(),false);
        return executionResources.upload(actor,deviceId,attempt,body);
    }

}
