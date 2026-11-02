package com.funnywork;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class OfficeCapabilities {
    public record Configure(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{1,64}") String agentId,@NotBlank @Pattern(regexp="[a-z0-9-]{1,64}") String zone,@NotBlank @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") @Size(max=40) String version){}
    static final String INSTRUCTIONS="""
        Office/WPS file workflow: first list and inspect attached files, then create or edit using the authorized Office tools.
        Never infer workbook values from names. Use fixed artifact IDs and versions, preserve originals, and return saved file references.
        For DOCX, use semantic title/headings, concise paragraphs and headered tables. Inspect the created file and identify formatting needing human review.
        For PPTX, use short titles, at most six concise bullets per slide, bounded tables and speaker notes. Preserve existing slides when only editing text.
        For XLSX, keep numbers numeric, input text as text, and use explicit formula objects only for calculations. Include sensible number formats, column widths, frozen headers and a chart when requested.
        Formula operations are limited to a documented safe subset and bounded ranges. Formula errors must be fixed before delivery. Existing cached values are not proof of fresh calculation.
        office.edit.v1 always saves a separate revision copy, never edits the original file. Shape, paragraph and table indices are zero-based; Excel uses A1 addresses.
        Read the produced document with office.read.v1 after saving. Use pagination/textOffset when needed. Do not claim visual verification from structured text alone.
        If a task needs live document control, macros, animations or complex tracked changes, report the unsupported operation and use the deployment-approved application adapter; do not run arbitrary code or bypass authorization.
        The client provides file saving and default-app opening; the employee configures no Office credentials. These files remain unreviewed drafts, not verified business completion.
        """;
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ToolRegistry tools;private final PublishedSkills skills;
    public OfficeCapabilities(JdbcTemplate db,AuthService auth,TenantLocks locks,ToolRegistry tools,PublishedSkills skills){this.db=db;this.auth=auth;this.locks=locks;this.tools=tools;this.skills=skills;}
    @Transactional public Map<String,Object> configure(Identity actor,Configure body){locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(body.zone());
        tools.agentTools(actor,body.agentId(),body.zone(),"",1);for(var row:db.queryForList("select v.id from agent_tool_defaults d join tool_versions v on v.id=d.tool_version_id where d.tenant_id=? and d.agent_id=? and v.zone=? and v.name in ('artifact.list.v1','office.read.v1','office.create.v1','office.edit.v1') and v.version<>?",actor.tenant(),body.agentId(),body.zone(),body.version()))tools.agentDefault(actor,body.agentId(),(String)row.get("id"),false);
        for(var row:skills.defaults(actor,body.agentId(),body.zone()))if("office-documents".equals(row.get("name")) && !body.version().equals(row.get("version")))skills.bind(actor,body.agentId(),(String)row.get("id"),false);
        var ids=new ArrayList<String>();for(String template:List.of("attachment-list","office-read","office-create","office-edit")){var published=tools.publishTemplate(actor,template,new ToolRegistry.TemplatePublish(body.version(),body.zone()));String id=(String)published.get("toolVersionId");tools.agentDefault(actor,body.agentId(),id,true);ids.add(id);}
        var prior=db.queryForList("select id,content_hash,enabled from published_skills where tenant_id=? and zone=? and name='office-documents' and version=?",actor.tenant(),body.zone(),body.version());String skill;
        if(prior.isEmpty())skill=(String)skills.publish(actor,new PublishedSkills.Publish("office-documents",body.version(),body.zone(),INSTRUCTIONS)).get("id");else{var row=prior.get(0);if(!Boolean.TRUE.equals(row.get("enabled")) || !AuthService.hash(INSTRUCTIONS).equals(row.get("content_hash")))throw ApiException.conflict("OFFICE_SKILL_VERSION_CONFLICT");skill=(String)row.get("id");}skills.bind(actor,body.agentId(),skill,true);
        auth.audit(actor,"OFFICE_CAPABILITIES_CONFIGURED",Map.of("agentId",body.agentId(),"zone",body.zone(),"version",body.version()));return Map.of("tools",ids,"skillId",skill,"newTasksOnly",true,"liveSessionControl",false);
    }
    @Transactional(readOnly=true) public Map<String,Object> status(Identity actor,String agent,String zone){tools.agentTools(actor,agent,zone,"",1);var rows=db.queryForList("select v.id,v.name,v.version,v.enabled,d.tool_version_id from tool_versions v left join agent_tool_defaults d on d.tool_version_id=v.id and d.agent_id=? where v.tenant_id=? and v.zone=? and v.name in ('artifact.list.v1','office.read.v1','office.create.v1','office.edit.v1') order by v.name,v.version",agent,actor.tenant(),zone);return Map.of("items",rows,"skills",skills.defaults(actor,agent,zone),"formats",List.of("docx","pptx","xlsx"),"liveSessionControl",false,"maxFileBytes",OfficeDocuments.MAX_FILE);}
}
