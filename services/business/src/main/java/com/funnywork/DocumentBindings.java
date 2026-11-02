package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DocumentBindings {
    public record Binding(@NotNull @Pattern(regexp="[A-Za-z0-9._-]{1,120}") String sourceId,@NotNull @Pattern(regexp="[a-f0-9]{64}") String deploymentHash) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    private final ControlService control;private final ToolRegistry tools;private final Json json;
    public DocumentBindings(JdbcTemplate db,AuthService auth,TenantLocks locks,ControlService control,ToolRegistry tools,Json json) {
        this.db=db;this.auth=auth;this.locks=locks;this.control=control;this.tools=tools;this.json=json;
    }
    private void administrator(Identity actor,String task,String tool) {
        actor=auth.current(actor);actor.requireUser("ADMIN");
        var rows=db.queryForList("select t.zone,v.contract_json,v.enabled from tasks t join task_tool_grants g on g.task_id=t.id join tool_versions v on v.id=g.tool_version_id where t.id=? and t.tenant_id=? and v.tenant_id=? and v.id=? and v.zone=t.zone and t.state='RUNNING'",task,actor.tenant(),actor.tenant(),tool);
        if(rows.isEmpty())throw ApiException.missing();
        var row=rows.get(0);actor.requireZone((String)row.get("zone"));
        if(!Boolean.TRUE.equals(row.get("enabled")) || !"READ_ONLY".equals(((Map<?,?>)json.read((String)row.get("contract_json"))).get("effect")))throw ApiException.forbidden();
    }
    @Transactional public Map<String,Object> inspect(Identity actor,String task) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var tasks=db.queryForList("select owner_id,zone,state from tasks where tenant_id=? and id=?",actor.tenant(),task);
        if(tasks.isEmpty())throw ApiException.missing();
        var metadata=tasks.get(0);actor.requireZone((String)metadata.get("zone"));
        var rows=db.queryForList("select b.tool_version_id,b.source_id,b.deployment_hash,b.enabled as binding_enabled,v.name,v.version,v.enabled as tool_enabled,g.tool_version_id as granted from task_document_bindings b join tool_versions v on v.id=b.tool_version_id and v.tenant_id=b.tenant_id left join task_tool_grants g on g.task_id=b.task_id and g.tool_version_id=b.tool_version_id where b.tenant_id=? and b.task_id=? order by b.tool_version_id limit 64",actor.tenant(),task);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows)items.add(Map.of("toolVersionId",row.get("tool_version_id"),"name",row.get("name"),"version",row.get("version"),"sourceId",row.get("source_id"),"deploymentHash",row.get("deployment_hash"),"bindingEnabled",row.get("binding_enabled"),"toolEnabled",row.get("tool_enabled"),"taskGranted",row.get("granted")!=null));
        return Map.of("taskId",task,"executionPrincipalId",metadata.get("owner_id"),"zone",metadata.get("zone"),"taskState",metadata.get("state"),"items",items);
    }
    private Map<String,Object> scopedTask(Identity actor,String task){
        actor=auth.current(actor);actor.requireUser("ADMIN");
        var rows=db.queryForList("select owner_id,zone,state from tasks where tenant_id=? and id=?",actor.tenant(),task);
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));return row;
    }
    @Transactional(readOnly=true) public Map<String,Object> toolCandidates(Identity actor,String task,String after,int limit){
        var metadata=scopedTask(actor,task);
        if(after==null || !after.matches("[A-Za-z0-9-]{0,64}") || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid tool page");
        var rows=db.queryForList("select v.id,v.name,v.version,v.contract_json from task_tool_grants g join tool_versions v on v.id=g.tool_version_id where g.task_id=? and v.tenant_id=? and v.zone=? and v.enabled=true and v.id>? and not exists(select 1 from task_document_bindings b where b.task_id=g.task_id and b.tool_version_id=v.id) order by v.id limit ?",task,actor.tenant(),metadata.get("zone"),after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows)if("READ_ONLY".equals(((Map<?,?>)json.read((String)row.get("contract_json"))).get("effect")))items.add(Map.of("id",row.get("id"),"name",row.get("name"),"version",row.get("version")));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> sourceCandidates(Identity actor,String task,String afterSource,String afterHash,int limit){
        var metadata=scopedTask(actor,task);
        if(afterSource==null || !afterSource.matches("[A-Za-z0-9._-]{0,120}") || afterHash==null || !afterHash.matches("(?:[a-f0-9]{64})?") || afterSource.isEmpty()!=afterHash.isEmpty() || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid source page");
        var rows=db.queryForList("select d.source_id,d.deployment_hash,count(*) as node_count from node_source_deployments d join nodes n on n.id=d.node_id and n.tenant_id=d.tenant_id join principals p on p.id=n.id and p.tenant_id=n.tenant_id where d.tenant_id=? and d.principal_id=? and n.zone=? and n.capability='model.read.v1' and p.enabled=true and (d.source_id>? or (d.source_id=? and d.deployment_hash>?)) group by d.source_id,d.deployment_hash order by d.source_id,d.deployment_hash limit ?",actor.tenant(),metadata.get("owner_id"),metadata.get("zone"),afterSource,afterSource,afterHash,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);
        if(more){var last=rows.get(rows.size()-1);result.put("nextSource",last.get("source_id"));result.put("nextHash",last.get("deployment_hash"));}return result;
    }
    @Transactional public void bind(Identity actor,String task,String tool,Binding binding) {
        locks.lock(actor.tenant());administrator(actor,task,tool);
        if(binding==null || binding.sourceId()==null || !binding.sourceId().matches("[A-Za-z0-9._-]{1,120}") || binding.deploymentHash()==null || !binding.deploymentHash().matches("[a-f0-9]{64}"))throw new ApiException(400,"INVALID_DOCUMENT_BINDING","Invalid fixed source binding");
        var existing=db.queryForList("select * from task_document_bindings where task_id=? and tool_version_id=?",task,tool);
        if(!existing.isEmpty()) {
            var row=existing.get(0);
            if(!Boolean.TRUE.equals(row.get("enabled")) || !binding.sourceId().equals(row.get("source_id")) || !binding.deploymentHash().equals(row.get("deployment_hash")))throw ApiException.conflict("DOCUMENT_BINDING_IMMUTABLE");
            return;
        }
        if(db.queryForObject("select count(*) from task_document_bindings where task_id=?",Integer.class,task)>=64)throw new ApiException(429,"DOCUMENT_BINDING_LIMIT","Maximum 64 document bindings per task");
        db.update("insert into task_document_bindings values(?,?,?,?,?,true)",actor.tenant(),task,tool,binding.sourceId(),binding.deploymentHash());
        control.invalidateSourceReservations(actor.tenant());
        auth.audit(actor,"DOCUMENT_BOUND",Map.of("taskId",task,"toolVersionId",tool,"sourceId",binding.sourceId(),"deploymentHash",binding.deploymentHash()));
    }
    @Transactional public void revoke(Identity actor,String task,String tool) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var rows=db.queryForList("select zone from tasks where id=? and tenant_id=?",task,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));
        db.update("update task_document_bindings set enabled=false where tenant_id=? and task_id=? and tool_version_id=?",actor.tenant(),task,tool);
        auth.audit(actor,"DOCUMENT_BINDING_REVOKED",Map.of("taskId",task,"toolVersionId",tool));
    }
    @Transactional public Map<String,Object> scope(Identity node,String attempt,long epoch) {
        locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);
        var catalog=tools.catalog(lease.owner(),lease.taskId());
        var allowed=new HashSet<String>();
        for(var entry:catalog)if(entry.get("effect")==ToolContract.Effect.READ_ONLY)allowed.add((String)entry.get("toolVersionId"));
        var rows=db.queryForList("select tool_version_id,source_id,deployment_hash from task_document_bindings where tenant_id=? and task_id=? and enabled=true order by tool_version_id",node.tenant(),lease.taskId());
        return Map.of("tenant",node.tenant(),"taskId",lease.taskId(),"executionPrincipalId",lease.owner().id(),"bindings",rows.stream().filter(row->allowed.contains(row.get("tool_version_id"))).toList());
    }
}
