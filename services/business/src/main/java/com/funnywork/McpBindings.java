package com.funnywork;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.time.Clock;
import java.util.*;

@Service
public class McpBindings {
    public record Config(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{1,64}") String toolVersionId,@NotBlank @Size(max=1000) String endpoint,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,120}") String remoteTool,@NotNull @Pattern(regexp="(?:FUNNYWORK_MCP_[A-Z0-9_]{1,80})?") String credentialRef,
        @NotNull Boolean enabled,@NotNull @Min(0) Integer expectedVersion){}
    public record Call(@Min(1) long epoch,@NotNull Map<String,Object> arguments){}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ToolRegistry tools;private final ControlService control;
    private final ObjectMapper mapper;private final Environment environment;private final Clock clock;private final TransactionTemplate tx;private final Set<String> hosts;
    public McpBindings(JdbcTemplate db,AuthService auth,TenantLocks locks,ToolRegistry tools,ControlService control,ObjectMapper mapper,Environment environment,Clock clock,PlatformTransactionManager manager,@Value("${funnywork.mcp-hosts:}") String allowed){
        this.db=db;this.auth=auth;this.locks=locks;this.tools=tools;this.control=control;this.mapper=mapper;this.environment=environment;this.clock=clock;tx=new TransactionTemplate(manager);hosts=new HashSet<>();for(String host:allowed.split(","))if(!host.isBlank())hosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    private URI endpoint(String value){try{URI uri=URI.create(value);String host=uri.getHost();if(host==null || !hosts.contains(host.toLowerCase(Locale.ROOT)) || uri.getPort()>65535 || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())&&Set.of("127.0.0.1","[::1]").contains(host)))throw new IllegalArgumentException();return uri;}catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_MCP_ENDPOINT","MCP endpoint requires HTTPS and a deployment-allowed host");}}
    private Map<String,Object> tool(Identity actor,String id){actor=auth.current(actor);actor.requireUser("ADMIN");var rows=db.queryForList("select * from tool_versions where tenant_id=? and id=?",actor.tenant(),id);if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));return row;}
    public Map<String,Object> list(Identity actor,String zone,String after,int limit){actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);if(after==null || after.length()>64 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid page");var rows=db.queryForList("select b.*,v.name as tool_name,v.version as tool_version,v.enabled as tool_enabled from mcp_bindings b join tool_versions v on v.id=b.tool_version_id and v.tenant_id=b.tenant_id where b.tenant_id=? and b.zone=? and b.tool_version_id>? order by b.tool_version_id limit ?",actor.tenant(),zone,after,limit+1);boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);var result=new LinkedHashMap<String,Object>();result.put("items",rows);if(more)result.put("nextCursor",rows.get(rows.size()-1).get("tool_version_id"));return result;}
    /** Candidate pages never expose contract credentials or remote secrets. */
    public Map<String,Object> candidates(Identity actor,String zone,String after,int limit){
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        if(after==null || !after.matches("[A-Za-z0-9-]{0,64}") || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid page");
        var rows=db.queryForList("select v.* from tool_versions v where v.tenant_id=? and v.zone=? and v.enabled=true and v.id>? and not exists(select 1 from mcp_bindings b where b.tool_version_id=v.id) order by v.id limit ?",actor.tenant(),zone,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows)if(contract(row).effect()==ToolContract.Effect.READ_ONLY)items.add(Map.of("id",row.get("id"),"name",row.get("name"),"version",row.get("version"),"zone",row.get("zone"),"contractHash",row.get("contract_hash")));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    private record Inspection(Map<String,Object> binding,ToolContract contract){}
    private Inspection inspection(Identity actor,String id,int version){return tx.execute(status->{
        locks.lock(actor.tenant());var row=tool(actor,id);
        if(!Boolean.TRUE.equals(row.get("enabled")))throw ApiException.conflict("TOOL_DISABLED");
        var rows=db.queryForList("select * from mcp_bindings where tool_version_id=? and tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var binding=rows.get(0);
        if(version<1 || ((Number)binding.get("version")).intValue()!=version)throw ApiException.conflict("MCP_VERSION_CONFLICT");
        return new Inspection(binding,contract(row));
    });}
    /** Validate saved transport and schema without invoking any remote business tool. */
    public Map<String,Object> check(Identity actor,String id,int version){
        var snapshot=inspection(actor,id,version);var binding=snapshot.binding();
        String ref=(String)binding.get("credential_ref"),token=ref.isEmpty()?null:environment.getProperty(ref);
        if(!ref.isEmpty() && (token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126)))throw new ApiException(503,"MCP_CREDENTIAL_UNAVAILABLE","MCP credential unavailable");
        try(var remote=new McpHttpClient(endpoint((String)binding.get("endpoint")),token,mapper)){
            remote.initialize();JsonNode selected=null;
            for(JsonNode candidate:remote.tools())if(binding.get("remote_tool").equals(candidate.path("name").asText())){if(selected!=null)throw new ApiException(409,"MCP_SCHEMA_CHANGED","Ambiguous remote tool");selected=candidate;}
            if(selected==null || !mapper.valueToTree(snapshot.contract().inputSchema()).equals(selected.path("inputSchema")))throw new ApiException(409,"MCP_SCHEMA_CHANGED","Remote schema differs from published contract");
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(502,"MCP_CHECK_FAILED","MCP connection check failed");}
        return tx.execute(status->{locks.lock(actor.tenant());inspection(actor,id,version);long now=clock.millis();
            auth.audit(auth.current(actor),"MCP_BINDING_CHECKED",Map.of("toolVersionId",id,"version",version,"checkedAt",now));
            return Map.of("toolVersionId",id,"version",version,"checkedAt",now,"schemaMatched",true,"toolInvoked",false);
        });
    }
    public Map<String,Object> save(Identity actor,Config body){return tx.execute(status->{locks.lock(actor.tenant());var row=tool(actor,body.toolVersionId());if(body.enabled()&&!Boolean.TRUE.equals(row.get("enabled")))throw ApiException.conflict("TOOL_DISABLED");ToolContract contract=contract(row);if(contract.effect()!=ToolContract.Effect.READ_ONLY)throw new ApiException(400,"MCP_WRITE_DENIED","Write tools require a receipt gateway");endpoint(body.endpoint());
        var old=db.queryForList("select * from mcp_bindings where tool_version_id=?",body.toolVersionId());
        if(old.isEmpty()){if(body.expectedVersion()!=0)throw ApiException.conflict("MCP_VERSION_CONFLICT");db.update("insert into mcp_bindings values(?,?,?,?,?,?,?,1,?)",body.toolVersionId(),actor.tenant(),row.get("zone"),body.endpoint(),body.remoteTool(),body.credentialRef(),body.enabled(),clock.millis());}
        else{var prior=old.get(0);if(!body.endpoint().equals(prior.get("endpoint")) || !body.remoteTool().equals(prior.get("remote_tool")) || !body.credentialRef().equals(prior.get("credential_ref")))throw ApiException.conflict("MCP_BINDING_IMMUTABLE");if(db.update("update mcp_bindings set enabled=?,version=version+1,updated_at=? where tool_version_id=? and tenant_id=? and version=?",body.enabled(),clock.millis(),body.toolVersionId(),actor.tenant(),body.expectedVersion())!=1)throw ApiException.conflict("MCP_VERSION_CONFLICT");}
        auth.audit(auth.current(actor),"MCP_BINDING_SAVED",Map.of("toolVersionId",body.toolVersionId(),"enabled",body.enabled()));return Map.of("version",body.expectedVersion()+1);});}
    private ToolContract contract(Map<String,Object> row){try{return mapper.readValue((String)row.get("contract_json"),ToolContract.class);}catch(Exception e){throw new IllegalStateException("Invalid stored tool");}}
    public List<String> catalog(Identity node,String attempt,long epoch){return tx.execute(status->{locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);var granted=tools.catalog(lease.owner(),lease.taskId());var result=new ArrayList<String>();for(var tool:granted)if(tool.get("effect")==ToolContract.Effect.READ_ONLY && !db.queryForList("select tool_version_id from mcp_bindings where tenant_id=? and tool_version_id=? and enabled=true",node.tenant(),tool.get("toolVersionId")).isEmpty())result.add((String)tool.get("toolVersionId"));return result;});}
    private record Authorized(Map<String,Object> binding,ToolContract contract){}
    private Authorized authorize(Identity node,String attempt,String toolId,long epoch){return tx.execute(status->{locks.lock(node.tenant());var lease=control.modelLease(node,attempt,epoch,false);var registered=tools.authorizedVersion(lease.owner(),lease.taskId(),toolId);if(registered.contract().effect()!=ToolContract.Effect.READ_ONLY)throw ApiException.forbidden();var rows=db.queryForList("select * from mcp_bindings where tool_version_id=? and tenant_id=? and enabled=true",toolId,node.tenant());if(rows.isEmpty())throw ApiException.missing();return new Authorized(rows.get(0),registered.contract());});}
    public Map<String,Object> call(Identity node,String attempt,String toolId,Call body){var authorized=authorize(node,attempt,toolId,body.epoch());var input=authorized.contract().validate(body.arguments());var binding=authorized.binding();URI uri=endpoint((String)binding.get("endpoint"));String ref=(String)binding.get("credential_ref");String token=ref.isEmpty()?null:environment.getProperty(ref);
        if(!ref.isEmpty() && (token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126)))throw new ApiException(503,"MCP_CREDENTIAL_UNAVAILABLE","MCP credential unavailable");
        String text;try(var remote=new McpHttpClient(uri,token,mapper)){remote.initialize();JsonNode selected=null;for(JsonNode candidate:remote.tools())if(binding.get("remote_tool").equals(candidate.path("name").asText())){if(selected!=null)throw new IllegalArgumentException();selected=candidate;}
            if(selected==null || !mapper.valueToTree(authorized.contract().inputSchema()).equals(selected.path("inputSchema")))throw new ApiException(409,"MCP_SCHEMA_CHANGED","Remote schema differs from published contract");
            authorize(node,attempt,toolId,body.epoch());var result=remote.call((String)binding.get("remote_tool"),input);if(result.path("isError").asBoolean() || !result.path("content").isArray())throw new IllegalArgumentException();var output=new StringBuilder();for(JsonNode item:result.path("content")){if(!"text".equals(item.path("type").asText()) || !item.path("text").isTextual())throw new IllegalArgumentException();if(output.length()>0)output.append('\n');output.append(item.path("text").textValue());}text=output.toString();if(text.length()>50000)throw new IllegalArgumentException();
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(502,"MCP_CALL_FAILED","MCP invocation did not return a supported result");}
        authorize(node,attempt,toolId,body.epoch());auth.audit(auth.current(node),"MCP_READ",Map.of("attemptId",attempt,"toolVersionId",toolId));return Map.of("text",text,"businessVerified",false);
    }
}
