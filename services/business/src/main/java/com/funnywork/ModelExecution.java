package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

@Service
public class ModelExecution {
    private final ControlService control;
    private final ToolRegistry tools;
    private final ArtifactService artifacts;
    private final TenantLocks locks;
    private final JdbcTemplate db;
    private final Json json;
    private final AuthService auth;
    private final Clock clock;
    public ModelExecution(ControlService control,ToolRegistry tools,ArtifactService artifacts,TenantLocks locks,JdbcTemplate db,Json json,AuthService auth,Clock clock) {
        this.control=control;this.tools=tools;this.artifacts=artifacts;this.locks=locks;this.db=db;this.json=json;this.auth=auth;this.clock=clock;
    }
    @Transactional
    public Map<String,Object> inputs(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);
        var rows=db.queryForList("select p.id,p.ordinal,p.state,d.completion,r.attempt_id,r.result_json from work_units u join task_dependencies d on d.task_id=u.task_id and d.successor=u.ordinal join work_units p on p.task_id=d.task_id and p.ordinal=d.predecessor left join unit_results r on r.unit_id=p.id where u.id=? order by p.ordinal",lease.unitId());
        var items=new ArrayList<Map<String,Object>>();long bytes=0;
        for(var row:rows) {
            if(!"OUTPUT_AVAILABLE".equals(row.get("completion")) || !"GENERATED".equals(row.get("state")) || row.get("result_json")==null)
                throw ApiException.conflict("DEPENDENCY_NOT_AVAILABLE");
            var recorded=(Map<?,?>)json.read((String)row.get("result_json"));
            if(!"MODEL_OUTPUT".equals(recorded.get("kind")))throw ApiException.conflict("DEPENDENCY_NOT_AVAILABLE");
            String artifactId=(String)recorded.get("artifactId");
            // Model output is originally committed as version 1. Never follow current_version.
            var content=artifacts.read(lease.owner(),artifactId,1);
            if(!Objects.equals(recorded.get("sha256"),content.get("sha256")))throw ApiException.conflict("DEPENDENCY_CONTENT_CHANGED");
            bytes+=((Number)content.get("byte_size")).longValue();
            if(bytes>262144)throw new ApiException(413,"DEPENDENCY_INPUT_TOO_LARGE","Predecessor outputs exceed the model input budget");
            items.add(Map.of("unitId",row.get("id"),"ordinal",row.get("ordinal"),"attemptId",row.get("attempt_id"),
                "artifactId",artifactId,"version",1,"sha256",content.get("sha256"),"contentBase64",content.get("content_base64"),
                "reviewState","UNREVIEWED","businessVerified",false));
        }
        return Map.of("items",items,"businessVerified",false);
    }
    @Transactional
    public List<Map<String,Object>> catalog(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);
        return tools.catalog(lease.owner(),lease.taskId()).stream().filter(tool->tool.get("effect")==ToolContract.Effect.READ_ONLY || tool.get("effect")==ToolContract.Effect.ARTIFACT_WRITE).toList();
    }
    @Transactional
    public Map<String,Object> attachments(Identity node,String attempt,long epoch,String afterId,int afterVersion,int limit) {
        if(afterId==null || !afterId.matches("[A-Za-z0-9-]{0,64}") || afterVersion<0 || afterVersion>32 || limit<1 || limit>20 || (afterId.isEmpty() && afterVersion!=0))
            throw new ApiException(400,"INVALID_CURSOR","Invalid attachment page");
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);
        var rows=db.queryForList("select l.artifact_id,l.version,a.name,v.sha256,v.byte_size from task_artifacts l join tasks t on t.id=l.task_id join artifacts a on a.id=l.artifact_id join artifact_versions v on v.artifact_id=l.artifact_id and v.version=l.version where l.tenant_id=? and l.task_id=? and a.tenant_id=? and a.zone=t.zone and (a.owner_id=? or exists(select 1 from artifact_grants g where g.artifact_id=a.id and g.principal_id=?)) and (l.artifact_id>? or (l.artifact_id=? and l.version>?)) order by l.artifact_id,l.version limit ?",node.tenant(),lease.taskId(),node.tenant(),lease.owner().id(),lease.owner().id(),afterId,afterId,afterVersion,limit+1);
        boolean more=rows.size()>limit;
        var items=rows.subList(0,Math.min(limit,rows.size()));
        var last=items.isEmpty()?Map.<String,Object>of("artifact_id",afterId,"version",afterVersion):items.get(items.size()-1);
        return Map.of("items",items,"hasMore",more,"afterId",last.get("artifact_id"),"afterVersion",last.get("version"));
    }
    @Transactional
    public Map<String,Object> attachment(Identity node,String attempt,long epoch,String artifact,int version) {
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);
        if(version<1 || db.queryForObject("select count(*) from task_artifacts where tenant_id=? and task_id=? and artifact_id=? and version=?",Integer.class,node.tenant(),lease.taskId(),artifact,version)!=1)
            throw ApiException.missing();
        return artifacts.read(lease.owner(),artifact,version);
    }
    @Transactional
    public Map<String,Object> output(Identity node,String attempt,Requests.ModelResult result) {
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,result.epoch(),true);
        String digest=AuthService.hash(result.text());
        var previous=db.queryForList("select attempt_id,result_json from unit_results where unit_id=?",lease.unitId());
        if(!previous.isEmpty()) {
            var recorded=(Map<?,?>)json.read((String)previous.get(0).get("result_json"));
            if(!attempt.equals(previous.get(0).get("attempt_id")) || !digest.equals(recorded.get("sha256")))throw ApiException.conflict("RESULT_CONFLICT");
            return Map.of("accepted",true,"duplicate",true,"artifactId",recorded.get("artifactId"),"reviewState","UNREVIEWED");
        }
        control.modelLease(node,attempt,result.epoch(),false);
        inputs(node,attempt,result.epoch()); // Recheck source availability/authorization before accepting a new derived output.
        String zone=db.queryForObject("select zone from tasks where id=?",String.class,lease.taskId());
        var artifact=artifacts.create(lease.owner(),new ArtifactService.Create("model-output.txt",zone,Base64.getEncoder().encodeToString(result.text().getBytes(StandardCharsets.UTF_8))));
        String artifactId=(String)artifact.get("artifactId");
        artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifactId,1));
        var value=Map.of("kind","MODEL_OUTPUT","artifactId",artifactId,"sha256",digest,"reviewState","UNREVIEWED");
        db.update("insert into unit_results(unit_id,attempt_id,result_json,accepted_at) values(?,?,?,?)",lease.unitId(),attempt,json.write(value),clock.millis());
        db.update("update attempts set state='SUCCEEDED' where id=?",attempt);
        db.update("update work_units set state='GENERATED' where id=?",lease.unitId());
        control.modelOutputEvent(node,lease.taskId(),lease.unitId(),artifactId);
        control.refreshTask(lease.taskId());
        db.update("update work_units set next_dispatch_at=? where id in (select u.id from work_units u join tasks t on t.id=u.task_id where t.tenant_id=? and t.zone=? and t.state='RUNNING' and u.state='READY' and u.next_dispatch_at>? order by t.created_at,t.id,u.ordinal limit 64)",clock.millis(),node.tenant(),zone,clock.millis());
        auth.audit(node,"MODEL_OUTPUT_STORED",Map.of("taskId",lease.taskId(),"attemptId",attempt,"artifactId",artifactId,"reviewState","UNREVIEWED"));
        return Map.of("accepted",true,"duplicate",false,"artifactId",artifactId,"reviewState","UNREVIEWED");
    }
}
