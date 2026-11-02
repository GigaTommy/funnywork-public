package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;
import com.funnywork.MaintenanceService.Check;

/** Verification and delivery remain ordinary task leases, pinned to the reviewed candidate. */
@Service
public class MaintenanceDelivery {
    @org.springframework.beans.factory.annotation.Autowired private TaskAttribution attribution;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionResources executionResources;
    public record Policy(@NotBlank String repositoryId,@NotEmpty @Size(max=3) List<@Pattern(regexp="macos|windows|linux") String> platforms,
        @NotEmpty @Size(max=3) Map<String,@NotEmpty @Size(max=20) List<@Valid Check>> checks,
        @NotEmpty @Size(max=30) List<@NotBlank @Size(max=160) String> requiredCi,boolean enabled) {}
    public record Action(@Min(1) int expectedVersion,@NotNull @Pattern(regexp="[a-f0-9]{40}|[a-f0-9]{64}") String candidateSha,
        @NotBlank @Size(max=2000) String note) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final ControlService control;
    private final Json json;private final Clock clock;private final ArtifactService artifacts;
    public MaintenanceDelivery(JdbcTemplate db,AuthService auth,TenantLocks locks,ControlService control,Json json,Clock clock,ArtifactService artifacts){this.db=db;this.auth=auth;this.locks=locks;this.control=control;this.json=json;this.clock=clock;this.artifacts=artifacts;}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private Identity operator(Identity actor){actor=auth.current(actor);actor.requireUser("OPERATOR");return actor;}
    private Map<String,Object> problem(Identity actor,String id){var p=one("select p.*,r.zone,r.slug,r.base_branch,r.agent_id,r.enabled from maintenance_problems p join maintenance_repositories r on r.id=p.repository_id where p.id=? and p.tenant_id=?",id,actor.tenant());actor.requireZone((String)p.get("zone"));return p;}
    @Transactional public Object configure(Identity actor,Policy policy){locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");var repo=one("select * from maintenance_repositories where id=? and tenant_id=?",policy.repositoryId(),actor.tenant());actor.requireZone((String)repo.get("zone"));
        if(new HashSet<>(policy.platforms()).size()!=policy.platforms().size()||!policy.checks().keySet().equals(new HashSet<>(policy.platforms()))||policy.requiredCi().stream().distinct().count()!=policy.requiredCi().size())throw ApiException.conflict("INVALID_DELIVERY_POLICY");
        for(var checks:policy.checks().values())if(checks.stream().map(MaintenanceService.Check::id).distinct().count()!=checks.size())throw ApiException.conflict("DUPLICATE_CHECK");
        if(db.queryForObject("select count(*) from maintenance_delivery_jobs j join maintenance_problems p on p.id=j.problem_id where p.repository_id=? and j.state='QUEUED'",Integer.class,policy.repositoryId())>0)throw ApiException.conflict("DELIVERY_POLICY_IN_USE");
        var old=db.queryForList("select * from maintenance_delivery_policies where repository_id=?",policy.repositoryId());
        if(!old.isEmpty()&&db.queryForObject("select count(*) from maintenance_automation_policies a join maintenance_problems p on p.repository_id=a.repository_id where a.repository_id=? and a.enabled=true and p.state in ('IN_PROGRESS','AWAITING_ACCEPTANCE','ACCEPTED')",Integer.class,policy.repositoryId())>0
            &&(!new HashSet<>(policy.platforms()).containsAll(json.strings((String)old.get(0).get("platforms_json")))||!new HashSet<>(policy.requiredCi()).containsAll(json.strings((String)old.get(0).get("required_ci_json")))))throw ApiException.conflict("FROZEN_DELIVERY_REQUIREMENTS");
        db.update("delete from maintenance_delivery_policies where repository_id=?",policy.repositoryId());db.update("insert into maintenance_delivery_policies values(?,?,?,?,?,?)",policy.repositoryId(),actor.tenant(),json.write(policy.platforms()),json.write(policy.checks()),json.write(policy.requiredCi()),policy.enabled());
        auth.audit(actor,"MAINTENANCE_DELIVERY_POLICY",Map.of("repositoryId",policy.repositoryId(),"enabled",policy.enabled()));return Map.of("saved",true);}
    @Transactional(readOnly=true) public List<Map<String,Object>> policies(Identity actor){actor=operator(actor);var zones=actor.zones();return db.queryForList("select d.*,r.slug,r.zone from maintenance_delivery_policies d join maintenance_repositories r on r.id=d.repository_id where d.tenant_id=?",actor.tenant()).stream().filter(r->zones.contains(r.get("zone"))).toList();}
    @Transactional(readOnly=true) public List<Map<String,Object>> jobs(Identity actor,String id){actor=operator(actor);problem(actor,id);return db.queryForList("select j.*,t.state as task_state from maintenance_delivery_jobs j join tasks t on t.id=j.task_id where j.problem_id=? order by j.created_at",id);}
    private Map<?,?> candidate(Map<String,Object> p,Action action){if(((Number)p.get("version")).intValue()!=action.expectedVersion())throw ApiException.conflict("MAINTENANCE_VERSION_CHANGED");
        var row=one("select r.report_json from maintenance_reports r join work_units u on u.id=r.unit_id where u.task_id=?",p.get("task_id"));var report=(Map<?,?>)json.read((String)row.get("report_json"));if(!"CANDIDATE_READY".equals(report.get("outcome"))||!action.candidateSha().equals(report.get("resultSha")))throw ApiException.conflict("MAINTENANCE_CANDIDATE_CHANGED");return report;}
    private String policyHash(Map<String,Object> policy){return AuthService.hash(json.write(List.of(policy.get("platforms_json"),policy.get("checks_json"),policy.get("required_ci_json"))));}
    void requireVerified(String problem,String sha,int version,Map<String,Object> policy){for(var job:db.queryForList("select task_id from maintenance_delivery_jobs where problem_id=? and candidate_sha=? and mode='VERIFY' and state='VERIFIED' and policy_hash=? and problem_version in (?,?)",problem,sha,policyHash(policy),version,version-1))executionResources.requireEvidence((String)job.get("task_id"));for(Object platform:(List<?>)json.read((String)policy.get("platforms_json")))if(db.queryForObject("select count(*) from maintenance_delivery_jobs where problem_id=? and candidate_sha=? and platform=? and mode='VERIFY' and state='VERIFIED' and policy_hash=? and problem_version in (?,?)",Integer.class,problem,sha,platform,policyHash(policy),version,version-1)<1)throw ApiException.conflict("PLATFORM_VERIFICATION_REQUIRED");}
    void requireAcceptance(Map<String,Object> p,String sha){executionResources.requireEvidence((String)p.get("task_id"));var policies=db.queryForList("select * from maintenance_delivery_policies where repository_id=? and enabled=true",p.get("repository_id"));if(!policies.isEmpty())requireVerified((String)p.get("id"),sha,((Number)p.get("version")).intValue(),policies.get(0));}
    @Transactional public Object verify(Identity actor,String id,Action action){locks.lock(actor.tenant());actor=operator(actor);var p=problem(actor,id);if(!Set.of("AWAITING_ACCEPTANCE","ACCEPTED").contains(p.get("state")))throw ApiException.conflict("MAINTENANCE_NOT_REVIEWABLE");var report=candidate(p,action);var policy=one("select * from maintenance_delivery_policies where repository_id=? and enabled=true",p.get("repository_id"));
        var ids=new ArrayList<String>();for(Object platform:(List<?>)json.read((String)policy.get("platforms_json")))ids.add(queue(actor,p,policy,report,action,"VERIFY",(String)platform));return Map.of("jobIds",ids);}
    @Transactional public Object deliver(Identity actor,String id,Action action){locks.lock(actor.tenant());if(executionResources.enforced())throw ApiException.conflict("SERVER_DELIVERY_REQUIRED");actor=operator(actor);var p=problem(actor,id);if(db.queryForObject("select count(*) from maintenance_automation_policies where repository_id=? and enabled=true",Integer.class,p.get("repository_id"))>0)throw ApiException.conflict("SERVER_DELIVERY_REQUIRED");if(!"ACCEPTED".equals(p.get("state")))throw ApiException.conflict("MAINTENANCE_ACCEPTANCE_REQUIRED");var report=candidate(p,action);var acceptance=(Map<?,?>)json.read((String)p.get("acceptance_json"));if(!action.candidateSha().equals(acceptance.get("candidateSha")))throw ApiException.conflict("MAINTENANCE_CANDIDATE_CHANGED");
        var policy=one("select * from maintenance_delivery_policies where repository_id=? and enabled=true",p.get("repository_id"));requireVerified(id,action.candidateSha(),action.expectedVersion(),policy);
        if(db.queryForObject("select count(*) from maintenance_intakes where problem_id=? and state='SOURCE_CHANGED'",Integer.class,id)>0)throw ApiException.conflict("MAINTENANCE_SOURCE_CHANGED");
        return Map.of("jobId",queue(actor,p,policy,report,action,"DELIVER",(String)p.get("platform")));}
    @Transactional
    void replaceVerificationPlan(Identity actor,String id,String previousHash){
        locks.lock(actor.tenant());actor=operator(actor);var p=problem(actor,id);
        if(!"AWAITING_ACCEPTANCE".equals(p.get("state")))throw ApiException.conflict("MAINTENANCE_NOT_REVIEWABLE");
        for(var job:db.queryForList("select * from maintenance_delivery_jobs where problem_id=? and mode='VERIFY' and policy_hash=?",id,previousHash)){
            if("QUEUED".equals(job.get("state")))throw ApiException.conflict("MAINTENANCE_VERIFICATION_ACTIVE");
            db.update("insert into maintenance_verification_history values(?,?,?,?,?,?,?)",job.get("id"),actor.tenant(),id,job.get("problem_version"),job.get("platform"),json.canonical(job),clock.millis());
            db.update("delete from maintenance_delivery_jobs where id=?",job.get("id"));
        }
    }
    @Transactional
    void retryStoppedVerification(Identity actor,String id,Action action){
        locks.lock(actor.tenant());actor=operator(actor);var p=problem(actor,id);var report=candidate(p,action);
        if(!"AWAITING_ACCEPTANCE".equals(p.get("state")))return;
        var policy=one("select * from maintenance_delivery_policies where repository_id=? and enabled=true",p.get("repository_id"));
        for(var job:db.queryForList("select j.* from maintenance_delivery_jobs j join tasks t on t.id=j.task_id where j.problem_id=? and j.problem_version=? and j.candidate_sha=? and j.mode='VERIFY' and j.state='BLOCKED' and t.state='FAILED'",id,action.expectedVersion(),action.candidateSha())){
            if(job.get("report_json")==null||!"RUNTIME_FAILED".equals(((Map<?,?>)json.read((String)job.get("report_json"))).get("outcome"))||!policyHash(policy).equals(job.get("policy_hash")))continue;
            if(db.queryForObject("select count(*) from maintenance_verification_history where problem_id=? and problem_version=? and platform=?",Integer.class,id,action.expectedVersion(),job.get("platform"))>=2)continue;
            db.update("insert into maintenance_verification_history values(?,?,?,?,?,?,?)",job.get("id"),actor.tenant(),id,action.expectedVersion(),job.get("platform"),json.canonical(job),clock.millis());
            db.update("delete from maintenance_delivery_jobs where id=? and state='BLOCKED'",job.get("id"));
            String next=queue(actor,p,policy,report,action,"VERIFY",(String)job.get("platform"));
            auth.audit(actor,"MAINTENANCE_VERIFICATION_RECOVERED",Map.of("previousJobId",job.get("id"),"jobId",next,"candidateSha",action.candidateSha()));
        }
    }
    @Transactional
    void reconcile(Identity actor,String repositoryId){
        locks.lock(actor.tenant());actor=operator(actor);
        var repository=one("select zone from maintenance_repositories where id=? and tenant_id=?",repositoryId,actor.tenant());actor.requireZone((String)repository.get("zone"));
        for(var job:db.queryForList("select j.id,j.task_id,j.problem_version,p.version,p.state as problem_state,t.state as task_state from maintenance_delivery_jobs j join maintenance_problems p on p.id=j.problem_id join tasks t on t.id=j.task_id where p.repository_id=? and p.tenant_id=? and j.mode='VERIFY' and j.state='QUEUED'",repositoryId,actor.tenant())){
            int valid=((Number)job.get("version")).intValue()-(Set.of("ACCEPTED","DELIVERED").contains(job.get("problem_state"))?1:0);
            if(((Number)job.get("problem_version")).intValue()<valid){control.cancel(actor,(String)job.get("task_id"));db.update("update maintenance_delivery_jobs set state='SUPERSEDED' where id=?",job.get("id"));}
            else if(Set.of("FAILED","CANCELLED","RECONCILED").contains(job.get("task_state")))db.update("update maintenance_delivery_jobs set state='BLOCKED' where id=?",job.get("id"));
        }
    }
    private String queue(Identity actor,Map<String,Object> p,Map<String,Object> policy,Map<?,?> report,Action action,String mode,String platform){String digest=AuthService.hash(json.write(action));var old=db.queryForList("select * from maintenance_delivery_jobs where problem_id=? and problem_version=? and mode=? and platform=?",p.get("id"),action.expectedVersion(),mode,platform);if(!old.isEmpty()){if(!digest.equals(old.get(0).get("request_hash")))throw ApiException.conflict("DELIVERY_REQUEST_CONFLICT");return (String)old.get(0).get("id");}
        String id=AuthService.id();String task=(String)control.createScopedTask(actor,"delivery:"+id,new Requests.Task((String)p.get("agent_id"),(String)p.get("zone"),List.of(mode+" "+p.get("id")),List.of(),List.of(),false,mode+" "+p.get("title")),attribution.projectForRepository(actor,(String)p.get("repository_id"))).get("taskId");
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);var spec=new LinkedHashMap<String,Object>();spec.put("mode",mode);spec.put("version",action.expectedVersion());spec.put("problemId",p.get("id"));spec.put("taskId",task);spec.put("jobId",id);spec.put("repository",p.get("slug"));spec.put("baseBranch",p.get("base_branch"));spec.put("baseSha",((Map<?,?>)json.read((String)p.get("requirement_json"))).get("baseSha"));spec.put("candidateSha",action.candidateSha());spec.put("prUrl",report.get("prUrl"));spec.put("platform",platform);spec.put("branch","funnywork/maintenance-"+p.get("id"));spec.put("title",mode+" "+((String)p.get("title")).substring(0,Math.min(145,((String)p.get("title")).length())));spec.put("requirement",action.note());spec.put("acceptance","Verify exact candidate; never invoke a coding agent or change repository files.");
        spec.put("checks","VERIFY".equals(mode)?((Map<?,?>)json.read((String)policy.get("checks_json"))).get(platform):List.of(new MaintenanceService.Check("delivery",List.of("git","diff","--check"),30)));spec.put("requiredCi",json.read((String)policy.get("required_ci_json")));
        spec.put("issues",db.queryForList("select distinct s.source_url from maintenance_intake_sources s join maintenance_intakes i on i.id=s.intake_id where i.problem_id=? and s.source_kind='GITHUB'",p.get("id")).stream().map(s->s.get("source_url")).toList());
        String encoded=json.write(spec);db.update("insert into maintenance_specs values(?,?,?,?,?,?)",unit,actor.tenant(),p.get("id"),platform,encoded,AuthService.hash(encoded));db.update("insert into maintenance_delivery_jobs values(?,?,?,?,?,?,?,?,'QUEUED',?,?,?,null)",id,actor.tenant(),p.get("id"),task,action.expectedVersion(),action.candidateSha(),mode,platform,digest,policyHash(policy),clock.millis());
        auth.audit(actor,"MAINTENANCE_"+mode+"_AUTHORIZED",Map.of("jobId",id,"candidateSha",action.candidateSha(),"note",action.note()));return id;}
    Map<String,Object> report(Identity node,String attempt,MaintenanceService.Report report,ControlService.ModelLease lease,Map<String,Object> assignment){var job=one("select * from maintenance_delivery_jobs where task_id=? and tenant_id=?",lease.taskId(),node.tenant());
        var spec=(Map<?,?>)json.read((String)assignment.get("spec_json"));String expected="VERIFY".equals(job.get("mode"))?"VERIFIED":"DELIVERED";boolean success=expected.equals(report.outcome());if(report.checks().stream().map(MaintenanceService.CheckResult::id).distinct().count()!=report.checks().size())throw ApiException.conflict("DUPLICATE_CHECK");if(success){if(!job.get("candidate_sha").equals(report.resultSha())||!spec.get("prUrl").equals(report.prUrl()))throw ApiException.conflict("MAINTENANCE_CANDIDATE_CHANGED");Set<String> checks=new HashSet<>();for(Object c:(List<?>)spec.get("checks"))checks.add((String)((Map<?,?>)c).get("id"));if(!checks.equals(new HashSet<>(report.checks().stream().map(MaintenanceService.CheckResult::id).toList()))||report.checks().stream().anyMatch(c->c.exitCode()!=0))throw ApiException.conflict("MAINTENANCE_CHECKS_INCOMPLETE");}else if(!Set.of("CHECKS_FAILED","RUNTIME_FAILED","NEEDS_ENVIRONMENT").contains(report.outcome()))throw ApiException.conflict("INVALID_DELIVERY_RESULT");
        if(success&&"DELIVER".equals(job.get("mode"))){
            Object parsed=json.read(report.summary());if(!(parsed instanceof Map<?,?> proof)||!job.get("candidate_sha").equals(proof.get("candidateSha"))||!spec.get("prUrl").equals(proof.get("prUrl"))||!(proof.get("mergeSha") instanceof String merged)||!merged.matches("[a-f0-9]{40}|[a-f0-9]{64}")||!(proof.get("issues") instanceof List<?> issues))throw ApiException.conflict("DELIVERY_PROOF_REQUIRED");
            Set<String> expectedIssues=new HashSet<>();for(Object issue:(List<?>)spec.get("issues"))expectedIssues.add((String)issue);Set<String> actualIssues=new HashSet<>();for(Object issue:issues){if(!(issue instanceof Map<?,?> item)||!(item.get("issue") instanceof String url)||!(item.get("commentId") instanceof Number comment)||comment.longValue()<1||!Boolean.TRUE.equals(item.get("closed"))||!actualIssues.add(url))throw ApiException.conflict("DELIVERY_PROOF_REQUIRED");}if(!expectedIssues.equals(actualIssues))throw ApiException.conflict("DELIVERY_PROOF_REQUIRED");
        }
        var p=problem(lease.owner(),(String)job.get("problem_id"));if(((Number)p.get("version")).intValue()!=((Number)job.get("problem_version")).intValue()||!Set.of("AWAITING_ACCEPTANCE","ACCEPTED").contains(p.get("state")))throw ApiException.conflict("MAINTENANCE_VERSION_CHANGED");
        String encoded=json.write(report);var artifact=artifacts.create(lease.owner(),new ArtifactService.Create("maintenance-delivery-report.json",(String)p.get("zone"),Base64.getEncoder().encodeToString(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))));String artifactId=(String)artifact.get("artifactId");artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifactId,1));db.update("insert into maintenance_reports values(?,?,?,?,?,?,?)",attempt,lease.unitId(),node.tenant(),AuthService.hash(encoded),encoded,artifactId,clock.millis());
        db.update("insert into unit_results values(?,?,?,?)",lease.unitId(),attempt,json.write(Map.of("kind","MAINTENANCE_"+job.get("mode"),"outcome",report.outcome(),"businessVerified",false)),clock.millis());db.update("update attempts set state=?,failure_code=? where id=?",success?"SUCCEEDED":"FAILED",success?null:report.outcome(),attempt);db.update("update work_units set state=? where id=?",success?"SUCCEEDED":"FAILED",lease.unitId());control.refreshTask(lease.taskId());db.update("update maintenance_delivery_jobs set state=?,report_json=? where id=?",success?expected:"BLOCKED",json.write(report),job.get("id"));
        auth.audit(node,"MAINTENANCE_DELIVERY_REPORT",Map.of("jobId",job.get("id"),"outcome",report.outcome()));return Map.of("accepted",true,"duplicate",false,"businessVerified",false);}
}
