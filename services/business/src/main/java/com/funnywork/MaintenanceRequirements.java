package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.*;

/** Versioned product requirements and evidence-based impact analysis, independent of execution tasks. */
@Service
public class MaintenanceRequirements {
    public record Criterion(@Pattern(regexp="[A-Za-z0-9-]{1,64}") String id,@NotBlank @Size(max=4000) String expected) {}
    public record Draft(@NotBlank String repositoryId,@Min(0) int expectedVersion,@NotBlank @Size(max=160) String title,
        @NotNull @Pattern(regexp="P[0-3]") String priority,@NotBlank @Size(max=10000) String requirement,
        @NotNull @Pattern(regexp="macos|windows|linux") String platform,
        @NotEmpty @Size(max=20) List<@NotBlank @Size(max=300) String> paths,
        @NotEmpty @Size(max=30) List<@NotNull @Valid Criterion> criteria,@NotBlank @Size(max=1000) String reason) {}
    public record Version(@Min(1) int expectedVersion) {}
    public record Component(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") String key,@NotBlank @Size(max=100) String name,
        @NotEmpty @Size(max=20) List<@NotBlank @Size(max=300) String> paths,@NotEmpty @Size(max=8) List<@NotNull @Pattern(regexp="macos|windows|linux|ios|android|web|server|shared") String> platforms,
        @NotNull @Size(max=80) List<@NotBlank String> dependsOn) {}
    public record DependencyMap(@Min(0) int expectedVersion,@NotNull @Size(max=80) List<@NotNull @Valid Component> components) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceMatches matches;
    private final MaintenanceRemote remote;private final TransactionTemplate tx;
    public MaintenanceRequirements(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,Clock clock,MaintenanceRemote remote,PlatformTransactionManager transactions){
        this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.clock=clock;this.remote=remote;this.tx=new TransactionTemplate(transactions);
    }
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private Identity operator(Identity actor){var live=auth.current(actor);live.requireUser("OPERATOR");return live;}
    private Map<String,Object> repository(Identity actor,String id){var repo=one("select * from maintenance_repositories where id=? and tenant_id=?",id,actor.tenant());actor.requireZone((String)repo.get("zone"));return repo;}
    private Map<String,Object> row(Identity actor,String id){var r=one("select * from maintenance_requirements where id=? and tenant_id=?",id,actor.tenant());repository(actor,(String)r.get("repository_id"));return r;}
    private void version(Map<String,Object> row,int expected){if(((Number)row.get("version")).intValue()!=expected)throw ApiException.conflict("REQUIREMENT_VERSION_CHANGED");}
    static boolean inside(String path,String root){return path.equals(root)||path.startsWith(root+"/");}
    private void paths(List<String> paths){if(paths.isEmpty()||paths.size()>20||paths.stream().anyMatch(p->!p.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||p.contains("..")||MaintenanceRemote.sensitive(p)))throw ApiException.conflict("REQUIREMENT_SCOPE_INVALID");}
    private List<String> scope(String repo){return json.strings((String)one("select allowed_paths from maintenance_intake_configs where repository_id=?",repo).get("allowed_paths")).stream().toList();}
    private void authorizedPaths(String repo,List<String> paths){paths(paths);var scope=scope(repo);if(paths.stream().anyMatch(p->scope.stream().noneMatch(s->inside(p,s))))throw ApiException.conflict("REQUIREMENT_SCOPE_INVALID");}
    private String effectiveState(Map<String,Object> r){
        if(r.get("intake_id")==null)return (String)r.get("state");
        var i=one("select i.state,p.state as repair_state from maintenance_intakes i left join maintenance_problems p on p.id=i.problem_id where i.id=?",r.get("intake_id"));
        if("ADMITTED".equals(i.get("state")))return String.valueOf(i.get("repair_state"));
        return switch(String.valueOf(i.get("state"))){case "RECEIVED","ANALYZING"->"QUEUED";case "READY_FOR_REVIEW"->"READY";default->String.valueOf(i.get("state"));};
    }
    private void snapshot(Identity actor,String id,int version,String title,String priority,String spec,String reason){
        db.update("insert into maintenance_requirement_revisions values(?,?,?,?,?,?,?,?)",id,version,spec,title,priority,actor.id(),reason,clock.millis());
    }
    @Transactional public Object create(Identity actor,String requestKey,Draft draft){
        locks.lock(actor.tenant());actor=operator(actor);if(requestKey==null||!requestKey.matches("[A-Za-z0-9_-]{1,64}"))throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required");
        String hash=AuthService.hash(json.canonical(draft));var old=db.queryForList("select request_hash,requirement_id from maintenance_requirement_requests where tenant_id=? and principal_id=? and request_key=?",actor.tenant(),actor.id(),requestKey);
        if(!old.isEmpty()){row(actor,(String)old.get(0).get("requirement_id"));if(!hash.equals(old.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return Map.of("id",old.get(0).get("requirement_id"),"version",1,"reused",true);}
        var result=save(actor,null,draft);db.update("insert into maintenance_requirement_requests values(?,?,?,?,?)",actor.tenant(),actor.id(),requestKey,hash,result.get("id"));return result;
    }
    @Transactional public Map<String,Object> save(Identity actor,String id,Draft draft){
        locks.lock(actor.tenant());actor=operator(actor);repository(actor,draft.repositoryId());authorizedPaths(draft.repositoryId(),draft.paths());
        Map<String,Object> prior=null;String requirement=id;
        if(id!=null){prior=row(actor,id);version(prior,draft.expectedVersion());if(!draft.repositoryId().equals(prior.get("repository_id")))throw ApiException.conflict("REQUIREMENT_FROZEN");
            if(prior.get("intake_id")!=null){
                if(!"MANUAL".equals(prior.get("origin"))||!Set.of("NEEDS_INFORMATION","DECLINED","DEFERRED").contains(effectiveState(prior))||db.queryForObject("select count(*) from maintenance_requirement_links where requirement_id=?",Integer.class,id)>0)throw ApiException.conflict("REQUIREMENT_FROZEN");
                if(db.queryForObject("select count(*) from maintenance_stage_runs where repository_id=? and subject_id like ? and state='RUNNING'",Integer.class,draft.repositoryId(),prior.get("intake_id")+":%")>0)throw ApiException.conflict("REQUIREMENT_REVIEW_ACTIVE");
            }}
        else {if(draft.expectedVersion()!=0)throw ApiException.conflict("REQUIREMENT_VERSION_CHANGED");requirement=AuthService.id();}
        var known=new HashSet<String>();if(prior!=null){var old=(Map<?,?>)json.read((String)prior.get("spec_json"));for(Object c:(List<?>)old.get("criteria"))known.add((String)((Map<?,?>)c).get("id"));}
        var criteria=new ArrayList<Map<String,String>>();var seen=new HashSet<String>();
        for(var c:draft.criteria()){String key=c.id()==null?AuthService.id():c.id();if((c.id()!=null&&!known.contains(key))||!seen.add(key))throw ApiException.conflict("REQUIREMENT_CRITERION_INVALID");criteria.add(Map.of("id",key,"expected",c.expected()));}
        String spec=json.write(Map.of("requirement",draft.requirement(),"platform",draft.platform(),"paths",draft.paths(),"criteria",criteria));
        int revision=draft.expectedVersion()+1;if(revision<1)throw ApiException.conflict("REQUIREMENT_VERSION_EXHAUSTED");
        if(prior==null)db.update("insert into maintenance_requirements values(?,?,?,null,'MANUAL','BACKLOG',?,?,?,?,?,?,?)",requirement,actor.tenant(),draft.repositoryId(),revision,draft.title(),draft.priority(),spec,actor.id(),clock.millis(),clock.millis());
        else db.update("update maintenance_requirements set version=?,title=?,priority=?,spec_json=?,updated_at=? where id=?",revision,draft.title(),draft.priority(),spec,clock.millis(),id);
        if(prior!=null&&prior.get("intake_id")!=null){
            String content=draft.requirement()+"\nAcceptance:\n"+json.write(criteria);if(content.length()>20000)throw ApiException.conflict("REQUIREMENT_TOO_LARGE");
            db.update("update maintenance_intakes set title=?,body=?,platform=?,revision=revision+1,state='RECEIVED',analysis_json=null,last_checked_at=0,updated_at=? where id=?",draft.title(),content,draft.platform(),clock.millis(),prior.get("intake_id"));
            db.update("update maintenance_intake_sources set payload_json=?,source_revision=?,updated_at=? where intake_id=? and source_kind='REQUIREMENT'",spec,String.valueOf(revision),clock.millis(),prior.get("intake_id"));
        }
        snapshot(actor,requirement,revision,draft.title(),draft.priority(),spec,draft.reason());auth.audit(actor,"MAINTENANCE_REQUIREMENT_SAVED",Map.of("requirementId",requirement,"version",revision));
        return Map.of("id",requirement,"version",revision);
    }
    @Transactional public Object queue(Identity actor,String id,Version body){
        locks.lock(actor.tenant());actor=operator(actor);var r=row(actor,id);version(r,body.expectedVersion());
        if(r.get("intake_id")!=null)return Map.of("intakeId",r.get("intake_id"),"reused",true);
        var spec=(Map<?,?>)json.read((String)r.get("spec_json"));var paths=((List<?>)spec.get("paths")).stream().map(String::valueOf).toList();authorizedPaths((String)r.get("repository_id"),paths);
        one("select a.repository_id from maintenance_automation_policies a join maintenance_intake_configs c on c.repository_id=a.repository_id join maintenance_repositories r on r.id=a.repository_id where a.repository_id=? and a.enabled=true and c.auto_dispatch=true and r.enabled=true",r.get("repository_id"));
        String intake=AuthService.id(),content=spec.get("requirement")+"\nAcceptance:\n"+json.write(spec.get("criteria"));
        if(content.length()>20000)throw ApiException.conflict("REQUIREMENT_TOO_LARGE");
        db.update("insert into maintenance_intakes(id,tenant_id,repository_id,fingerprint,title,body,platform,state,created_at,updated_at) values(?,?,?,?,?,?,?,'RECEIVED',?,?)",intake,actor.tenant(),r.get("repository_id"),AuthService.hash(id+":"+r.get("version")),r.get("title"),content,spec.get("platform"),clock.millis(),clock.millis());
        db.update("insert into maintenance_intake_sources(id,tenant_id,intake_id,repository_id,source_kind,source_key,reporter_id,source_revision,payload_json,updated_at) values(?,?,?,?,'REQUIREMENT',?,?,?,?,?)",AuthService.id(),actor.tenant(),intake,r.get("repository_id"),id,actor.id(),String.valueOf(r.get("version")),json.write(spec),clock.millis());
        db.update("update maintenance_requirements set intake_id=?,state='QUEUED',updated_at=? where id=?",intake,clock.millis(),id);auth.audit(actor,"MAINTENANCE_REQUIREMENT_QUEUED",Map.of("requirementId",id,"intakeId",intake));return Map.of("intakeId",intake,"reused",false);
    }
    @Transactional(readOnly=true) public Object context(Identity actor,String intake){actor=operator(actor);var rows=db.queryForList("select * from maintenance_requirements where intake_id=? and tenant_id=?",intake,actor.tenant());if(rows.isEmpty())return Map.of();var r=rows.get(0);repository(actor,(String)r.get("repository_id"));return Map.of("id",r.get("id"),"version",r.get("version"),"spec",json.read((String)r.get("spec_json")));}
    /** Called after a successful product decision, before technical feasibility or task creation. */
    @Transactional public Map<String,Object> capture(Identity actor,Map<String,Object> intake,JsonNode product){
        locks.lock(actor.tenant());actor=operator(actor);repository(actor,(String)intake.get("repository_id"));
        one("select id from maintenance_intakes where id=? and revision=? and state='RECEIVED'",intake.get("id"),intake.get("revision"));
        return materialize(actor,intake,product);
    }
    private Map<String,Object> materialize(Identity actor,Map<String,Object> intake,JsonNode product){
        var existing=db.queryForList("select * from maintenance_requirements where intake_id=?",intake.get("id"));
        Map<String,Object> prior=existing.isEmpty()?null:existing.get(0);
        if(prior!=null){var old=(Map<?,?>)json.read((String)prior.get("spec_json"));if("MANUAL".equals(prior.get("origin"))){authorizedPaths((String)prior.get("repository_id"),((List<?>)old.get("paths")).stream().map(String::valueOf).toList());return prior;}if(Objects.equals(old.get("sourceRevision"),intake.get("revision"))||db.queryForObject("select count(*) from maintenance_requirement_links where requirement_id=?",Integer.class,prior.get("id"))>0)return prior;}
        String title=product.path("title").asText(),requirement=product.path("requirement").asText(),acceptance=product.path("acceptance").asText();
        if(title.isBlank()||title.length()>160||requirement.isBlank()||requirement.length()>10000||acceptance.isBlank()||acceptance.length()>10000)throw ApiException.conflict("MAINTENANCE_ROLE_RESULT_INCOMPLETE");
        String id=prior==null?AuthService.id():(String)prior.get("id");
        var oldCriteria=prior==null?List.of():(List<?>)((Map<?,?>)json.read((String)prior.get("spec_json"))).get("criteria");
        var known=new HashMap<String,String>();for(Object c:oldCriteria)known.put(String.valueOf(((Map<?,?>)c).get("id")),String.valueOf(((Map<?,?>)c).get("expected")));
        var criteria=new ArrayList<Map<String,String>>();var seen=new HashSet<String>();var structured=product.path("acceptanceCriteria");
        if(structured.isArray()&&!structured.isEmpty()){
            if(structured.size()>30)throw ApiException.conflict("REQUIREMENT_CRITERION_INVALID");for(var c:structured){String expected=c.path("expected").asText(),key=c.path("id").asText("");if(expected.isBlank()||expected.length()>4000||(!key.isEmpty()&&!known.containsKey(key)))throw ApiException.conflict("REQUIREMENT_CRITERION_INVALID");if(key.isEmpty())key=known.entrySet().stream().filter(e->e.getValue().equals(expected)&&!seen.contains(e.getKey())).map(Map.Entry::getKey).findFirst().orElseGet(AuthService::id);if(!seen.add(key))throw ApiException.conflict("REQUIREMENT_CRITERION_INVALID");criteria.add(Map.of("id",key,"expected",expected));}
        }else criteria.add(Map.of("id",known.size()==1?known.keySet().iterator().next():AuthService.id(),"expected",acceptance));
        if(criteria.stream().mapToInt(c->c.get("expected").length()+1).sum()>10000)throw ApiException.conflict("REQUIREMENT_TOO_LARGE");
        String spec=json.write(Map.of("sourceRevision",intake.get("revision"),"requirement",requirement,"platform",intake.get("platform"),"paths",scope((String)intake.get("repository_id")),"criteria",criteria));
        if(prior!=null){int revision=((Number)prior.get("version")).intValue()+1;if(revision<1)throw ApiException.conflict("REQUIREMENT_VERSION_EXHAUSTED");db.update("update maintenance_requirements set version=?,title=?,spec_json=?,updated_at=? where id=?",revision,title,spec,clock.millis(),id);snapshot(actor,id,revision,title,(String)prior.get("priority"),spec,"PRODUCT reanalysis");return row(actor,id);}
        db.update("insert into maintenance_requirements values(?,?,?,?,'PRODUCT','READY',1,?,'P2',?,?,?,?)",id,actor.tenant(),intake.get("repository_id"),intake.get("id"),title,spec,actor.id(),clock.millis(),clock.millis());
        snapshot(actor,id,1,title,"P2",spec,"PRODUCT decision");return row(actor,id);
    }
    @Transactional public Object importExisting(Identity actor,String after){
        locks.lock(actor.tenant());actor=operator(actor);if(after.length()>64)throw new ApiException(400,"INVALID_PAGE","Invalid import cursor");if(actor.zones().isEmpty())return Map.of("imported",0,"skipped",0,"nextCursor","");
        var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(actor.zones());String sql="select i.* from maintenance_intakes i join maintenance_repositories r on r.id=i.repository_id where i.tenant_id=? and r.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") and i.analysis_json is not null and i.state in ('ADMITTED','READY_FOR_REVIEW','NEEDS_INFORMATION','DECLINED','DEFERRED','SOURCE_CHANGED') and not exists(select 1 from maintenance_requirements q where q.intake_id=i.id)";
        if(!after.isEmpty()){var cursor=one("select i.created_at,i.id,r.zone from maintenance_intakes i join maintenance_repositories r on r.id=i.repository_id where i.id=? and i.tenant_id=?",after,actor.tenant());actor.requireZone((String)cursor.get("zone"));sql+=" and (i.created_at>? or (i.created_at=? and i.id>?))";args.add(cursor.get("created_at"));args.add(cursor.get("created_at"));args.add(after);}
        var rows=db.queryForList(sql+" order by i.created_at,i.id limit 101",args.toArray());boolean more=rows.size()>100;if(more)rows=rows.subList(0,100);int imported=0,skipped=0;
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();for(var i:rows){JsonNode product=mapper.valueToTree(json.read((String)i.get("analysis_json")));if(product.has("analysis"))product=product.path("analysis");
            if(product.path("requirement").asText().isBlank()||product.path("acceptance").asText().isBlank()||product.path("title").asText().isBlank()){skipped++;continue;}
            var requirement=materialize(actor,i,product);if(i.get("problem_id")!=null)link(actor,(String)requirement.get("id"),((Number)requirement.get("version")).intValue(),(String)i.get("problem_id"));imported++;
        }
        auth.audit(actor,"MAINTENANCE_REQUIREMENTS_IMPORTED",Map.of("imported",imported,"skipped",skipped));return Map.of("imported",imported,"skipped",skipped,"nextCursor",more?rows.get(rows.size()-1).get("id"):"");
    }
    @Transactional(readOnly=true) public Object catalog(Identity actor){
        actor=operator(actor);if(actor.zones().isEmpty())return List.of();var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(actor.zones());
        return db.queryForList("select r.id,r.slug,c.allowed_paths,c.default_platform from maintenance_repositories r join maintenance_intake_configs c on c.repository_id=r.id where r.tenant_id=? and r.enabled=true and r.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") order by r.slug,r.id",args.toArray());
    }
    @Transactional(readOnly=true) public Object page(Identity actor,String after,int limit,String query,String repositoryId,String state){
        actor=operator(actor);if(limit<1||limit>100||after.length()>64||query.length()>160||state.length()>32)throw new ApiException(400,"INVALID_PAGE","Invalid requirement page");
        if(actor.zones().isEmpty())return Map.of("items",List.of(),"nextCursor","");
        var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(actor.zones());
        String sql=" from maintenance_requirements q join maintenance_repositories r on r.id=q.repository_id where q.tenant_id=? and r.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")";
        if(!repositoryId.isEmpty()){repository(actor,repositoryId);sql+=" and q.repository_id=?";args.add(repositoryId);}
        if(!after.isEmpty()){var cursor=row(actor,after);sql+=" and (q.created_at<? or (q.created_at=? and q.id<?))";args.add(cursor.get("created_at"));args.add(cursor.get("created_at"));args.add(after);}
        if(!query.isBlank()){sql+=" and position(lower(?) in lower(q.title))>0";args.add(query);}
        // State is derived in SQL before LIMIT so authorized matching rows cannot be hidden by other states.
        String effective="case when i.state='ADMITTED' then p.state when i.state in ('RECEIVED','ANALYZING') then 'QUEUED' when i.state='READY_FOR_REVIEW' then 'READY' else coalesce(i.state,q.state) end";
        sql=sql.replace(" where q.tenant_id", " left join maintenance_intakes i on i.id=q.intake_id left join maintenance_problems p on p.id=i.problem_id where q.tenant_id");
        if(!state.isEmpty()){sql+=" and ("+effective+")=?";args.add(state);}
        args.add(limit+1);var items=db.queryForList("select q.id,q.title,q.priority,q.version,q.repository_id,q.intake_id,q.created_at,q.updated_at,r.slug,"+effective+" as state"+sql+" order by q.created_at desc,q.id desc limit ?",args.toArray());
        boolean more=items.size()>limit;if(more)items=new ArrayList<>(items.subList(0,limit));return Map.of("items",items,"nextCursor",more?items.get(items.size()-1).get("id"):"");
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(Identity actor,String id){
        actor=operator(actor);var r=row(actor,id);r.put("state",effectiveState(r));r.put("spec",json.read((String)r.remove("spec_json")));
        if(r.get("intake_id")!=null){var intake=one("select * from maintenance_intakes where id=?",r.get("intake_id"));var canonical=matches.canonical(intake);if(!canonical.get("id").equals(intake.get("id"))){var canonicalRows=db.queryForList("select id,title,version from maintenance_requirements where intake_id=? and tenant_id=?",canonical.get("id"),actor.tenant());if(!canonicalRows.isEmpty()){var value=canonicalRows.get(0);value.put("state",canonical.get("state"));r.put("canonicalRequirement",value);}}}
        r.put("revisions",db.queryForList("select version,title,priority,reason,created_at,spec_json from maintenance_requirement_revisions where requirement_id=? order by version desc",id));
        r.put("links",db.queryForList("select l.requirement_version,l.problem_id,p.task_id,p.state from maintenance_requirement_links l join maintenance_problems p on p.id=l.problem_id where l.requirement_id=?",id));
        r.put("stages",r.get("intake_id")==null?List.of():db.queryForList("select s.id,s.stage,s.state,s.result_json,s.error_code from maintenance_stage_runs s join maintenance_intakes i on s.repository_id=i.repository_id and s.subject_id=concat(i.id,':',i.revision) where i.id=? and s.tenant_id=? order by s.created_at,s.id",r.get("intake_id"),actor.tenant()));
        r.put("sources",r.get("intake_id")==null?List.of():db.queryForList("select source_kind,source_url,client_version,updated_at from maintenance_intake_sources where intake_id=? or intake_id in(select id from maintenance_intakes where duplicate_of=?)",r.get("intake_id"),r.get("intake_id")));
        var impacts=db.queryForList("select id,requirement_version,result_json,created_at from maintenance_requirement_impacts where requirement_id=? order by created_at desc,id desc limit 10",id);
        String stamp=configStamp((String)r.get("repository_id"));for(var impact:impacts){var result=(Map<?,?>)json.read((String)impact.remove("result_json"));impact.put("result",result);impact.put("stale",!r.get("version").equals(impact.get("requirement_version"))||!stamp.equals(result.get("configStamp")));}r.put("impacts",impacts);return r;
    }
    @Transactional(readOnly=true) public Map<String,Object> dependencies(Identity actor,String repo){actor=operator(actor);repository(actor,repo);var rows=db.queryForList("select * from maintenance_dependency_maps where repository_id=?",repo);return rows.isEmpty()?Map.of("version",0,"components",List.of()):Map.of("version",rows.get(0).get("version"),"components",json.read((String)rows.get(0).get("components_json")));}
    @Transactional public Object dependencies(Identity actor,String repo,DependencyMap body){
        locks.lock(actor.tenant());actor=operator(actor);actor.requireUser("ADMIN");repository(actor,repo);var old=dependencies(actor,repo);if(((Number)old.get("version")).intValue()!=body.expectedVersion())throw ApiException.conflict("DEPENDENCY_MAP_CHANGED");
        Set<String> keys=new HashSet<>();for(var c:body.components()){paths(c.paths());if(!keys.add(c.key()))throw ApiException.conflict("DEPENDENCY_MAP_INVALID");}
        for(var c:body.components())if(c.dependsOn().stream().anyMatch(d->!keys.contains(d)||d.equals(c.key())))throw ApiException.conflict("DEPENDENCY_MAP_INVALID");
        int version=body.expectedVersion()+1;if(version<1)throw ApiException.conflict("DEPENDENCY_MAP_CHANGED");
        db.update("delete from maintenance_dependency_maps where repository_id=?",repo);db.update("insert into maintenance_dependency_maps values(?,?,?,?)",repo,version,json.write(body.components()),clock.millis());auth.audit(actor,"MAINTENANCE_DEPENDENCIES_SAVED",Map.of("repositoryId",repo,"version",version));return Map.of("version",version);
    }
    private String configStamp(String repo){var config=one("select allowed_paths,base_sha from maintenance_intake_configs where repository_id=?",repo);var maps=db.queryForList("select version,components_json from maintenance_dependency_maps where repository_id=?",repo);return AuthService.hash(json.canonical(Map.of("config",config,"dependencies",maps)));}
    @Transactional(readOnly=true) public String stamp(Identity actor,String repo){actor=operator(actor);repository(actor,repo);return configStamp(repo);}
    @Transactional public void assertImpact(Identity actor,String id,int version,String impact){locks.lock(actor.tenant());actor=operator(actor);var r=row(actor,id);version(r,version);var saved=one("select result_json from maintenance_requirement_impacts where id=? and requirement_id=? and requirement_version=?",impact,id,version);if(!configStamp((String)r.get("repository_id")).equals(((Map<?,?>)json.read((String)saved.get("result_json"))).get("configStamp")))throw ApiException.conflict("REQUIREMENT_IMPACT_STALE");}
    public Object analyze(Identity actor,String id,Version body)throws Exception {
        Identity live=operator(actor);Map<String,Object> r=tx.execute(s->{locks.lock(live.tenant());var value=row(operator(live),id);version(value,body.expectedVersion());value.put("configStamp",configStamp((String)value.get("repository_id")));return value;});
        var policy=one("select a.*,r.slug,r.base_branch from maintenance_automation_policies a join maintenance_repositories r on r.id=a.repository_id where a.repository_id=?",r.get("repository_id"));
        var baseline=remote.baseline(policy,scope((String)r.get("repository_id")));
        return tx.execute(s->{locks.lock(live.tenant());var current=row(operator(live),id);version(current,body.expectedVersion());if(!r.get("configStamp").equals(configStamp((String)r.get("repository_id"))))throw ApiException.conflict("REQUIREMENT_IMPACT_STALE");return recordImpact(current,baseline,null);});
    }
    /** Reuse the fixed baseline already obtained by automation; no second network or model call. */
    @Transactional public Map<String,Object> impact(Identity actor,String id,int version,Map<String,Object> baseline,String expectedStamp){locks.lock(actor.tenant());actor=operator(actor);var r=row(actor,id);version(r,version);if(!expectedStamp.equals(configStamp((String)r.get("repository_id"))))throw ApiException.conflict("REQUIREMENT_IMPACT_STALE");return recordImpact(r,baseline,null);}
    @Transactional public Map<String,Object> plannedImpact(Identity actor,String id,int version,Map<String,Object> baseline,List<String> paths,String expectedStamp){locks.lock(actor.tenant());actor=operator(actor);var r=row(actor,id);version(r,version);if(!expectedStamp.equals(configStamp((String)r.get("repository_id"))))throw ApiException.conflict("REQUIREMENT_IMPACT_STALE");authorizedPaths((String)r.get("repository_id"),paths);return recordImpact(r,baseline,paths);}
    private Map<String,Object> recordImpact(Map<String,Object> r,Map<String,Object> baseline,List<String> plan){
        var spec=(Map<?,?>)json.read((String)r.get("spec_json"));var requested=plan==null?((List<?>)spec.get("paths")).stream().map(String::valueOf).toList():plan;
        var components=(List<?>)dependenciesRaw((String)r.get("repository_id"));var result=MaintenanceImpact.analyze(baseline,requested,components);
        result.put("basis",plan==null?"PRODUCT_SCOPE":"TECHNICAL_PLAN");result.put("configStamp",configStamp((String)r.get("repository_id")));String encoded=json.canonical(result),hash=AuthService.hash(encoded);
        var prior=db.queryForList("select id from maintenance_requirement_impacts where requirement_id=? and requirement_version=? and input_hash=?",r.get("id"),r.get("version"),hash);
        String id=prior.isEmpty()?AuthService.id():(String)prior.get(0).get("id");if(prior.isEmpty())db.update("insert into maintenance_requirement_impacts values(?,?,?,?,?,?)",id,r.get("id"),r.get("version"),hash,encoded,clock.millis());
        return Map.of("id",id,"requirementId",r.get("id"),"version",r.get("version"),"result",result);
    }
    private Object dependenciesRaw(String repo){var rows=db.queryForList("select components_json from maintenance_dependency_maps where repository_id=?",repo);return rows.isEmpty()?List.of():json.read((String)rows.get(0).get("components_json"));}
    @Transactional public void link(Identity actor,String id,int version,String problem){locks.lock(actor.tenant());actor=operator(actor);var r=row(actor,id);version(r,version);one("select id from maintenance_problems where id=? and tenant_id=? and repository_id=?",problem,actor.tenant(),r.get("repository_id"));if(db.queryForObject("select count(*) from maintenance_requirement_links where requirement_id=? and requirement_version=? and problem_id=?",Integer.class,id,version,problem)==0)db.update("insert into maintenance_requirement_links values(?,?,?)",id,version,problem);}
}
