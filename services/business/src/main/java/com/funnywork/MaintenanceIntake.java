package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Raw sources are durable before bounded native analysis; only configured operator authority admits repair tasks. */
@Service
public class MaintenanceIntake {
    public record Configuration(@NotBlank String repositoryId,@NotNull @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String baseSha,
        @NotEmpty @Size(max=20) List<@NotBlank @Size(max=200) String> allowedPaths,boolean autoDispatch,boolean githubEnabled,
        @NotNull @Pattern(regexp="(?:FUNNYWORK_GITHUB_[A-Z0-9_]{1,80})?") String githubTokenRef,@NotNull @Pattern(regexp="macos|windows|linux") String defaultPlatform) {}
    public record Feedback(@NotBlank String repositoryId,@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,
        @NotBlank @Size(max=160) String title,@NotBlank @Size(max=6000) String actual,
        @NotBlank @Size(max=6000) String expected,@NotBlank @Size(max=6000) String steps,
        @NotNull @Pattern(regexp="macos|windows|linux") String platform,@NotNull @Size(max=80) String clientVersion) {}
    public record Supplement(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,@NotBlank @Size(max=6000) String content) {}
    @Transactional public Object supplement(Identity actor,String id,Supplement body){
        locks.lock(actor.tenant());actor=user(actor);detail(actor,id,false);
        var prior=db.queryForList("select content,review_state from maintenance_feedback_messages where intake_id=? and author_id=? and request_id=?",id,actor.id(),body.requestId());
        if(!prior.isEmpty()){if(!body.content().equals(prior.get(0).get("content")))throw ApiException.conflict("FEEDBACK_REQUEST_CONFLICT");return Map.of("accepted",true,"reused",true,"held","HELD".equals(prior.get(0).get("review_state")));}
        var current=one("select * from maintenance_intakes where id=? and tenant_id=?",id,actor.tenant());
        if(!"NEEDS_INFORMATION".equals(current.get("state"))||current.get("problem_id")!=null)throw ApiException.conflict("FEEDBACK_NOT_AWAITING_INFORMATION");
        if(MaintenanceIntakePolicy.protectedRequest("",Map.of(),List.of(Map.of("expected",body.content()))).isPresent()){
            db.update("insert into maintenance_feedback_messages(id,tenant_id,intake_id,author_id,request_id,content,created_at,review_state) values(?,?,?,?,?,?,?,'HELD')",AuthService.id(),actor.tenant(),id,actor.id(),body.requestId(),body.content(),clock.millis());auth.audit(actor,"MAINTENANCE_FEEDBACK_NOTE_HELD",Map.of("intakeId",id));return Map.of("accepted",true,"reused",false,"held",true);
        }
        String content=current.get("body")+"\n\nAdditional information:\n"+body.content().trim();
        if(content.length()>20000)throw ApiException.conflict("INVALID_FEEDBACK");
        if(((Number)current.get("revision")).intValue()==Integer.MAX_VALUE)throw ApiException.conflict("FEEDBACK_REVISION_EXHAUSTED");
        var source=one("select id,payload_json from maintenance_intake_sources where intake_id=? and reporter_id=? and source_kind='CLIENT' order by updated_at desc,id desc limit 1",id,actor.id());
        var sourcePayload=new LinkedHashMap<String,Object>();((Map<?,?>)json.read((String)source.get("payload_json"))).forEach((key,value)->sourcePayload.put(String.valueOf(key),value));sourcePayload.put("title",current.get("title"));sourcePayload.put("body",content);sourcePayload.put("platform",current.get("platform"));String payload=json.canonical(sourcePayload);
        db.update("insert into maintenance_feedback_messages(id,tenant_id,intake_id,author_id,request_id,content,created_at) values(?,?,?,?,?,?,?)",AuthService.id(),actor.tenant(),id,actor.id(),body.requestId(),body.content(),clock.millis());
        db.update("update maintenance_intake_sources set payload_json=?,source_revision=?,updated_at=? where id=?",payload,AuthService.hash(payload),clock.millis(),source.get("id"));
        db.update("update maintenance_intakes set body=?,revision=revision+1,state='RECEIVED',analysis_json=null,last_checked_at=0,updated_at=? where id=?",content,clock.millis(),id);
        auth.audit(actor,"MAINTENANCE_FEEDBACK_SUPPLEMENTED",Map.of("intakeId",id));return Map.of("accepted",true,"reused",false,"held",false);
    }
    public record Analysis(@NotNull @Pattern(regexp="READY|NEEDS_INFORMATION|DECLINED|DUPLICATE|RUNTIME_FAILED") String outcome,
        @NotNull @Size(max=160) String title,@NotNull @Size(max=10000) String requirement,@NotNull @Size(max=10000) String acceptance,
        @NotNull @Size(max=2000) String reason,@NotNull @Size(max=20) List<@NotBlank @Size(max=200) String> paths,String duplicateOf,@Pattern(regexp="BUG|IMPROVEMENT|QUESTION|OTHER") String category) {
        public Analysis(String outcome,String title,String requirement,String acceptance,String reason,List<String> paths,String duplicateOf){this(outcome,title,requirement,acceptance,reason,paths,duplicateOf,null);}
    }
    public record Receipt(@NotBlank String runtimeEpoch,@Min(1) int revision,@NotNull @Pattern(regexp="[a-f0-9]{64}") String specHash,
        @Valid @NotNull Analysis analysis,@Valid MaintenanceService.ModelUsage modelUsage) {}
    public record Renewal(@NotBlank String runtimeEpoch,@Min(1) int revision) {}
    public record Recovery(@NotBlank @Size(max=2000) String note,boolean stopped) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceMatches matches;
    private final MaintenanceService maintenance;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private DesktopPolicies policies;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionResources executionResources;
    public MaintenanceIntake(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,MaintenanceService maintenance,Clock clock){
        this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.maintenance=maintenance;this.clock=clock;
    }
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private Identity user(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();return actor;}
    private Map<String,Object> repository(Identity actor,String id){var r=one("select * from maintenance_repositories where id=? and tenant_id=? and enabled=true",id,actor.tenant());actor.requireZone((String)r.get("zone"));return r;}
    @Transactional public Object configure(Identity actor,Configuration body){
        locks.lock(actor.tenant());actor=user(actor);actor.requireUser("ADMIN");repository(actor,body.repositoryId());
        if(body.allowedPaths().stream().anyMatch(p->!p.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||p.contains("..")))throw ApiException.conflict("INVALID_MAINTENANCE_SCOPE");
        var old=db.queryForList("select * from maintenance_intake_configs where repository_id=?",body.repositoryId());
        if(old.isEmpty())db.update("insert into maintenance_intake_configs(repository_id,tenant_id,operator_id,base_sha,allowed_paths,default_platform,auto_dispatch,github_enabled,github_token_ref,github_since,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",body.repositoryId(),actor.tenant(),actor.id(),body.baseSha(),json.write(body.allowedPaths()),body.defaultPlatform(),body.autoDispatch(),body.githubEnabled(),body.githubTokenRef(),java.time.Instant.ofEpochMilli(clock.millis()).toString(),clock.millis());
        else {
            if(db.queryForObject("select count(*) from maintenance_intake_attempts where intake_id in (select id from maintenance_intakes where repository_id=?) and state='RUNNING'",Integer.class,body.repositoryId())>0)throw ApiException.conflict("INTAKE_ANALYSIS_ACTIVE");
            db.update("update maintenance_intake_configs set operator_id=?,base_sha=?,allowed_paths=?,default_platform=?,auto_dispatch=?,github_enabled=?,github_token_ref=?,updated_at=? where repository_id=?",actor.id(),body.baseSha(),json.write(body.allowedPaths()),body.defaultPlatform(),body.autoDispatch(),body.githubEnabled(),body.githubTokenRef(),clock.millis(),body.repositoryId());
        }
        auth.audit(actor,"MAINTENANCE_INTAKE_CONFIGURED",Map.of("repositoryId",body.repositoryId(),"autoDispatch",body.autoDispatch()));return Map.of("configured",true);
    }
    @Transactional(readOnly=true) public Object configurations(Identity actor){actor=user(actor);actor.requireUser("ADMIN");Identity live=actor;return db.queryForList("select c.*,r.slug,r.zone,(select count(*) from maintenance_source_failures f where f.repository_id=c.repository_id and f.resolved=false) as source_failure_count from maintenance_intake_configs c join maintenance_repositories r on r.id=c.repository_id where c.tenant_id=?",actor.tenant()).stream().filter(r->live.zones().contains(r.get("zone"))).toList();}
    @Transactional(readOnly=true) public Object feedbackRepositories(Identity actor){actor=user(actor);Identity live=actor;return db.queryForList("select r.id,r.slug,r.zone from maintenance_repositories r join maintenance_intake_configs c on c.repository_id=r.id where r.tenant_id=? and r.enabled=true",actor.tenant()).stream().filter(r->live.zones().contains(r.get("zone"))).map(r->Map.of("id",r.get("id"),"slug",r.get("slug"))).toList();}
    public static String feedbackBody(Feedback f){return feedbackBody(f.actual(),f.expected(),f.steps());}
    static String feedbackBody(String actual,String expected,String steps){return "Actual:\n"+actual.trim()+"\n\nExpected:\n"+expected.trim()+"\n\nSteps:\n"+steps.trim();}
    @Transactional public Object feedback(Identity actor,Feedback body){locks.lock(actor.tenant());actor=user(actor);repository(actor,body.repositoryId());one("select repository_id from maintenance_intake_configs where repository_id=?",body.repositoryId());
        var fields=Map.of("actual",body.actual(),"expected",body.expected(),"steps",body.steps());
        String requestHash=AuthService.hash(json.canonical(Map.of("title",body.title(),"fields",fields,"platform",body.platform(),"clientVersion",body.clientVersion())));
        var prior=db.queryForList("select intake_id,payload_json from maintenance_intake_sources where repository_id=? and source_kind='CLIENT' and source_key=?",body.repositoryId(),actor.id()+":"+body.requestId());
        if(!prior.isEmpty()){var old=(Map<?,?>)json.read((String)prior.get(0).get("payload_json"));if(old.get("clientRequestHash")!=null){if(!requestHash.equals(old.get("clientRequestHash")))throw ApiException.conflict("FEEDBACK_REQUEST_CONFLICT");return Map.of("intakeId",prior.get(0).get("intake_id"),"reused",true);}if(old.get("clientFeedback")!=null&&!json.canonical(fields).equals(json.canonical(old.get("clientFeedback"))))throw ApiException.conflict("FEEDBACK_REQUEST_CONFLICT");}
        var result=ingest(actor.tenant(),body.repositoryId(),"CLIENT",actor.id()+":"+body.requestId(),actor.id(),null,body.title(),feedbackBody(body),body.platform(),null,fields);
        var source=one("select id,client_version,payload_json from maintenance_intake_sources where repository_id=? and source_kind='CLIENT' and source_key=?",body.repositoryId(),actor.id()+":"+body.requestId());
        if(!((String)source.get("client_version")).isEmpty()&&!body.clientVersion().equals(source.get("client_version")))throw ApiException.conflict("FEEDBACK_REQUEST_CONFLICT");
        var payload=new LinkedHashMap<String,Object>();((Map<?,?>)json.read((String)source.get("payload_json"))).forEach((key,value)->payload.put(String.valueOf(key),value));payload.put("clientFeedback",fields);payload.put("clientRequestHash",requestHash);
        db.update("update maintenance_intake_sources set client_version=?,payload_json=? where id=?",body.clientVersion(),json.canonical(payload),source.get("id"));return result;}
    private Map<String,Object> ingest(String tenant,String repo,String kind,String sourceKey,String reporter,String url,String title,String body,String platform,String sourceUpdated,Map<String,String> clientFields){
        if(title==null||title.isBlank()||title.length()>160||body==null||body.isBlank()||body.length()>20000)throw ApiException.conflict("INVALID_FEEDBACK");
        String payload=json.canonical(Map.of("title",title,"body",body,"platform",platform));String revision=AuthService.hash(payload);
        var old=db.queryForList("select * from maintenance_intake_sources where tenant_id=? and repository_id=? and source_kind=? and source_key=?",tenant,repo,kind,sourceKey);
        if(!old.isEmpty()){
            var source=old.get(0);if(sourceUpdated!=null&&source.get("source_updated")!=null&&java.time.Instant.parse(sourceUpdated).isBefore(java.time.Instant.parse((String)source.get("source_updated"))))return Map.of("intakeId",source.get("intake_id"),"reused",true,"olderSource",true);String id=(String)source.get("intake_id");
            if(source.get("source_revision").equals(revision)||json.canonical(json.read((String)source.get("payload_json"))).equals(payload))return Map.of("intakeId",id,"reused",true);
            if("CLIENT".equals(kind))throw ApiException.conflict("FEEDBACK_REQUEST_CONFLICT");
            db.update("update maintenance_intake_sources set payload_json=?,source_revision=?,source_updated=?,updated_at=? where id=?",payload,revision,sourceUpdated,clock.millis(),source.get("id"));
            // An edit never changes an active repair specification or starts a second writer.
            db.update("update maintenance_intakes set state='SOURCE_CHANGED',updated_at=? where id=?",clock.millis(),id);
            return Map.of("intakeId",id,"reused",true,"sourceChanged",true);
        }
        String fingerprint=AuthService.hash((title.trim()+"\n"+body.replace("\r\n","\n").trim()+"\n"+platform));
        var existing=db.queryForList("select i.id from maintenance_intakes i left join maintenance_problems p on p.id=i.problem_id where i.tenant_id=? and i.repository_id=? and i.platform=? and (i.fingerprint=? or (i.title=? and i.body=?)) and i.state in ('RECEIVED','ANALYZING','NEEDS_INFORMATION','READY_FOR_REVIEW','ADMITTED') and (p.state is null or p.state not in ('ACCEPTED','DELIVERED')) order by i.created_at desc,i.id limit 20",tenant,repo,platform,fingerprint,title,body);
        if(!existing.isEmpty()){
            var compatible=new ArrayList<Map<String,Object>>();
            for(var candidate:existing){boolean match=true;for(var raw:db.queryForList("select distinct payload_json from maintenance_intake_sources where intake_id=? and source_kind='CLIENT'",candidate.get("id"))){var stored=(Map<?,?>)json.read((String)raw.get("payload_json"));if(clientFields!=null&&stored.get("clientFeedback")!=null&&!json.canonical(clientFields).equals(json.canonical(stored.get("clientFeedback")))){match=false;break;}}
                if(match){compatible.add(candidate);break;}}
            existing=compatible;
        }
        // A new report after a fix needs its own decision; it must not be silently swallowed by the old fingerprint.
        if(existing.isEmpty()&&db.queryForObject("select count(*) from maintenance_intakes where tenant_id=? and repository_id=? and fingerprint=?",Integer.class,tenant,repo,fingerprint)>0)fingerprint=AuthService.hash(fingerprint+"\nSOURCE:"+kind+":"+sourceKey);
        String id=existing.isEmpty()?AuthService.id():(String)existing.get(0).get("id");
        if(existing.isEmpty())db.update("insert into maintenance_intakes(id,tenant_id,repository_id,fingerprint,title,body,platform,state,created_at,updated_at) values(?,?,?,?,?,?,?,'RECEIVED',?,?)",id,tenant,repo,fingerprint,title,body,platform,clock.millis(),clock.millis());
        db.update("insert into maintenance_intake_sources(id,tenant_id,repository_id,intake_id,source_kind,source_key,reporter_id,source_url,source_revision,source_updated,payload_json,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",AuthService.id(),tenant,repo,id,kind,sourceKey,reporter,url,revision,sourceUpdated,payload,clock.millis());
        if(reporter!=null&&db.queryForObject("select count(*) from maintenance_feedback_restrictions where repository_id=? and source_kind=? and reporter_id=? and until_at>?",Integer.class,repo,kind,reporter,clock.millis())>0)
            db.update("update maintenance_intakes set state='RESTRICTED',updated_at=? where id=? and state='RECEIVED'",clock.millis(),id);
        return Map.of("intakeId",id,"reused",!existing.isEmpty());
    }
    @Transactional public Object github(String tenant,String repo,com.fasterxml.jackson.databind.JsonNode issue){
        locks.lock(tenant);var c=one("select * from maintenance_intake_configs where repository_id=? and tenant_id=? and github_enabled=true",repo,tenant);
        Identity operator=operator(c);var r=repository(operator,repo);
        if(issue.has("pull_request"))return Map.of("ignored",true);
        if(!issue.path("number").isIntegralNumber()||!issue.path("number").canConvertToLong()||issue.path("number").asLong()<1)throw ApiException.conflict("INVALID_GITHUB_ISSUE");
        String url="https://github.com/"+r.get("slug")+"/issues/"+issue.path("number").asLong();
        if(!url.equals(issue.path("html_url").asText()))throw ApiException.conflict("INVALID_GITHUB_ISSUE");
        String updated=issue.path("updated_at").asText();
        try{java.time.Instant.parse(updated);}catch(java.time.DateTimeException invalid){throw ApiException.conflict("INVALID_GITHUB_REVISION");}
        if(!"open".equals(issue.path("state").asText())){
            var sources=db.queryForList("select * from maintenance_intake_sources where tenant_id=? and repository_id=? and source_kind='GITHUB' and source_key=?",tenant,repo,String.valueOf(issue.path("number").asLong()));
            if(!sources.isEmpty()&&(sources.get(0).get("source_updated")==null||!java.time.Instant.parse(updated).isBefore(java.time.Instant.parse((String)sources.get(0).get("source_updated"))))){
                var source=sources.get(0);String payload=json.write(Map.of("state","closed","title",issue.path("title").asText(),"body",issue.path("body").asText(""),"platform",c.get("default_platform")));
                db.update("update maintenance_intake_sources set payload_json=?,source_revision=?,source_updated=?,updated_at=? where id=?",payload,AuthService.hash(payload),updated,clock.millis(),source.get("id"));
                db.update("update maintenance_intakes set state='SOURCE_CHANGED',updated_at=? where id=? and problem_id is null",clock.millis(),source.get("intake_id"));
            }
            return Map.of("ignored",true);
        }
        String platform=(String)c.get("default_platform"); // An administrator supplies the default; native-platform labels may refine it.
        for(var label:issue.path("labels")){String name=label.path("name").asText();if(name.equals("platform:windows"))platform="windows";if(name.equals("platform:linux"))platform="linux";}
        return ingest(tenant,repo,"GITHUB",String.valueOf(issue.path("number").asLong()),issue.path("user").path("id").isIntegralNumber()?"github:"+issue.path("user").path("id").asText():null,url,issue.path("title").asText(),issue.path("body").asText(""),platform,updated,null);
    }
    @Transactional public void quarantine(String tenant,String repo,com.fasterxml.jackson.databind.JsonNode issue,String code){
        locks.lock(tenant);var config=one("select * from maintenance_intake_configs where repository_id=? and tenant_id=? and github_enabled=true",repo,tenant);repository(operator(config),repo);
        String payload=issue.toString(),revision=AuthService.hash(payload),key=issue.path("number").canConvertToLong()?issue.path("number").asText():revision;
        if(db.queryForObject("select count(*) from maintenance_source_failures where repository_id=? and source_key=? and source_revision=?",Integer.class,repo,key,revision)==0)
            db.update("insert into maintenance_source_failures values(?,?,?,?,?,?,false,?)",tenant,repo,key,revision,payload,code,clock.millis());
    }
    @Transactional public void sourceResolved(String tenant,String repo,String key){db.update("update maintenance_source_failures set resolved=true where tenant_id=? and repository_id=? and source_key=?",tenant,repo,key);}
    @Transactional(readOnly=true) public Object sourceFailures(Identity actor,String repo){actor=user(actor);actor.requireUser("ADMIN");repository(actor,repo);return db.queryForList("select source_key,source_revision,error_code,resolved,updated_at from maintenance_source_failures where repository_id=? and tenant_id=? order by updated_at desc limit 100",repo,actor.tenant());}
    private Identity operator(Map<String,Object> config){var p=one("select auth_version from principals where id=? and tenant_id=?",config.get("operator_id"),config.get("tenant_id"));Identity i=auth.current(new Identity((String)config.get("operator_id"),(String)config.get("tenant_id"),"USER",Set.of(),Set.of(),((Number)p.get("auth_version")).longValue()));i.requireUser("OPERATOR");return i;}
    @Transactional(readOnly=true) public Object list(Identity actor){return page(actor,"",100,"","",false).get("items");}
    @Transactional(readOnly=true) public Map<String,Object> page(Identity actor,String after,int limit,String query,String state,boolean mine){
        actor=user(actor);if(!mine)actor.requireUser("OPERATOR");
        if(limit<1||limit>100||after.length()>64||query.length()>160||state.length()>32)throw new ApiException(400,"INVALID_PAGE","Invalid feedback page");
        var args=new ArrayList<Object>();args.add(actor.tenant());
        String sql=" from maintenance_intakes i join maintenance_repositories r on r.id=i.repository_id where i.tenant_id=?";
        if(actor.zones().isEmpty())return Map.of("items",List.of(),"nextCursor","");
        sql+=" and r.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")";args.addAll(actor.zones());
        if(mine){sql+=" and exists(select 1 from maintenance_intake_sources s where s.intake_id=i.id and s.reporter_id=? and s.source_kind='CLIENT')";args.add(actor.id());}
        String cursorSql=sql;var cursorArgs=new ArrayList<>(args);
        if(!query.isBlank()){sql+=" and position(lower(?) in lower(i.title))>0";args.add(query);}
        if(!state.isBlank()){sql+=" and i.state=?";args.add(state);}
        if(!after.isEmpty()){var cursor=one("select i.created_at,i.id"+cursorSql+" and i.id=?",append(cursorArgs,after));sql+=" and (i.created_at<? or (i.created_at=? and i.id<?))";args.add(cursor.get("created_at"));args.add(cursor.get("created_at"));args.add(after);}
        args.add(limit+1);var rows=db.queryForList("select i.id,i.title,i.state,i.platform,i.problem_id,i.duplicate_of,i.created_at,i.updated_at,r.slug,r.zone,(select c.state from maintenance_intakes c where c.id=i.duplicate_of and c.tenant_id=i.tenant_id and c.repository_id=i.repository_id and c.platform=i.platform) as canonical_state,(select p.state from maintenance_problems p where p.id=i.problem_id) as repair_state"+sql+" order by i.created_at desc,i.id desc limit ?",args.toArray());
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        if(mine)for(var row:rows){if("DUPLICATE".equals(row.get("state"))&&Set.of("SOURCE_CHANGED","DECLINED","REJECTED","RESTRICTED","DEFERRED","NEEDS_INFORMATION").contains(String.valueOf(row.get("canonical_state"))))row.put("state",row.get("canonical_state"));row.remove("canonical_state");row.remove("zone");row.remove("problem_id");row.remove("duplicate_of");if(Set.of("REJECTED","RESTRICTED","DEFERRED").contains(row.get("state")))row.put("state","DECLINED");}
        return Map.of("items",rows,"nextCursor",more?rows.get(rows.size()-1).get("id"):"");
    }
    private Object[] append(List<Object> values,Object value){var copy=new ArrayList<>(values);copy.add(value);return copy.toArray();}
    @Transactional(readOnly=true) public Object detail(Identity actor,String id,boolean management){actor=user(actor);if(management)actor.requireUser("OPERATOR");var i=one("select * from maintenance_intakes where id=? and tenant_id=?",id,actor.tenant());repository(actor,(String)i.get("repository_id"));
        if(!management&&db.queryForObject("select count(*) from maintenance_intake_sources where intake_id=? and reporter_id=? and source_kind='CLIENT'",Integer.class,id,actor.id())==0)throw ApiException.missing();
        if(!management){
            var resolved=matches.canonical(i);
            // Public feedback DTO never includes prompts, internal reasons, paths or other reporters.
            var publicResult=new LinkedHashMap<String,Object>();publicResult.put("id",i.get("id"));publicResult.put("title",i.get("title"));publicResult.put("state",i.get("state"));publicResult.put("updatedAt",i.get("updated_at"));
            String message=switch((String)i.get("state")){case "DECLINED","REJECTED","RESTRICTED"->"该反馈暂未进入开发。";case "NEEDS_INFORMATION"->"请补充复现步骤和预期结果。";case "ADMITTED"->"已安排处理，可在这里查看进度。";default->"反馈已收到。";};
            if("DUPLICATE".equals(i.get("state"))&&Set.of("DECLINED","REJECTED","RESTRICTED","DEFERRED","NEEDS_INFORMATION").contains(String.valueOf(resolved.get("state"))))publicResult.put("state",Set.of("REJECTED","RESTRICTED","DEFERRED").contains(String.valueOf(resolved.get("state")))?"DECLINED":resolved.get("state"));
            publicResult.put("canSupplement","NEEDS_INFORMATION".equals(i.get("state"))&&i.get("problem_id")==null);
            if("SOURCE_CHANGED".equals(resolved.get("state"))){publicResult.put("state","SOURCE_CHANGED");message="关联来源已变化，需要重新确认修复范围。";}
            publicResult.put("message",message);publicResult.put("messages",db.queryForList("select content,created_at,review_state from maintenance_feedback_messages where intake_id=? and author_id=? order by created_at,id",id,actor.id()));
            // Preserve the original installed client's display shape with approved public text only.
            publicResult.put("analysis",Map.of("analysis",Map.of("reason",message)));
            if(Set.of("REJECTED","RESTRICTED","DEFERRED").contains(i.get("state")))publicResult.put("state","DECLINED");
            if(resolved.get("problem_id")!=null){var repair=one("select state from maintenance_problems where id=? and tenant_id=?",resolved.get("problem_id"),actor.tenant());
                repair.put("deliveryState","DELIVERED".equals(repair.get("state"))?"DELIVERED":latestDeliveryState((String)resolved.get("problem_id")));
                var reports=db.queryForList("select r.report_json from maintenance_reports r join work_units u on u.id=r.unit_id join maintenance_problems p on p.task_id=u.task_id where p.id=? order by r.created_at desc,r.attempt_id desc limit 1",resolved.get("problem_id"));
                if(!reports.isEmpty()){var report=(Map<?,?>)json.read((String)reports.get(0).get("report_json"));if(report.get("prUrl")!=null)repair.put("prUrl",report.get("prUrl"));}publicResult.put("repair",repair);
                if(!"SOURCE_CHANGED".equals(i.get("state"))&&!"SOURCE_CHANGED".equals(resolved.get("state"))){String resolution=switch(String.valueOf(repair.get("state"))){case "DELIVERED"->"FIX_MERGED";case "ACCEPTED"->"FIX_ACCEPTED";default->"IN_PROGRESS";};
                    publicResult.put("resolution",Map.of("code",resolution,"title",i.get("title"),"installationConfirmed",false));
                    message=switch(resolution){case "FIX_MERGED"->"已有修复已合并："+i.get("title")+"。尚未确认当前客户端版本已包含此修复。";case "FIX_ACCEPTED"->"修复已通过验收："+i.get("title")+"。尚未确认发布或安装。";default->"已关联处理中的需求，可在这里查看进度。";};publicResult.put("message",message);publicResult.put("analysis",Map.of("analysis",Map.of("reason",message)));
                }
            }else if("DUPLICATE".equals(i.get("state"))){message=switch(String.valueOf(resolved.get("state"))){case "DECLINED","REJECTED","RESTRICTED","DEFERRED"->"关联需求暂未进入开发。";case "NEEDS_INFORMATION"->"关联需求仍在等待补充资料，尚未进入开发。";case "SOURCE_CHANGED"->"关联来源已变化，需要重新确认修复范围。";default->"已关联队列中的相同需求，尚未创建执行任务。";};publicResult.put("message",message);publicResult.put("analysis",Map.of("analysis",Map.of("reason",message)));}
            return publicResult;
        }
        var result=new LinkedHashMap<String,Object>(i);
        result.put("automationStages",db.queryForList("select id,stage,state,result_json,error_code,updated_at from maintenance_stage_runs where tenant_id=? and subject_id=? order by created_at",actor.tenant(),id+":"+i.get("revision")));result.remove("fingerprint");Object analysis=i.get("analysis_json")==null?null:json.read((String)i.get("analysis_json"));if(analysis instanceof Map<?,?> value&&!value.containsKey("analysis"))analysis=Map.of("analysis",analysis);result.put("analysis",analysis);result.remove("analysis_json");result.put("messages",db.queryForList("select content,created_at,review_state from maintenance_feedback_messages where intake_id=? order by created_at,id",id));result.put("sources",db.queryForList("select source_kind,source_url,client_version,updated_at,payload_json from maintenance_intake_sources where intake_id=? order by updated_at",id));
        if(i.get("problem_id")!=null){var repair=one("select p.state,t.state as task_state from maintenance_problems p join tasks t on t.id=p.task_id where p.id=? and p.tenant_id=?",i.get("problem_id"),actor.tenant());
            var reports=db.queryForList("select r.report_json from maintenance_problems p join work_units u on u.task_id=p.task_id join maintenance_reports r on r.unit_id=u.id where p.id=? order by r.created_at desc,r.attempt_id desc limit 1",i.get("problem_id"));
            if(!reports.isEmpty()){var report=(Map<?,?>)json.read((String)reports.get(0).get("report_json"));repair.put("prUrl",report.get("prUrl"));repair.put("candidateSha",report.get("resultSha"));}repair.put("deliveryState",latestDeliveryState((String)i.get("problem_id")));result.put("repair",repair);
        }
        return result;
    }
    private String latestDeliveryState(String problem){var rows=db.queryForList("select state from maintenance_delivery_jobs where problem_id=? and mode='DELIVER' order by created_at desc,id desc limit 1",problem);return rows.isEmpty()?null:(String)rows.get(0).get("state");}
    @Transactional public Object retry(Identity actor,String id){locks.lock(actor.tenant());actor=user(actor);actor.requireUser("OPERATOR");var i=one("select * from maintenance_intakes where id=? and tenant_id=?",id,actor.tenant());repository(actor,(String)i.get("repository_id"));if(i.get("problem_id")!=null&&!"DUPLICATE".equals(i.get("state")))throw ApiException.conflict("USE_MAINTENANCE_REWORK");
        if("DUPLICATE".equals(i.get("state")))auth.audit(actor,"MAINTENANCE_DUPLICATE_REANALYSIS",Map.of("intakeId",id,"previousTarget",String.valueOf(i.get("duplicate_of")),"previousAnalysis",String.valueOf(i.get("analysis_json"))));
        if(db.queryForObject("select count(*) from maintenance_intake_attempts where intake_id=? and state='RUNNING'",Integer.class,id)>0||db.queryForObject("select count(*) from maintenance_stage_runs where repository_id=? and subject_id like ? and state='RUNNING'",Integer.class,i.get("repository_id"),id+":%")>0)throw ApiException.conflict("INTAKE_ANALYSIS_ACTIVE");
        var source=one("select payload_json,source_kind from maintenance_intake_sources where intake_id=? order by updated_at desc,id desc limit 1",id);Map<?,?> payload=(Map<?,?>)json.read((String)source.get("payload_json"));
        if("REQUIREMENT".equals(source.get("source_kind"))){var requirement=one("select title,spec_json from maintenance_requirements where intake_id=? and tenant_id=?",id,actor.tenant());var spec=(Map<?,?>)json.read((String)requirement.get("spec_json"));payload=Map.of("title",requirement.get("title"),"body",spec.get("requirement")+"\nAcceptance:\n"+json.write(spec.get("criteria")),"platform",spec.get("platform"));}
        if("closed".equals(payload.get("state")))throw ApiException.conflict("INTAKE_SOURCE_CLOSED");
        db.update("update maintenance_intakes set title=?,body=?,platform=?,state='RECEIVED',revision=revision+1,analysis_json=null,duplicate_of=null,problem_id=null where id=?",payload.get("title"),payload.get("body"),payload.get("platform"),id);return Map.of("queued",true);
    }
    public boolean occupied(String node){return db.queryForObject("select count(*) from maintenance_intake_attempts where node_id=? and state='RUNNING'",Integer.class,node)>0;}
    @Transactional public Object recover(Identity actor,String id,Recovery body){
        locks.lock(actor.tenant());actor=user(actor);actor.requireUser("OPERATOR");var i=one("select * from maintenance_intakes where id=? and tenant_id=?",id,actor.tenant());repository(actor,(String)i.get("repository_id"));
        if(!body.stopped())throw ApiException.conflict("INTAKE_STOP_NOT_CONFIRMED");
        for(var a:db.queryForList("select a.*,d.runtime_epoch as current_runtime from maintenance_intake_attempts a join sync_devices d on d.id=a.device_id where a.intake_id=? and a.state='RUNNING'",id)){
            if(clock.millis()<((Number)a.get("expires_at")).longValue()&&Objects.equals(a.get("runtime_epoch"),a.get("current_runtime")))throw ApiException.conflict("INTAKE_ANALYSIS_ACTIVE");
        }
        db.update("update maintenance_intake_attempts set state='ABANDONED' where intake_id=? and state='RUNNING'",id);
        auth.audit(actor,"MAINTENANCE_INTAKE_RECOVERED",Map.of("intakeId",id,"note",body.note()));return retry(actor,id);
    }
    @Transactional public List<Map<String,Object>> dispatch(Identity actor,Identity node,String device,String runtime,boolean capable){
        locks.lock(actor.tenant());
        if(executionResources.enforced())return List.of(); // Analysis belongs to the backend; legacy client triage cannot bypass project grants.
        var active=db.queryForList("select * from maintenance_intake_attempts where node_id=? and state='RUNNING'",node.id());
        Map<String,Object> a;
        if(!active.isEmpty()){
            a=active.get(0);if(!runtime.equals(a.get("runtime_epoch"))||clock.millis()>=((Number)a.get("expires_at")).longValue())return List.of();
        }else {
            if(!capable)return List.of();
            var pending=db.queryForList("select i.*,r.zone,c.operator_id,c.base_sha,c.allowed_paths,c.auto_dispatch from maintenance_intakes i join maintenance_repositories r on r.id=i.repository_id join maintenance_intake_configs c on c.repository_id=r.id where i.tenant_id=? and i.state='RECEIVED' and not exists(select 1 from maintenance_automation_policies ap where ap.repository_id=r.id and ap.enabled=true) and r.zone=? and r.enabled=true order by i.created_at limit 1",actor.tenant(),db.queryForObject("select zone from nodes where id=?",String.class,node.id()));
            if(pending.isEmpty())return List.of();var i=pending.get(0);
            try{operator(i);}catch(ApiException denied){db.update("update maintenance_intakes set state='RUNTIME_FAILED',analysis_json=? where id=?",json.write(Map.of("analysis",new Analysis("RUNTIME_FAILED","","","","Configured operator authorization is unavailable",List.of(),null))),i.get("id"));return List.of();}
            var context=new LinkedHashMap<String,Object>();context.put("intakeId",i.get("id"));context.put("revision",i.get("revision"));context.put("title",i.get("title"));context.put("body",i.get("body"));context.put("platform",i.get("platform"));context.put("allowedPaths",json.read((String)i.get("allowed_paths")));context.put("related",db.queryForList("select id,title,platform,state,analysis_json from maintenance_intakes where repository_id=? and tenant_id=? and id<>? and state='ADMITTED' order by updated_at desc limit 20",i.get("repository_id"),actor.tenant(),i.get("id")));
            String spec=json.write(context),id=AuthService.id();db.update("insert into maintenance_intake_attempts(id,intake_id,tenant_id,device_id,node_id,runtime_epoch,revision,state,expires_at,spec_json,spec_hash) values(?,?,?,?,?,?,?,'RUNNING',?,?,?)",id,i.get("id"),actor.tenant(),device,node.id(),runtime,i.get("revision"),clock.millis()+120000,spec,AuthService.hash(spec));
            db.update("update maintenance_intakes set state='ANALYZING',updated_at=? where id=?",clock.millis(),i.get("id"));a=one("select * from maintenance_intake_attempts where id=?",id);
        }
        return List.of(Map.of("id",a.get("id"),"kind","maintenance-triage","nativeId","","payload",Map.of("runtimeEpoch",runtime,"revision",a.get("revision"),"specHash",a.get("spec_hash"),"spec",json.read((String)a.get("spec_json")))));
    }
    private Map<String,Object> attempt(Identity actor,String device,String id,String runtime,int revision){if(executionResources.enforced())throw ApiException.conflict("SERVER_ANALYSIS_REQUIRED");actor=user(actor);policies.requireControl(actor);var a=one("select a.*,d.principal_id,d.runtime_epoch as current_runtime,d.revoked from maintenance_intake_attempts a join sync_devices d on d.id=a.device_id where a.id=? and a.tenant_id=?",id,actor.tenant());
        if(!device.equals(a.get("device_id"))||!actor.id().equals(a.get("principal_id"))||Boolean.TRUE.equals(a.get("revoked")))throw ApiException.forbidden();
        if(db.queryForObject("select count(*) from maintenance_desktop_bindings b join nodes n on n.id=b.node_id join principals p on p.id=n.id where b.device_id=? and b.node_id=? and b.tenant_id=? and b.enabled=true and p.enabled=true and n.admin_paused=false",Integer.class,device,a.get("node_id"),actor.tenant())!=1)throw ApiException.forbidden();
        var i=one("select repository_id from maintenance_intakes where id=?",a.get("intake_id"));repository(actor,(String)i.get("repository_id"));
        if(!runtime.equals(a.get("runtime_epoch"))||!runtime.equals(a.get("current_runtime"))||revision!=((Number)a.get("revision")).intValue())throw ApiException.conflict("INTAKE_RUNTIME_CHANGED");return a;
    }
    @Transactional public Object renew(Identity actor,String device,String id,Renewal body){locks.lock(actor.tenant());var a=attempt(actor,device,id,body.runtimeEpoch(),body.revision());if(!"RUNNING".equals(a.get("state"))||clock.millis()>=((Number)a.get("expires_at")).longValue())throw ApiException.conflict("INTAKE_LEASE_EXPIRED");db.update("update maintenance_intake_attempts set expires_at=? where id=?",clock.millis()+120000,id);return Map.of("renewed",true);}
    @Transactional public Object report(Identity actor,String device,String id,Receipt receipt){locks.lock(actor.tenant());var a=attempt(actor,device,id,receipt.runtimeEpoch(),receipt.revision());String digest=AuthService.hash(json.write(receipt));if(a.get("report_hash")!=null){if(!digest.equals(a.get("report_hash")))throw ApiException.conflict("INTAKE_RESULT_CONFLICT");return Map.of("accepted",true,"duplicate",true);}
        if(!"RUNNING".equals(a.get("state"))||clock.millis()>=((Number)a.get("expires_at")).longValue()||!receipt.specHash().equals(a.get("spec_hash")))throw ApiException.conflict("INTAKE_LEASE_EXPIRED");
        var i=one("select * from maintenance_intakes where id=?",a.get("intake_id"));var c=one("select * from maintenance_intake_configs where repository_id=?",i.get("repository_id"));Identity operator=operator(c);repository(operator,(String)i.get("repository_id"));Analysis out=receipt.analysis();String state=out.outcome();String problem=null,duplicate=null;
        if(!"ANALYZING".equals(i.get("state")))state="SOURCE_CHANGED";
        else if(MaintenanceIntakePolicy.protectedRequest((String)i.get("body"),Map.of(),matches.feedbacks((String)i.get("id"))).isPresent()){state="NEEDS_INFORMATION";out=new Analysis("NEEDS_INFORMATION",out.title(),"","","Protected service or usage-control changes require separate authorization",List.of(),null);}
        else if("READY".equals(state)){
            var allowed=json.strings((String)c.get("allowed_paths"));
            if(out.title().isBlank()||out.requirement().isBlank()||out.acceptance().isBlank()||out.paths().isEmpty()||out.paths().stream().anyMatch(p->!p.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||p.contains("..")||allowed.stream().noneMatch(x->p.equals(x)||p.startsWith(x+"/"))))throw ApiException.conflict("INTAKE_ANALYSIS_SCOPE_INVALID");
            if(Boolean.TRUE.equals(c.get("auto_dispatch"))){var created=maintenance.create(operator,"intake:"+i.get("id")+":"+i.get("revision"),new MaintenanceService.Problem((String)i.get("repository_id"),out.title(),out.requirement()+"\nAllowed change paths: "+String.join(", ",out.paths()),out.acceptance(),(String)i.get("platform"),(String)c.get("base_sha"),out.paths()));problem=(String)created.get("problemId");state="ADMITTED";}else state="READY_FOR_REVIEW";
        }else if("DUPLICATE".equals(state)){
            if(out.duplicateOf()==null||out.duplicateOf().equals(i.get("id"))||out.reason().isBlank())throw ApiException.conflict("INTAKE_DUPLICATE_INVALID");var target=one("select * from maintenance_intakes where id=? and tenant_id=? and repository_id=? and platform=? and state='ADMITTED'",out.duplicateOf(),actor.tenant(),i.get("repository_id"),i.get("platform"));duplicate=(String)target.get("id");problem=(String)target.get("problem_id");
        }
        db.update("update maintenance_intakes set state=?,analysis_json=?,problem_id=?,duplicate_of=?,updated_at=? where id=?",state,json.write(Map.of("analysis",out,"modelUsage",receipt.modelUsage()==null?Map.of():receipt.modelUsage())),problem,duplicate,clock.millis(),i.get("id"));db.update("update maintenance_intake_attempts set state='DONE',report_hash=? where id=?",digest,id);return Map.of("accepted",true,"duplicate",false);
    }
}
