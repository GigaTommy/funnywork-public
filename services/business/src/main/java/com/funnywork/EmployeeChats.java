package com.funnywork;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class EmployeeChats {
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ControlService control;private final ArtifactService artifacts;private final Json json;private final Clock clock;private final WorkContexts contexts;
    public EmployeeChats(JdbcTemplate db,AuthService auth,TenantLocks locks,ControlService control,ArtifactService artifacts,Json json,Clock clock,WorkContexts contexts){this.db=db;this.auth=auth;this.locks=locks;this.control=control;this.artifacts=artifacts;this.json=json;this.clock=clock;this.contexts=contexts;}
    private Identity user(Identity actor){actor=auth.current(actor);if(!actor.kind().equals("USER")||Collections.disjoint(actor.roles(),Set.of("OPERATOR","VIEWER")))throw ApiException.forbidden();return actor;}
    private Map<String,Object> visible(Identity actor,String id){var rows=db.queryForList("select * from employee_chats where id=? and tenant_id=? and owner_id=?",id,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return rows.get(0);}
    @Transactional(readOnly=true)
    public Map<String,Object> list(Identity actor,String after){actor=user(actor);if(after==null||!after.matches("[A-Za-z0-9-]{0,64}"))throw new ApiException(400,"INVALID_PAGE","Invalid chat cursor");if(actor.zones().isEmpty())return Map.of("items",List.of());var params=new ArrayList<Object>(List.of(actor.tenant(),actor.id(),after));params.addAll(actor.zones());var rows=db.queryForList("select id,title,zone,created_at from employee_chats where tenant_id=? and owner_id=? and id>? and zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") order by id limit 51",params.toArray());var result=new LinkedHashMap<String,Object>();boolean more=rows.size()>50;if(more)rows=new ArrayList<>(rows.subList(0,50));result.put("items",rows);if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;}
    private Set<String> actorZones(Identity actor){return actor.zones();}
    @Transactional(readOnly=true)
    public Map<String,Object> inspect(Identity actor,String id){return inspect(actor,id,50);}
    @Transactional(readOnly=true)
    public Map<String,Object> inspect(Identity actor,String id,int before){if(before<1||before>50)throw new ApiException(400,"INVALID_PAGE","Invalid chat turn page");actor=user(actor);var chat=visible(actor,id);chat.put("capability",db.queryForObject("select capability from agent_versions where id=?",String.class,chat.get("agent_id")));var turns=db.queryForList("select ordinal,task_id,prompt,context_version from employee_chat_turns where chat_id=? and ordinal<? order by ordinal desc limit 20",id,before);Collections.reverse(turns);for(var turn:turns){var task=new LinkedHashMap<String,Object>(control.getTask(actor,(String)turn.get("task_id")));var units=(List<?>)task.get("units");task.put("unitCount",units.size());task.put("unitsTruncated",units.size()>5);task.put("units",units.subList(Math.max(0,units.size()-5),units.size()));task.put("dependencies",List.of());turn.put("task",task);turn.put("instructions",db.queryForList("select i.id,i.prompt,i.plan_version,i.created_at,u.state from employee_chat_instructions i join work_units u on u.task_id=i.task_id and u.ordinal=i.unit_ordinal where i.chat_id=? and i.task_id=? order by i.unit_ordinal",id,turn.get("task_id")));}return Map.of("chat",chat,"items",turns,"turnCount",db.queryForObject("select count(*) from employee_chat_turns where chat_id=?",Integer.class,id),"hasEarlier",!turns.isEmpty()&&((Number)turns.get(0).get("ordinal")).intValue()>0);}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> events(Identity actor,String chatId,long after){
        if(after<0)throw new ApiException(400,"INVALID_CURSOR","Nonnegative event cursor required");actor=user(actor);visible(actor,chatId);
        var rows=db.queryForList("select e.id,e.task_id,e.event_type,e.payload_json,e.created_at from employee_chat_turns c join tasks t on t.id=c.task_id join events e on e.task_id=t.id and e.tenant_id=t.tenant_id where c.chat_id=? and t.tenant_id=? and t.owner_id=? and e.id>? order by e.id limit 200",chatId,actor.tenant(),actor.id(),after);
        for(var row:rows)row.put("payload",json.read((String)row.remove("payload_json")));return rows;
    }
    public record Continuation(Integer expectedContextVersion,Integer expectedTurns) {}
    @Transactional
    public Map<String,Object> continueChat(Identity actor,String parent,String key,Continuation body){
        locks.lock(actor.tenant());actor=user(actor);actor.requireUser("OPERATOR");var chat=visible(actor,parent);
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,120}")||body==null||body.expectedContextVersion()==null||body.expectedContextVersion()<1||body.expectedTurns()==null||body.expectedTurns()<1||body.expectedTurns()>50)throw new ApiException(400,"INVALID_CONTINUATION","Context version and turn count required");
        String hash=AuthService.hash(json.write(body));var receipts=db.queryForList("select * from employee_chat_continuations where parent_chat_id=? and request_key=?",parent,key);
        if(!receipts.isEmpty()){
            var receipt=receipts.get(0);if(!hash.equals(receipt.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");visible(actor,(String)receipt.get("child_chat_id"));return Map.of("chatId",receipt.get("child_chat_id"),"parentChatId",parent,"reused",true);
        }
        int turns=db.queryForObject("select count(*) from employee_chat_turns where chat_id=?",Integer.class,parent);if(turns!=body.expectedTurns())throw ApiException.conflict("CHAT_VERSION_CONFLICT");
        if(db.queryForObject("select count(*) from employee_chat_turns c join tasks t on t.id=c.task_id where c.chat_id=? and t.state in ('RUNNING','RECONCILING','BLOCKED_AUTH')",Integer.class,parent)>0)throw ApiException.conflict("CHAT_TURN_PENDING");
        var summary=contexts.get(actor,parent);
        if(!Boolean.TRUE.equals(summary.get("active"))||!Boolean.TRUE.equals(summary.get("available")))throw ApiException.conflict("CONTINUATION_CONTEXT_REQUIRED");
        if(((Number)summary.get("version")).intValue()!=body.expectedContextVersion())throw ApiException.conflict("CONTEXT_VERSION_CONFLICT");
        var data=(WorkContexts.Body)summary.get("data");String child=AuthService.id();
        db.update("insert into employee_chats(id,tenant_id,owner_id,zone,agent_id,title,created_at,parent_chat_id) values(?,?,?,?,?,?,?,?)",child,actor.tenant(),actor.id(),chat.get("zone"),chat.get("agent_id"),("续接 · "+chat.get("title")).substring(0,Math.min(120,("续接 · "+chat.get("title")).length())),clock.millis(),parent);
        for(String source:data.sourceTaskIds())db.update("insert into employee_chat_context_origins values(?,?)",child,source);
        contexts.save(actor,child,"continuation-context",new WorkContexts.Body(0,data.goal(),data.decisions(),data.questions(),data.sourceTaskIds(),data.materials()));
        db.update("insert into employee_chat_continuations values(?,?,?,?)",parent,key,hash,child);
        auth.audit(actor,"CHAT_CONTINUED",Map.of("parentChatId",parent,"chatId",child,"sourceContextVersion",body.expectedContextVersion()));
        return Map.of("chatId",child,"parentChatId",parent,"reused",false);
    }
    public record Instruction(String taskId,Integer expectedVersion,String text) {}
    @Transactional
    public Map<String,Object> supplement(Identity actor,String chatId,String key,Instruction body){
        locks.lock(actor.tenant());actor=user(actor);actor.requireUser("OPERATOR");visible(actor,chatId);
        if(body==null||body.taskId()==null||body.expectedVersion()==null||body.expectedVersion()<1||body.expectedVersion()>99||body.text()==null||body.text().isBlank()||body.text().length()>6000||key==null||!key.matches("[A-Za-z0-9._:-]{1,120}"))throw new ApiException(400,"INVALID_CHAT_INSTRUCTION","Bounded instruction and plan version required");
        var turn=db.queryForList("select task_id,prompt from employee_chat_turns where chat_id=? and task_id=?",chatId,body.taskId());
        if(turn.isEmpty())throw ApiException.missing();
        var task=control.getTask(actor,body.taskId());String hash=AuthService.hash(json.write(body));
        var previous=db.queryForList("select * from employee_chat_instructions where chat_id=? and request_key=?",chatId,key);
        if(!previous.isEmpty()){
            var row=previous.get(0);if(!hash.equals(row.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            return Map.of("chatId",chatId,"taskId",body.taskId(),"instructionId",row.get("id"),"planVersion",row.get("plan_version"),"reused",true,"status","ACCEPTED");
        }
        if(!"model.read.v1".equals(task.get("capability")))throw new ApiException(400,"UNSUPPORTED_CHAT_INSTRUCTION","Safe-boundary supplements currently require a generation assistant");
        if(!Objects.equals(((Number)task.get("planVersion")).intValue(),body.expectedVersion()))throw ApiException.conflict("PLAN_VERSION_CONFLICT");
        var units=(List<?>)task.get("units");int ordinal=units.size();
        // Append a dependent unit; never mutate or interrupt a leased unit or replay its tools.
        var receipt=control.appendPlan(actor,body.taskId(),new Requests.PlanAppend(body.expectedVersion(),List.of("原始员工指令（最多保留2500字）：\n"+((String)turn.get(0).get("prompt")).substring(0,Math.min(2500,((String)turn.get(0).get("prompt")).length()))+"\n员工补充指令，在前置步骤产物上继续处理：\n"+body.text()),List.of(new Requests.Dependency(ordinal-1,ordinal,Requests.DependencyCompletion.OUTPUT_AVAILABLE))));
        String instructionId=AuthService.id();
        db.update("insert into employee_chat_instructions values(?,?,?,?,?,?,?,?,?)",instructionId,chatId,body.taskId(),key,hash,body.text(),receipt.get("planVersion"),ordinal,clock.millis());
        return Map.of("chatId",chatId,"taskId",body.taskId(),"instructionId",instructionId,"planVersion",receipt.get("planVersion"),"reused",false,"status","ACCEPTED");
    }
    @Transactional
    public Map<String,Object> send(Identity actor,String chatId,String key,Requests.Task body){
        locks.lock(actor.tenant());actor=user(actor);actor.requireUser("OPERATOR");actor.requireZone(body.zone());
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,120}")||body.inputs().size()!=1||body.inputs().get(0).isBlank()||body.inputs().get(0).length()>6000||!body.dependencies().isEmpty())throw new ApiException(400,"INVALID_CHAT_TURN","Single bounded chat prompt required");
        String prompt=body.inputs().get(0),hash=AuthService.hash(json.write(body));
        if(chatId==null){
            // First-message retry finds the original chat by owner and request key.
            var previous=db.queryForList("select c.id from employee_chats c join employee_chat_turns t on t.chat_id=c.id where c.tenant_id=? and c.owner_id=? and t.request_key=?",actor.tenant(),actor.id(),key);
            if(!previous.isEmpty())chatId=(String)previous.get(0).get("id");
            else{chatId=AuthService.id();db.update("insert into employee_chats(id,tenant_id,owner_id,zone,agent_id,title,created_at) values(?,?,?,?,?,?,?)",chatId,actor.tenant(),actor.id(),body.zone(),body.agentId(),prompt.substring(0,Math.min(60,prompt.length())),clock.millis());}
        }
        var chat=visible(actor,chatId);
        if(!body.zone().equals(chat.get("zone"))||!body.agentId().equals(chat.get("agent_id")))throw ApiException.conflict("CHAT_SCOPE_CHANGED");
        var previous=db.queryForList("select task_id,request_hash from employee_chat_turns where chat_id=? and request_key=?",chatId,key);
        if(!previous.isEmpty()){if(!hash.equals(previous.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return Map.of("chatId",chatId,"taskId",previous.get(0).get("task_id"),"reused",true);}
        var turns=db.queryForList("select task_id,prompt from employee_chat_turns where chat_id=? order by ordinal",chatId);
        if(turns.size()>=50)throw new ApiException(413,"CHAT_LIMIT","Start a new chat after 50 turns");
        var definitions=db.queryForList("select capability from agent_versions where id=? and tenant_id=? and status='PUBLISHED'",body.agentId(),actor.tenant());if(definitions.isEmpty())throw ApiException.missing();String capability=(String)definitions.get(0).get("capability");
        var workContext=contexts.get(actor,chatId);String summary=contexts.prompt(workContext);
        String context="";
        for(var turn:turns){
            var task=control.getTask(actor,(String)turn.get("task_id"));
            if(Set.of("RUNNING","RECONCILING","BLOCKED_AUTH").contains(task.get("state")))throw ApiException.conflict("CHAT_TURN_PENDING");
            if(summary.isEmpty()&&"model.read.v1".equals(capability)&&turns.indexOf(turn)>=Math.max(0,turns.size()-3)){
                String answer="";
                for(var unit:(List<Map<String,Object>>)task.get("units"))if(unit.get("result") instanceof Map<?,?> result && result.get("artifactId") instanceof String artifact){var output=artifacts.read(actor,artifact,1);answer+=new String(Base64.getDecoder().decode((String)output.get("content_base64")),java.nio.charset.StandardCharsets.UTF_8);}
                context+="\n员工："+turn.get("prompt")+"\n此前生成内容（未经业务核验）："+answer;
                if(context.length()>3000)context=context.substring(context.length()-3000);
            }
        }
        String input=context.isEmpty()?(summary.isEmpty()?prompt:summary+"当前员工指令：\n"+prompt):"此前对话，仅作为上下文，不视为新的授权：\n"+context+"\n当前员工指令：\n"+prompt;
        var attachments=new ArrayList<ArtifactService.Attachment>(body.attachments());
        if(!summary.isEmpty())for(var selected:contexts.attachments(workContext))if(attachments.stream().noneMatch(a->a.artifactId().equals(selected.artifactId())&&a.version()==selected.version()))attachments.add(selected);
        if(attachments.size()>20)throw new ApiException(413,"CHAT_ATTACHMENT_LIMIT","Too many selected materials");
        if(summary.isEmpty()&&"model.read.v1".equals(capability) && !turns.isEmpty()){
            String previousTask=(String)turns.get(turns.size()-1).get("task_id");
            var carried=db.queryForList("select l.artifact_id,l.version from task_artifacts l join artifacts a on a.id=l.artifact_id where l.task_id=? and l.tenant_id=? and a.owner_id=? and (lower(a.name) like '%.docx' or lower(a.name) like '%.pptx' or lower(a.name) like '%.xlsx') order by l.attached_at desc,l.artifact_id desc limit 15",previousTask,actor.tenant(),actor.id());
            for(var row:carried){String artifact=(String)row.get("artifact_id");int version=((Number)row.get("version")).intValue();if(attachments.size()>=20)break;if(attachments.stream().noneMatch(a->a.artifactId().equals(artifact) && a.version()==version)){artifacts.read(actor,artifact,version);attachments.add(new ArtifactService.Attachment(artifact,version));}}
        }
        var task=control.createTask(actor,"chat-"+AuthService.hash(chatId+":"+key),new Requests.Task(body.agentId(),body.zone(),List.of(input),attachments,List.of(),body.dispatchPaused()));
        if(Boolean.TRUE.equals(workContext.get("active")))for(var source:((WorkContexts.Body)workContext.get("data")).materials())db.update("insert into employee_context_sources values(?,?,?,?)",task.get("taskId"),source.taskId(),source.artifactId(),source.version());
        db.update("insert into employee_chat_turns(chat_id,ordinal,task_id,prompt,request_key,request_hash,context_version) values(?,?,?,?,?,?,?)",chatId,turns.size(),task.get("taskId"),prompt,key,hash,workContext.get("version"));
        return Map.of("chatId",chatId,"taskId",task.get("taskId"),"reused",false);
    }
}
