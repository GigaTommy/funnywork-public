package com.funnywork;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Controller
@ResponseBody
@RequestMapping("/api/v1/client-policy")
public class ClientPolicyController {
    public record Entry(@NotNull @Pattern(regexp="[a-z0-9-]{1,64}") String id,@NotBlank @Size(max=120) String name,
                        @NotNull @Pattern(regexp="IM|DOCUMENT|WORKFLOW|OFFICE|MAIL|COMPUTER|BROWSER|CODE|SKILL|MCP") String category,
                        @NotNull @Pattern(regexp="RESERVED|AVAILABLE") String status,@Size(max=64) String agentId,@Size(max=500) String description) {}
    public record Policy(@Min(0) int expectedVersion,@NotNull @Pattern(regexp="MICROSOFT_MANAGED") String signInMode,
                         @NotNull @Size(max=64) String identityProviderId,@NotNull @Size(max=50) List<@NotNull @Valid Entry> entries) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;
    public ClientPolicyController(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json){this.db=db;this.auth=auth;this.locks=locks;this.json=json;}
    private Policy defaults(){return new Policy(0,"MICROSOFT_MANAGED","",List.of(
        reserved("lanxin","蓝信","IM"),reserved("wecom","企业微信","IM"),reserved("feishu","飞书","IM"),reserved("dingtalk","钉钉","IM"),reserved("ruanhong","北京软虹公文系统","DOCUMENT"),reserved("workflow","内部流程系统","WORKFLOW"),reserved("wps","WPS","OFFICE"),reserved("coremail-web","Coremail 网页邮箱","MAIL"),reserved("coremail-client","Coremail 客户端","MAIL"),reserved("computer","Computer use","COMPUTER"),reserved("browser","Browser use","BROWSER"),reserved("code","基础代码开发","CODE"),reserved("skills","服务端 Skills","SKILL"),reserved("mcp","服务端 MCP","MCP")) );}
    private Entry reserved(String id,String name,String category){return new Entry(id,name,category,"RESERVED","","由服务端维护、发布和授权，客户端只负责使用。");}
    @GetMapping @Transactional(readOnly=true)
    public Map<String,Object> get(@AuthenticationPrincipal Identity actor){actor=auth.current(actor);if(!actor.kind().equals("USER")||Collections.disjoint(actor.roles(),Set.of("ADMIN","OPERATOR","VIEWER")))throw ApiException.forbidden();var rows=db.queryForList("select version,policy_json from employee_client_policy where tenant_id=?",actor.tenant());return rows.isEmpty()?Map.of("version",0,"policy",defaults(),"identityReady",false):Map.of("version",rows.get(0).get("version"),"policy",json.read((String)rows.get(0).get("policy_json")),"identityReady",false);}
    @PutMapping @Transactional
    public Map<String,Object> save(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Policy policy){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var unique=new HashSet<String>();
        for(var entry:policy.entries()){
            if(!unique.add(entry.id()))throw new ApiException(400,"DUPLICATE_ENTRY","Duplicate capability entry");
            if(entry.status().equals("AVAILABLE")){
                if(entry.agentId()==null||!entry.agentId().matches("[A-Za-z0-9-]{1,64}")||db.queryForObject("select count(*) from agent_versions where id=? and tenant_id=? and status='PUBLISHED'",Integer.class,entry.agentId(),actor.tenant())!=1)throw new ApiException(400,"AGENT_REQUIRED","Available entry requires a published tenant agent");
            }
        }
        var rows=db.queryForList("select version from employee_client_policy where tenant_id=?",actor.tenant());int version=rows.isEmpty()?0:((Number)rows.get(0).get("version")).intValue();
        if(version!=policy.expectedVersion())throw ApiException.conflict("POLICY_VERSION_CONFLICT");
        if(rows.isEmpty())db.update("insert into employee_client_policy values(?,?,?)",actor.tenant(),1,json.write(policy));else db.update("update employee_client_policy set version=?,policy_json=? where tenant_id=?",version+1,json.write(policy),actor.tenant());
        auth.audit(actor,"CLIENT_POLICY_SAVED",Map.of("version",version+1,"entryCount",policy.entries().size()));return Map.of("version",version+1);
    }
}
