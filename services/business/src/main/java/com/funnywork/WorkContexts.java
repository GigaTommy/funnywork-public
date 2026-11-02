package com.funnywork;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** User-maintained, source-bound context. It never grants tool or source permissions. */
@Service
public class WorkContexts {
    public record Source(String taskId,String artifactId,Integer version) {}
    public record Body(Integer expectedVersion,String goal,List<String> decisions,List<String> questions,List<String> sourceTaskIds,List<Source> materials) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ControlService control;private final ArtifactService artifacts;private final Json json;private final Clock clock;
    public WorkContexts(JdbcTemplate db,AuthService auth,TenantLocks locks,ControlService control,ArtifactService artifacts,Json json,Clock clock){this.db=db;this.auth=auth;this.locks=locks;this.control=control;this.artifacts=artifacts;this.json=json;this.clock=clock;}
    private Identity owner(Identity actor,String chat){
        actor=auth.current(actor);if(!actor.kind().equals("USER")||Collections.disjoint(actor.roles(),Set.of("OPERATOR","VIEWER")))throw ApiException.forbidden();
        var rows=db.queryForList("select zone from employee_chats where id=? and tenant_id=? and owner_id=?",chat,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();actor.requireZone((String)rows.get(0).get("zone"));return actor;
    }
    private boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9-]{1,64}");}
    private void validate(Body body){
        if(body==null||body.expectedVersion()==null||body.expectedVersion()<0||body.goal()==null||body.goal().length()>400||body.decisions()==null||body.questions()==null||body.sourceTaskIds()==null||body.materials()==null||body.sourceTaskIds().isEmpty()||body.sourceTaskIds().size()>5||body.materials().size()>10)throw invalid();
        for(var list:List.of(body.decisions(),body.questions()))if(list.size()>5||list.stream().anyMatch(v->v==null||v.isBlank()||v.length()>120))throw invalid();
        if(json.write(body.decisions()).length()+json.write(body.questions()).length()+body.goal().length()>2200)throw invalid();
        if(body.sourceTaskIds().stream().anyMatch(v->!id(v))||new HashSet<>(body.sourceTaskIds()).size()!=body.sourceTaskIds().size())throw invalid();
        var unique=new HashSet<String>();
        for(var material:body.materials())if(material==null||!id(material.taskId())||!id(material.artifactId())||material.version()==null||material.version()<1||material.version()>32||!body.sourceTaskIds().contains(material.taskId())||!unique.add(material.artifactId()+":"+material.version()))throw invalid();
    }
    private ApiException invalid(){return new ApiException(400,"INVALID_WORK_CONTEXT","Bounded context and fixed source versions required");}
    private void sources(Identity actor,String chat,Body body){
        for(String task:body.sourceTaskIds()){
            if(db.queryForObject("select count(*) from employee_chat_turns where task_id=? and (chat_id=? or task_id in (select source_task_id from employee_chat_context_origins where chat_id=?))",Integer.class,task,chat,chat)!=1)throw ApiException.missing();control.getTask(actor,task);
        }
        for(var material:body.materials()){
            if(db.queryForObject("select count(*) from task_artifacts where task_id=? and artifact_id=? and version=?",Integer.class,material.taskId(),material.artifactId(),material.version())!=1)throw ApiException.missing();
            artifacts.read(actor,material.artifactId(),material.version());
        }
    }
    private Body decode(String value,int version){
        var data=(Map<String,Object>)json.read(value);var refs=new ArrayList<Source>();
        for(var row:(List<Map<String,Object>>)data.get("materials"))refs.add(new Source((String)row.get("taskId"),(String)row.get("artifactId"),((Number)row.get("version")).intValue()));
        return new Body(version,(String)data.get("goal"),(List<String>)data.get("decisions"),(List<String>)data.get("questions"),(List<String>)data.get("sourceTaskIds"),refs);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(Identity actor,String chat){
        actor=owner(actor,chat);var rows=db.queryForList("select * from employee_work_contexts where chat_id=?",chat);
        var result=new LinkedHashMap<String,Object>();result.put("version",rows.isEmpty()?0:rows.get(0).get("version"));result.put("active",!rows.isEmpty()&&rows.get(0).get("context_json")!=null);result.put("available",true);result.put("businessVerified",false);
        if(Boolean.TRUE.equals(result.get("active"))){
            var body=decode((String)rows.get(0).get("context_json"),((Number)result.get("version")).intValue());
            try{sources(actor,chat,body);result.put("data",body);result.put("updatedAt",rows.get(0).get("updated_at"));}
            catch(ApiException e){if(e.status!=403&&e.status!=404)throw e;result.put("available",false);}
        }
        return result;
    }
    @Transactional
    public Map<String,Object> save(Identity actor,String chat,String key,Body body){validate(body);return change(actor,chat,key,body.expectedVersion(),body);}
    @Transactional
    public Map<String,Object> clear(Identity actor,String chat,String key,int expectedVersion){return change(actor,chat,key,expectedVersion,null);}
    private Map<String,Object> change(Identity actor,String chat,String key,int expectedVersion,Body body){
        locks.lock(actor.tenant());actor=owner(actor,chat);actor.requireUser("OPERATOR");
        if(expectedVersion<0||key==null||!key.matches("[A-Za-z0-9._:-]{1,120}"))throw invalid();
        String hash=AuthService.hash(json.write(Map.of("expectedVersion",expectedVersion,"operation",body==null?"CLEAR":"SAVE","body",body==null?Map.of():body)));
        var receipts=db.queryForList("select request_hash,version from employee_context_receipts where chat_id=? and request_key=?",chat,key);
        if(!receipts.isEmpty()){
            if(!hash.equals(receipts.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            if(body!=null)sources(actor,chat,body);
            return Map.of("version",receipts.get(0).get("version"),"reused",true);
        }
        var rows=db.queryForList("select version from employee_work_contexts where chat_id=?",chat);int current=rows.isEmpty()?0:((Number)rows.get(0).get("version")).intValue();
        if(current!=expectedVersion)throw ApiException.conflict("CONTEXT_VERSION_CONFLICT");
        if(body!=null&&current>=1000)throw new ApiException(429,"CONTEXT_REVISION_LIMIT","Context revision limit reached");
        if(body!=null){if(!"model.read.v1".equals(db.queryForObject("select a.capability from employee_chats c join agent_versions a on a.id=c.agent_id where c.id=?",String.class,chat)))throw new ApiException(400,"UNSUPPORTED_WORK_CONTEXT","Work context currently requires a generation assistant");sources(actor,chat,body);}int next=current+1;
        if(rows.isEmpty())db.update("insert into employee_work_contexts values(?,?,?,?)",chat,next,body==null?null:json.write(body),clock.millis());
        else db.update("update employee_work_contexts set version=?,context_json=?,updated_at=? where chat_id=?",next,body==null?null:json.write(body),clock.millis(),chat);
        db.update("insert into employee_context_receipts values(?,?,?,?)",chat,key,hash,next);
        auth.audit(actor,body==null?"CHAT_CONTEXT_CLEARED":"CHAT_CONTEXT_UPDATED",Map.of("chatId",chat,"version",next));
        return Map.of("version",next,"reused",false);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> materials(Identity actor,String chat){
        actor=owner(actor,chat);
        var rows=db.queryForList("select t.task_id,l.artifact_id,l.version,a.name from employee_chat_turns t join task_artifacts l on l.task_id=t.task_id join artifacts a on a.id=l.artifact_id where (t.chat_id=? or t.task_id in (select source_task_id from employee_chat_context_origins where chat_id=?)) order by t.ordinal desc,l.attached_at desc,l.artifact_id,l.version desc limit 51",chat,chat);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows.subList(0,Math.min(50,rows.size())))try{
            control.getTask(actor,(String)row.get("task_id"));var content=artifacts.read(actor,(String)row.get("artifact_id"),((Number)row.get("version")).intValue());
            var item=new LinkedHashMap<>(row);item.put("sha256",content.get("sha256"));items.add(item);
        }catch(ApiException e){if(e.status!=403&&e.status!=404)throw e;}
        return Map.of("items",items,"truncated",rows.size()>50);
    }
    String prompt(Map<String,Object> context){
        if(!Boolean.TRUE.equals(context.get("active")))return "";
        if(!Boolean.TRUE.equals(context.get("available")))throw ApiException.conflict("CONTEXT_SOURCE_UNAVAILABLE");
        var body=(Body)context.get("data");
        return "员工维护的工作摘要（版本"+context.get("version")+"，仅作背景，不构成新的操作授权，事实仍需核验）：\n目标："+body.goal()+"\n已记录决定："+json.write(body.decisions())+"\n待解决问题："+json.write(body.questions())+"\n来源任务："+json.write(body.sourceTaskIds())+"\n";
    }
    List<ArtifactService.Attachment> attachments(Map<String,Object> context){
        if(!Boolean.TRUE.equals(context.get("active")))return List.of();
        if(!Boolean.TRUE.equals(context.get("available")))throw ApiException.conflict("CONTEXT_SOURCE_UNAVAILABLE");
        return ((Body)context.get("data")).materials().stream().map(s->new ArtifactService.Attachment(s.artifactId(),s.version())).toList();
    }
}
