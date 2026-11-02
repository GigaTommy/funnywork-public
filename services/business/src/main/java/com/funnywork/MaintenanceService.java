package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Frozen repository work reuses task leases; automated repositories require backend role decisions. */
@Service
public class MaintenanceService {
    @org.springframework.beans.factory.annotation.Autowired private TaskAttribution attribution;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceDelivery delivery;
    public record Check(@NotBlank @Pattern(regexp="[a-z0-9-]{1,64}") String id,
                        @NotEmpty @Size(max=32) List<@NotBlank @Size(max=1000) String> argv,
                        @Min(1) @Max(1800) int timeoutSeconds) {}
    public record Repository(@Pattern(regexp="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") @NotNull String slug,
                             @Pattern(regexp="[A-Za-z0-9_/-]{1,120}") @NotNull String baseBranch,
                             @Pattern(regexp="[a-z0-9-]{1,64}") @NotNull String zone,
                             @NotEmpty @Size(max=20) List<@Valid Check> checks) {}
    public record RepositoryChecks(@NotEmpty @Size(max=20) List<@Valid Check> checks) {}
    public record Problem(@NotBlank String repositoryId,@NotBlank @Size(max=160) String title,
                          @NotBlank @Size(max=10000) String requirement,@NotBlank @Size(max=10000) String acceptance,
                          @Pattern(regexp="macos|windows|linux") @NotNull String platform,
                          @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") @NotNull String baseSha,
                          @Size(max=20) List<@NotBlank @Size(max=200) String> allowedPaths) {
        public Problem(String repositoryId,String title,String requirement,String acceptance,String platform,String baseSha){this(repositoryId,title,requirement,acceptance,platform,baseSha,List.of());}
    }
    public record Profile(@Pattern(regexp="macos|windows|linux") @NotNull String platform,
                          @NotBlank @Size(max=120) String executorVersion,@NotBlank @Size(max=120) String gitVersion) {}
    public record CheckResult(@NotBlank String id,int exitCode,@NotNull @Size(max=20000) String output) {}
    public record ModelUsage(@Min(0) @Max(100000) int calls,@PositiveOrZero Long inputTokens,@PositiveOrZero Long outputTokens,@PositiveOrZero Long cachedInputTokens) {}
    public record Report(@Min(1) long epoch,@Pattern(regexp="[a-f0-9]{64}") @NotNull String specHash,
                         @Pattern(regexp="CANDIDATE_READY|VERIFIED|DELIVERED|CHECKS_FAILED|NEEDS_ENVIRONMENT|RUNTIME_FAILED") @NotNull String outcome,
                         @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String resultSha,
                         @Size(max=300) String prUrl,@NotNull @Size(max=20000) String summary,
                         @NotNull @Size(max=20) List<@Valid CheckResult> checks,@Valid ModelUsage modelUsage) {
        public Report(long epoch,String specHash,String outcome,String resultSha,String prUrl,String summary,List<CheckResult> checks){this(epoch,specHash,outcome,resultSha,prUrl,summary,checks,null);}
    }
    public record Decision(@Min(1) int expectedVersion,@Pattern(regexp="accept|rework") @NotNull String action,
                           @NotBlank @Size(max=10000) String note,@Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String candidateSha) {
        public Decision(int expectedVersion,String action,String note){this(expectedVersion,action,note,null);}
    }
    private final JdbcTemplate db;
    private final Json json;
    private final AuthService auth;
    private final TenantLocks locks;
    private final ControlService control;
    private final ArtifactService artifacts;
    private final Clock clock;
    public MaintenanceService(JdbcTemplate db,Json json,AuthService auth,TenantLocks locks,ControlService control,ArtifactService artifacts,Clock clock) {
        this.db=db;this.json=json;this.auth=auth;this.locks=locks;this.control=control;this.artifacts=artifacts;this.clock=clock;
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    private Identity operator(Identity actor) {actor=auth.current(actor);actor.requireUser("OPERATOR");return actor;}
    @Transactional
    public Map<String,Object> repository(Identity actor,Repository request) {
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(request.zone());
        if(request.slug().length()>200 || request.baseBranch().contains("..") || request.baseBranch().startsWith("/"))throw ApiException.conflict("INVALID_REPOSITORY");
        if(request.checks().stream().map(Check::id).distinct().count()!=request.checks().size())throw ApiException.conflict("DUPLICATE_CHECK");
        var previous=db.queryForList("select * from maintenance_repositories where tenant_id=? and zone=? and slug=?",actor.tenant(),request.zone(),request.slug());
        if(!previous.isEmpty())throw ApiException.conflict("REPOSITORY_ALREADY_CONFIGURED");
        String id=AuthService.id();
        String agent=(String)control.publishAgent(actor,new Requests.Agent("maintenance-"+id,"1.0.0",Capabilities.REPOSITORY)).get("agentId");
        db.update("insert into maintenance_repositories(id,tenant_id,zone,agent_id,slug,base_branch,checks_json) values(?,?,?,?,?,?,?)",id,actor.tenant(),request.zone(),agent,request.slug(),request.baseBranch(),json.write(request.checks()));
        auth.audit(actor,"MAINTENANCE_REPOSITORY_CREATED",Map.of("repositoryId",id,"slug",request.slug()));
        return Map.of("repositoryId",id,"agentId",agent);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> repositories(Identity actor) {
        actor=operator(actor);Set<String> zones=actor.zones();
        return db.queryForList("select id,slug,zone,base_branch,enabled from maintenance_repositories where tenant_id=? order by slug",actor.tenant()).stream().filter(row->actorZones(row,zones)).toList();
    }
    private boolean actorZones(Map<String,Object> row,Set<String> zones) {return zones.contains((String)row.get("zone"));}
    @Transactional
    public Object checks(Identity actor,String id,RepositoryChecks request){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        var repo=one("select * from maintenance_repositories where id=? and tenant_id=?",id,actor.tenant());actor.requireZone((String)repo.get("zone"));
        if(request.checks().stream().map(Check::id).distinct().count()!=request.checks().size())throw ApiException.conflict("DUPLICATE_CHECK");
        if(db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id join maintenance_specs s on s.unit_id=u.id join maintenance_problems p on p.id=s.problem_id where p.repository_id=? and a.state in ('RUNNING','RESERVED')",Integer.class,id)>0)throw ApiException.conflict("MAINTENANCE_CHECKS_IN_USE");
        db.update("update maintenance_repositories set checks_json=? where id=?",json.write(request.checks()),id);
        auth.audit(actor,"MAINTENANCE_CHECKS_CONFIGURED",Map.of("repositoryId",id));return Map.of("saved",true);
    }
    @Transactional
    public Map<String,Object> create(Identity actor,String requestKey,Problem request) {
        if(db.queryForObject("select count(*) from maintenance_automation_policies where repository_id=? and tenant_id=? and enabled=true",Integer.class,request.repositoryId(),actor.tenant())>0)throw ApiException.conflict("AUTOMATED_INTAKE_REQUIRED");
        return createInternal(actor,requestKey,request);
    }
    @Transactional
    Map<String,Object> createReviewed(Identity actor,String requestKey,Problem request,String approval) {
        var rows=db.queryForList("select * from maintenance_stage_runs where id=? and tenant_id=? and repository_id=? and stage='DISPATCH' and state='PASSED' and input_hash=?",approval,actor.tenant(),request.repositoryId(),AuthService.hash(json.canonical(request)));
        if(rows.isEmpty())throw ApiException.conflict("TECHNICAL_APPROVAL_REQUIRED");
        return createInternal(actor,requestKey,request);
    }
    @Transactional
    Map<String,Object> createReviewed(Identity actor,String requestKey,Problem request,String approval,Map<String,Object> product) {
        var rows=db.queryForList("select id from maintenance_stage_runs where id=? and tenant_id=? and repository_id=? and stage='DISPATCH' and state='PASSED' and input_hash=?",approval,actor.tenant(),request.repositoryId(),AuthService.hash(json.canonical(Map.of("request",request,"product",product))));
        if(rows.isEmpty())throw ApiException.conflict("TECHNICAL_APPROVAL_REQUIRED");
        return createInternal(actor,requestKey,request,product);
    }
    private Map<String,Object> createInternal(Identity actor,String requestKey,Problem request) {return createInternal(actor,requestKey,request,null);}
    private Map<String,Object> createInternal(Identity actor,String requestKey,Problem request,Map<String,Object> product) {
        locks.lock(actor.tenant());actor=operator(actor);
        if(MaintenanceIntakePolicy.protectedRequest("",Map.of(),List.of(Map.of("expected",request.requirement()),Map.of("expected",request.acceptance()))).isPresent())throw ApiException.conflict("MAINTENANCE_PROTECTED_PLAN");
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9._:-]{1,120}"))throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required");
        var canonical=new LinkedHashMap<String,Object>();canonical.put("repositoryId",request.repositoryId());canonical.put("title",request.title());canonical.put("requirement",request.requirement());canonical.put("acceptance",request.acceptance());canonical.put("platform",request.platform());canonical.put("baseSha",request.baseSha());
        if(request.allowedPaths()!=null&&!request.allowedPaths().isEmpty())canonical.put("allowedPaths",request.allowedPaths());
        if(product!=null)canonical.put("product",product);
        String hash=AuthService.hash(product==null?json.write(canonical):json.canonical(canonical));
        var old=db.queryForList("select id,request_hash,task_id from maintenance_problems where tenant_id=? and owner_id=? and request_key=?",actor.tenant(),actor.id(),requestKey);
        if(!old.isEmpty()) {
            if(!hash.equals(old.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            return Map.of("problemId",old.get(0).get("id"),"taskId",old.get(0).get("task_id"),"reused",true);
        }
        var repo=one("select * from maintenance_repositories where id=? and tenant_id=? and enabled=true",request.repositoryId(),actor.tenant());actor.requireZone((String)repo.get("zone"));
        String id=AuthService.id();
        Map<String,Object> requirement=new LinkedHashMap<>(Map.of("requirement",request.requirement(),"acceptance",request.acceptance(),"baseSha",request.baseSha()));
        if(request.allowedPaths()!=null&&!request.allowedPaths().isEmpty()){
            if(request.allowedPaths().stream().anyMatch(p->!p.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||p.contains("..")))throw ApiException.conflict("INVALID_MAINTENANCE_SCOPE");
            requirement.put("allowedPaths",request.allowedPaths());
        }
        if(product!=null)requirement.put("product",product);
        String task=createTask(actor,repo,id,1,request.title(),request.requirement(),request.acceptance());
        db.update("insert into maintenance_problems(id,tenant_id,owner_id,repository_id,task_id,request_key,request_hash,title,requirement_json,platform,state,created_at) values(?,?,?,?,?,?,?,?,?,?,'IN_PROGRESS',?)",id,actor.tenant(),actor.id(),request.repositoryId(),task,requestKey,hash,request.title(),json.write(requirement),request.platform(),clock.millis());
        storeSpec(repo,id,task,1,request.platform(),requirement,request.title());
        return Map.of("problemId",id,"taskId",task,"reused",false);
    }
    private String createTask(Identity actor,Map<String,Object> repo,String problem,int version,String title,String requirement,String acceptance) {
        return (String)control.createScopedTask(actor,"maintenance:"+problem+":"+version,new Requests.Task((String)repo.get("agent_id"),(String)repo.get("zone"),List.of(title+"\nMaintenance problem: "+problem+" version "+version),List.of(),List.of(),false,title),attribution.projectForRepository(actor,(String)repo.get("id"))).get("taskId");
    }
    private void storeSpec(Map<String,Object> repo,String problem,String task,int version,String platform,Map<?,?> requirement,String title) {
        var spec=new LinkedHashMap<String,Object>();
        spec.put("version",version);spec.put("problemId",problem);spec.put("taskId",task);spec.put("repository",repo.get("slug"));spec.put("baseBranch",repo.get("base_branch"));
        spec.put("baseSha",requirement.get("baseSha"));spec.put("platform",platform);spec.put("branch","funnywork/maintenance-"+problem);spec.put("title",title);
        if(requirement.get("product")!=null)spec.put("product",requirement.get("product"));
        spec.put("requirement",requirement.get("requirement"));spec.put("acceptance",requirement.get("acceptance"));spec.put("checks",json.read((String)repo.get("checks_json")));
        if(requirement.get("allowedPaths")!=null)spec.put("allowedPaths",requirement.get("allowedPaths"));
        String encoded=json.write(spec),unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        db.update("insert into maintenance_specs(unit_id,tenant_id,problem_id,platform,spec_json,spec_hash) values(?,?,?,?,?,?)",unit,repo.get("tenant_id"),problem,platform,encoded,AuthService.hash(encoded));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Identity actor) {
        actor=operator(actor);if(actor.zones().isEmpty())return List.of();
        var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(actor.zones());
        return db.queryForList("select p.id,p.title,p.state,p.version,p.platform,p.created_at,p.task_id,r.slug,r.zone from maintenance_problems p join maintenance_repositories r on r.id=p.repository_id where p.tenant_id=? and r.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") order by p.created_at desc,p.id limit 100",args.toArray());
    }
    private Map<String,Object> problem(Identity actor,String id) {
        var p=one("select p.*,r.zone,r.slug from maintenance_problems p join maintenance_repositories r on r.id=p.repository_id where p.id=? and p.tenant_id=?",id,actor.tenant());actor.requireZone((String)p.get("zone"));return p;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(Identity actor,String id) {
        actor=operator(actor);var p=problem(actor,id);var result=new LinkedHashMap<String,Object>(p);
        result.put("requirement",json.read((String)p.get("requirement_json")));
        result.remove("requirement_json");result.remove("request_hash");
        result.put("acceptance",p.get("acceptance_json")==null?null:json.read((String)p.get("acceptance_json")));result.remove("acceptance_json");
        result.put("deliveryJobs",delivery.jobs(actor,id));
        result.put("automated",db.queryForObject("select count(*) from maintenance_automation_policies where repository_id=? and enabled=true",Integer.class,p.get("repository_id"))>0);
        result.put("automationStages",db.queryForList("select id,stage,state,result_json,error_code,updated_at from maintenance_stage_runs where tenant_id=? and subject_id like ? order by created_at",actor.tenant(),id+":%"));
        result.put("runtimeRepairs",db.queryForList("select * from maintenance_runtime_repairs where problem_id=? and tenant_id=?",id,actor.tenant()));
        result.put("taskState",db.queryForObject("select state from tasks where id=?",String.class,p.get("task_id")));
        result.put("reports",db.queryForList("select r.attempt_id,r.report_json,r.artifact_id,r.created_at,s.spec_json from maintenance_reports r join maintenance_specs s on s.unit_id=r.unit_id join work_units u on u.id=r.unit_id where s.problem_id=? and not exists(select 1 from maintenance_delivery_jobs j where j.task_id=u.task_id) order by r.created_at desc",id).stream().map(row->{var value=new LinkedHashMap<String,Object>(row);value.put("report",json.read((String)row.get("report_json")));value.remove("report_json");var assignment=(Map<?,?>)json.read((String)row.get("spec_json"));value.put("version",assignment.get("version"));value.put("current",p.get("task_id").equals(assignment.get("taskId")));value.remove("spec_json");return value;}).toList());return result;
    }
    @Transactional
    public void profile(Identity node,Profile request) {
        locks.lock(node.tenant());node=auth.current(node);node.requireNode();
        if(db.queryForObject("select count(*) from nodes where id=? and tenant_id=? and capability=?",Integer.class,node.id(),node.tenant(),Capabilities.REPOSITORY)!=1)throw ApiException.conflict("CAPABILITY_MISMATCH");
        db.update("update nodes set capacity=1 where id=?",node.id());
        db.update("delete from maintenance_node_profiles where node_id=?",node.id());
        db.update("insert into maintenance_node_profiles values(?,?,?,?,?,?)",node.id(),node.tenant(),request.platform(),request.executorVersion(),request.gitVersion(),clock.millis());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> spec(Identity node,String attempt,long epoch) {
        var lease=control.maintenanceLease(node,attempt,epoch,false);var s=one("select * from maintenance_specs where unit_id=? and tenant_id=?",lease.unitId(),node.tenant());
        return Map.of("spec",json.read((String)s.get("spec_json")),"specHash",s.get("spec_hash"));
    }
    @Transactional
    public Map<String,Object> report(Identity node,String attempt,Report request) {
        locks.lock(node.tenant());var lease=control.maintenanceLease(node,attempt,request.epoch(),true);
        var spec=one("select * from maintenance_specs where unit_id=? and tenant_id=?",lease.unitId(),node.tenant());
        if(!spec.get("spec_hash").equals(request.specHash()))throw ApiException.conflict("MAINTENANCE_SPEC_CHANGED");
        String encoded=json.write(request),digest=AuthService.hash(encoded);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>240000)throw ApiException.conflict("MAINTENANCE_REPORT_TOO_LARGE");
        var old=db.queryForList("select * from maintenance_reports where attempt_id=?",attempt);
        if(!old.isEmpty()) {if(!old.get(0).get("report_hash").equals(digest))throw ApiException.conflict("RESULT_CONFLICT");return Map.of("accepted",true,"duplicate",true);}
        control.maintenanceLease(node,attempt,request.epoch(),false);
        if(((Map<?,?>)json.read((String)spec.get("spec_json"))).get("mode")!=null)return delivery.report(node,attempt,request,lease,spec);
        if(Set.of("VERIFIED","DELIVERED").contains(request.outcome()))throw ApiException.conflict("INVALID_MAINTENANCE_RESULT");
        var p=problem(lease.owner(),(String)spec.get("problem_id"));
        if(!p.get("task_id").equals(lease.taskId()))throw ApiException.conflict("MAINTENANCE_SPEC_CHANGED");
        var config=one("select * from maintenance_repositories where id=?",p.get("repository_id"));
        if(request.checks().stream().map(CheckResult::id).distinct().count()!=request.checks().size())throw ApiException.conflict("DUPLICATE_CHECK");
        if(request.prUrl()!=null && !request.prUrl().matches("https://github\\.com/"+java.util.regex.Pattern.quote((String)p.get("slug"))+"/pull/[1-9][0-9]*"))throw ApiException.conflict("INVALID_MAINTENANCE_CANDIDATE");
        if("CANDIDATE_READY".equals(request.outcome())) {
            if(request.resultSha()==null || request.prUrl()==null || request.resultSha().equals(((Map<?,?>)json.read((String)spec.get("spec_json"))).get("baseSha")))throw ApiException.conflict("INVALID_MAINTENANCE_CANDIDATE");
            Set<String> required=new HashSet<>();
            for(Object value:(List<?>)json.read((String)config.get("checks_json")))required.add((String)((Map<?,?>)value).get("id"));
            if(!required.equals(new HashSet<>(request.checks().stream().map(CheckResult::id).toList())) || request.checks().stream().anyMatch(c->c.exitCode()!=0))throw ApiException.conflict("MAINTENANCE_CHECKS_INCOMPLETE");
        }
        var artifact=artifacts.create(lease.owner(),new ArtifactService.Create("maintenance-report.json",(String)p.get("zone"),Base64.getEncoder().encodeToString(encoded.getBytes(StandardCharsets.UTF_8))));
        String artifactId=(String)artifact.get("artifactId");artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifactId,1));
        db.update("insert into maintenance_reports values(?,?,?,?,?,?,?)",attempt,lease.unitId(),node.tenant(),digest,encoded,artifactId,clock.millis());
        var result=Map.of("kind","REPOSITORY_MAINTENANCE","artifactId",artifactId,"outcome",request.outcome(),"businessVerified",false);
        db.update("insert into unit_results values(?,?,?,?)",lease.unitId(),attempt,json.write(result),clock.millis());
        boolean candidate="CANDIDATE_READY".equals(request.outcome());
        db.update("update attempts set state=?,failure_code=? where id=?",candidate?"SUCCEEDED":"FAILED",candidate?null:request.outcome(),attempt);
        db.update("update work_units set state=? where id=?",candidate?"GENERATED":"FAILED",lease.unitId());
        db.update("update maintenance_problems set state=? where id=?",candidate?"AWAITING_ACCEPTANCE":"BLOCKED",p.get("id"));
        control.refreshTask(lease.taskId());
        auth.audit(node,"MAINTENANCE_REPORT_STORED",Map.of("problemId",p.get("id"),"attemptId",attempt,"artifactId",artifactId));
        return Map.of("accepted",true,"duplicate",false,"artifactId",artifactId,"businessVerified",false);
    }
    @Transactional
    public Map<String,Object> decide(Identity actor,String id,Decision request) {
        var p=problem(operator(actor),id);
        if(db.queryForObject("select count(*) from maintenance_automation_policies where repository_id=? and enabled=true",Integer.class,p.get("repository_id"))>0)throw ApiException.conflict("BACKEND_AGENT_DECISION_REQUIRED");
        return decideInternal(actor,id,request,false);
    }
    @Transactional
    Map<String,Object> decideAutomatic(Identity actor,String id,Decision request) {
        return decideInternal(actor,id,request,true);
    }
    private Map<String,Object> decideInternal(Identity actor,String id,Decision request,boolean automated) {
        locks.lock(actor.tenant());actor=operator(actor);var p=problem(actor,id);
        int version=((Number)p.get("version")).intValue();if(version!=request.expectedVersion())throw ApiException.conflict("MAINTENANCE_VERSION_CHANGED");
        if(!Set.of("AWAITING_ACCEPTANCE","BLOCKED").contains(p.get("state")))throw ApiException.conflict("MAINTENANCE_NOT_REVIEWABLE");
        if("accept".equals(request.action())) {
            if(!"AWAITING_ACCEPTANCE".equals(p.get("state")))throw ApiException.conflict("MAINTENANCE_NOT_REVIEWABLE");
            var latest=one("select r.report_json from maintenance_reports r join work_units u on u.id=r.unit_id where u.task_id=?",p.get("task_id"));
            if(request.candidateSha()==null || !request.candidateSha().equals(((Map<?,?>)json.read((String)latest.get("report_json"))).get("resultSha")))throw ApiException.conflict("MAINTENANCE_CANDIDATE_CHANGED");
            delivery.requireAcceptance(p,request.candidateSha());
            db.update("update maintenance_problems set state='ACCEPTED',version=version+1,acceptance_json=? where id=?",json.write(Map.of("actorId",actor.id(),"decisionSource",automated?"BACKEND_AGENT":"MANUAL","note",request.note(),"candidateSha",request.candidateSha(),"at",clock.millis())),id);
            db.update("update work_units set state='SUCCEEDED' where task_id=? and state='GENERATED'",p.get("task_id"));
            db.update("update tasks set state='SUCCEEDED' where id=? and state='OUTPUT_READY'",p.get("task_id"));
        } else {
            for(var job:db.queryForList("select id,task_id from maintenance_delivery_jobs where problem_id=? and mode='VERIFY' and state='QUEUED'",id)){
                control.cancel(actor,(String)job.get("task_id"));
                db.update("update maintenance_delivery_jobs set state='SUPERSEDED' where id=?",job.get("id"));
            }
            var repo=one("select * from maintenance_repositories where id=? and enabled=true",p.get("repository_id"));
            @SuppressWarnings("unchecked") Map<String,Object> requirement=new LinkedHashMap<>((Map<String,Object>)json.read((String)p.get("requirement_json")));
            String revised=requirement.get("requirement")+"\nRework:\n"+request.note();
            if(revised.length()>50000)throw ApiException.conflict("MAINTENANCE_REQUIREMENT_LIMIT");
            requirement.put("requirement",revised);
            String task=createTask(actor,repo,id,version+1,(String)p.get("title"),(String)requirement.get("requirement"),(String)requirement.get("acceptance"));
            storeSpec(repo,id,task,version+1,(String)p.get("platform"),requirement,(String)p.get("title"));
            db.update("update maintenance_problems set task_id=?,requirement_json=?,state='IN_PROGRESS',version=version+1 where id=?",task,json.write(requirement),id);
        }
        auth.audit(actor,"MAINTENANCE_"+request.action().toUpperCase(Locale.ROOT),Map.of("problemId",id,"version",version));
        return Map.of("problemId",id,"version",version+1);
    }
}
