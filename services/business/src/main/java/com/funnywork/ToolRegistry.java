package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ToolRegistry {
    public record Publish(@NotNull @Valid ToolContract contract,@NotNull @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") @Size(max=40) String version,@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone) {}
    public record TemplatePublish(@NotNull @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") @Size(max=40) String version,@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String zone) {}
    public record Registered(String id,ToolContract contract) {}
    private final JdbcTemplate db;
    private final AuthService auth;
    private final TenantLocks locks;
    private final Json json;
    private final ObjectMapper mapper;
    public ToolRegistry(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,ObjectMapper mapper) {
        this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.mapper=mapper;
    }
    public String fingerprint(ToolContract c) {
        return AuthService.hash(json.write(new TreeMap<>(Map.of("name",c.name(),"effect",c.effect(),"fields",new TreeMap<>(c.fields()),"credentialRef",c.credentialRef(),"verifier",c.verifier()))));
    }
    @Transactional
    public Map<String,Object> publish(Identity actor,Publish body) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(body.zone());
        if(body.contract().effect()==ToolContract.Effect.ARTIFACT_WRITE && !BuiltinToolTemplates.isOfficeWrite(body.contract()))throw new ApiException(400,"ARTIFACT_TOOL_DENIED","Only built-in Office artifact writers are supported");
        String id=AuthService.id();
        db.update("insert into tool_versions values(?,?,?,?,?,?,?,true,?)",id,actor.tenant(),body.contract().name(),body.version(),body.zone(),json.write(body.contract()),fingerprint(body.contract()),actor.id());
        auth.audit(actor,"TOOL_PUBLISHED",Map.of("toolVersionId",id));
        return Map.of("toolVersionId",id,"name",body.contract().name(),"version",body.version(),"contractHash",fingerprint(body.contract()));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> templates(Identity actor) {
        actor=auth.current(actor);actor.requireUser("ADMIN");
        return BuiltinToolTemplates.ALL.stream().map(item->Map.<String,Object>of("templateId",item.id(),"title",item.title(),"name",item.contract().name(),"effect",item.contract().effect(),"inputSchema",item.contract().inputSchema())).toList();
    }
    @Transactional
    public Map<String,Object> publishTemplate(Identity actor,String templateId,TemplatePublish body) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(body.zone());
        var template=BuiltinToolTemplates.get(templateId);
        var existing=db.queryForList("select * from tool_versions where tenant_id=? and name=? and version=?",actor.tenant(),template.contract().name(),body.version());
        if(!existing.isEmpty()) {
            var row=existing.get(0);
            if(!body.zone().equals(row.get("zone")) || !fingerprint(template.contract()).equals(row.get("contract_hash")) || !Boolean.TRUE.equals(row.get("enabled")))throw ApiException.conflict("TEMPLATE_VERSION_CONFLICT");
            return Map.of("toolVersionId",row.get("id"),"name",row.get("name"),"version",row.get("version"),"reused",true);
        }
        var result=new LinkedHashMap<>(publish(actor,new Publish(template.contract(),body.version(),body.zone())));
        result.put("reused",false);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> agentTools(Identity actor,String agentId,String zone,String after,int limit) {
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        if(limit<1 || limit>100 || !after.matches("[A-Za-z0-9-]{0,64}") || !zone.matches("[a-z0-9-]{1,64}"))throw new ApiException(400,"INVALID_PAGE","Invalid tool page");
        if(db.queryForObject("select count(*) from agent_versions where id=? and tenant_id=? and capability='model.read.v1' and status='PUBLISHED'",Integer.class,agentId,actor.tenant())==0)throw ApiException.missing();
        var rows=db.queryForList("select v.id,v.name,v.version,v.zone,v.enabled,v.contract_hash,v.contract_json,d.tool_version_id as default_id from tool_versions v left join agent_tool_defaults d on d.tool_version_id=v.id and d.agent_id=? and d.tenant_id=v.tenant_id where v.tenant_id=? and v.zone=? and v.id>? order by v.id limit ?",agentId,actor.tenant(),zone,after,limit+1);
        boolean more=rows.size()>limit;
        if(more)rows=new ArrayList<>(rows.subList(0,limit));
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:rows) {
            ToolContract contract;
            try {contract=mapper.readValue((String)row.get("contract_json"),ToolContract.class);}
            catch(Exception e){throw new IllegalStateException("Invalid stored contract");}
            boolean enabled=Boolean.TRUE.equals(row.get("enabled"));
            items.add(Map.of("toolVersionId",row.get("id"),"name",row.get("name"),"version",row.get("version"),"zone",row.get("zone"),"enabled",enabled,"configured",row.get("default_id")!=null,"effect",contract.effect(),"eligible",enabled && (contract.effect()==ToolContract.Effect.READ_ONLY || BuiltinToolTemplates.isOfficeWrite(contract))));
        }
        Map<String,Object> page=new LinkedHashMap<>();page.put("items",items);
        if(more)page.put("nextCursor",rows.get(rows.size()-1).get("id"));
        return page;
    }
    @Transactional
    public void agentDefault(Identity actor,String agentId,String toolId,boolean enabled) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var agents=db.queryForList("select * from agent_versions where id=? and tenant_id=? and capability='model.read.v1' and status='PUBLISHED'",agentId,actor.tenant());
        if(agents.isEmpty())throw ApiException.missing();
        var row=tool(actor,toolId);actor.requireZone((String)row.get("zone"));
        if(enabled) {
            ToolContract contract;
            try {contract=mapper.readValue((String)row.get("contract_json"),ToolContract.class);}
            catch(Exception e) {throw new IllegalStateException("Invalid stored contract");}
            if(!Boolean.TRUE.equals(row.get("enabled")) || (contract.effect()!=ToolContract.Effect.READ_ONLY && !BuiltinToolTemplates.isOfficeWrite(contract)))throw ApiException.forbidden();
            if(db.queryForObject("select count(*) from agent_tool_defaults where agent_id=? and tool_version_id=?",Integer.class,agentId,toolId)==0) {
                if(db.queryForObject("select count(*) from agent_tool_defaults d join tool_versions v on v.id=d.tool_version_id where d.agent_id=? and v.zone=?",Integer.class,agentId,row.get("zone"))>=64)throw new ApiException(429,"TOOL_CATALOG_LIMIT","Too many default tools");
                db.update("insert into agent_tool_defaults values(?,?,?)",actor.tenant(),agentId,toolId);
            }
        } else db.update("delete from agent_tool_defaults where tenant_id=? and agent_id=? and tool_version_id=?",actor.tenant(),agentId,toolId);
        auth.audit(actor,enabled?"AGENT_TOOL_DEFAULT_ADDED":"AGENT_TOOL_DEFAULT_REMOVED",Map.of("agentId",agentId,"toolVersionId",toolId));
    }
    void applyDefaults(Identity actor,String taskId,String agentId,String zone) {
        var rows=db.queryForList("select v.id from agent_tool_defaults d join tool_versions v on v.id=d.tool_version_id where d.tenant_id=? and v.tenant_id=? and d.agent_id=? and v.zone=? and v.enabled=true order by v.id",actor.tenant(),actor.tenant(),agentId,zone);
        for(var row:rows)db.update("insert into task_tool_grants values(?,?,?)",actor.tenant(),taskId,row.get("id"));
        if(!rows.isEmpty())auth.audit(actor,"TASK_DEFAULT_TOOLS_APPLIED",Map.of("taskId",taskId,"toolVersionIds",rows.stream().map(r->r.get("id")).toList()));
    }
    @Transactional
    public void disable(Identity actor,String id) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var row=tool(actor,id);actor.requireZone((String)row.get("zone"));
        db.update("update tool_versions set enabled=false where id=?",id);
        auth.audit(actor,"TOOL_DISABLED",Map.of("toolVersionId",id));
    }
    private Map<String,Object> tool(Identity actor,String id) {
        var rows=db.queryForList("select * from tool_versions where id=? and tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    @Transactional
    public void grant(Identity actor,String taskId,String toolId,boolean enabled) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var tools=tool(actor,toolId);actor.requireZone((String)tools.get("zone"));
        var tasks=db.queryForList("select zone from tasks where id=? and tenant_id=?",taskId,actor.tenant());
        if(tasks.isEmpty())throw ApiException.missing();String zone=(String)tasks.get(0).get("zone");actor.requireZone(zone);
        if(!zone.equals(tools.get("zone")))throw ApiException.forbidden();
        if(enabled) {
            if(!Boolean.TRUE.equals(tools.get("enabled")))throw ApiException.forbidden();
            if(db.queryForObject("select count(*) from task_tool_grants where task_id=? and tool_version_id=?",Integer.class,taskId,toolId)==0)
                db.update("insert into task_tool_grants values(?,?,?)",actor.tenant(),taskId,toolId);
        } else db.update("delete from task_tool_grants where task_id=? and tool_version_id=?",taskId,toolId);
        auth.audit(actor,enabled?"TASK_TOOL_GRANTED":"TASK_TOOL_REVOKED",Map.of("taskId",taskId,"toolVersionId",toolId));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> catalog(Identity actor,String taskId) {
        actor=auth.current(actor);actor.requireUser("OPERATOR");
        var tasks=db.queryForList("select * from tasks where id=? and tenant_id=? and owner_id=?",taskId,actor.tenant(),actor.id());
        if(tasks.isEmpty())throw ApiException.missing();var task=tasks.get(0);actor.requireZone((String)task.get("zone"));
        if(!task.get("state").equals("RUNNING") || ((Number)task.get("owner_auth_version")).longValue()!=actor.authVersion())throw ApiException.forbidden();
        var rows=db.queryForList("select v.* from tool_versions v join task_tool_grants g on g.tool_version_id=v.id where g.task_id=? and v.tenant_id=? and v.zone=? and v.enabled=true order by v.name,v.version limit 65",taskId,actor.tenant(),task.get("zone"));
        if(rows.size()>64)throw new ApiException(429,"TOOL_CATALOG_LIMIT","Reduce task tools to at most 64 versions");
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            ToolContract contract;
            try {contract=mapper.readValue((String)row.get("contract_json"),ToolContract.class);}
            catch(Exception e) {throw new IllegalStateException("Stored tool contract is invalid");}
            result.add(Map.of("toolVersionId",row.get("id"),"name",row.get("name"),"version",row.get("version"),"effect",contract.effect(),"inputSchema",contract.inputSchema()));
        }
        return result;
    }
    Registered reconciliationVersion(Identity actor,String id,String zone) {
        actor=auth.current(actor);actor.requireUser("RECONCILER");actor.requireZone(zone);
        var row=tool(actor,id);
        if(!Boolean.TRUE.equals(row.get("enabled")) || !zone.equals(row.get("zone")))throw ApiException.forbidden();
        try {return new Registered(id,mapper.readValue((String)row.get("contract_json"),ToolContract.class));}
        catch(Exception e) {throw new IllegalStateException("Stored tool contract is invalid");}
    }
    public Registered authorizedVersion(Identity actor,String taskId,String id) {
        actor=auth.current(actor);var row=tool(actor,id);
        ToolContract contract;
        try {contract=mapper.readValue((String)row.get("contract_json"),ToolContract.class);}
        catch(Exception e) {throw new IllegalStateException("Stored tool contract is invalid");}
        return authorized(actor,taskId,contract,id);
    }
    public Registered authorized(Identity actor,String taskId,ToolContract contract,String pinnedId) {
        actor=auth.current(actor);actor.requireUser("OPERATOR");
        var rows=db.queryForList("select v.*,t.owner_id,t.owner_auth_version,t.zone as task_zone from tool_versions v join task_tool_grants g on g.tool_version_id=v.id join tasks t on t.id=g.task_id where g.task_id=? and v.tenant_id=? and v.contract_hash=? and v.enabled=true order by v.id",taskId,actor.tenant(),fingerprint(contract));
        for(var row:rows) {
            if(pinnedId!=null && !pinnedId.equals(row.get("id")))continue;
            if(!actor.id().equals(row.get("owner_id")) || ((Number)row.get("owner_auth_version")).longValue()!=actor.authVersion())continue;
            actor.requireZone((String)row.get("zone"));
            if(!row.get("zone").equals(row.get("task_zone")))continue;
            try {return new Registered((String)row.get("id"),mapper.readValue((String)row.get("contract_json"),ToolContract.class));}
            catch(Exception e) {throw new IllegalStateException("Stored tool contract is invalid");}
        }
        throw ApiException.forbidden();
    }
}
