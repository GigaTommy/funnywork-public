package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Fixed-template annual operating reports; source snapshots and backend arithmetic own publication. */
@Service
public class AnnualReports {
    public record Material(@Pattern(regexp="[a-z0-9-]{1,64}") @NotNull String id,
        @Pattern(regexp="[\\p{L}\\p{N} ._-]{1,120}") @NotNull String label,
        @Pattern(regexp="REVENUE|COST|PRIOR_REVENUE") @NotNull String metric,
        @DecimalMin("0") @DecimalMax("999999999999.99") @Digits(integer=12,fraction=2) @NotNull BigDecimal amount) {}
    public record Dataset(@Pattern(regexp="[\\p{L}\\p{N} ._-]{1,120}") @NotNull String name,
        @Min(2000) @Max(2100) int year,@NotBlank String projectId,@NotEmpty @Size(max=100) List<@Valid Material> materials) {}
    public record Compile(@NotBlank String datasetId,@Pattern(regexp="macos|windows|linux") @NotNull String platform) {}
    public record Candidate(@Min(1) long epoch,@NotBlank String runtimeEpoch,
        @Pattern(regexp="[a-f0-9]{64}") @NotNull String specHash,
        @NotNull @Size(max=32000) String candidateJson) {}
    public record Failure(@Min(1) long epoch,@NotBlank String runtimeEpoch,@Pattern(regexp="[a-f0-9]{64}") @NotNull String specHash) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionResources resources;
    private final Json json;private final ControlService control;private final ArtifactService artifacts;private final Clock clock;
    public AnnualReports(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,ControlService control,ArtifactService artifacts,Clock clock){this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.control=control;this.artifacts=artifacts;this.clock=clock;}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private Identity operator(Identity actor){actor=auth.current(actor);actor.requireUser("OPERATOR");return actor;}
    private Identity reader(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind())||!(actor.roles().contains("VIEWER")||actor.roles().contains("OPERATOR")))throw ApiException.forbidden();return actor;}
    private Map<String,Object> dataset(Identity actor,String id){actor=reader(actor);var d=one("select * from annual_datasets where id=? and tenant_id=? and owner_id=?",id,actor.tenant(),actor.id());actor.requireZone((String)d.get("zone"));return d;}
    @Transactional public Object createDataset(Identity actor,Dataset body){
        locks.lock(actor.tenant());actor=operator(actor);var project=one("select zone from execution_projects where id=? and tenant_id=? and enabled=true",body.projectId(),actor.tenant());String zone=(String)project.get("zone");actor.requireZone(zone);
        if(body.materials().stream().map(Material::id).distinct().count()!=body.materials().size()||!body.materials().stream().map(Material::metric).collect(java.util.stream.Collectors.toSet()).equals(Set.of("REVENUE","COST","PRIOR_REVENUE")))throw ApiException.conflict("REPORT_MATERIALS_INCOMPLETE");
        if(db.queryForObject("select count(*) from annual_datasets where tenant_id=?",Integer.class,actor.tenant())>=200)throw ApiException.conflict("REPORT_DATASET_LIMIT");
        String id=AuthService.id(),materials=json.canonical(body.materials());String hash=AuthService.hash(json.canonical(Map.of("name",body.name(),"year",body.year(),"materials",body.materials())));
        String agent=AuthService.id();db.update("insert into agent_versions(id,tenant_id,name,version,capability,status,created_by) values(?,?,?,'1.0.0',?,'PUBLISHED',?)",agent,actor.tenant(),"annual-"+id,Capabilities.ANNUAL,actor.id());
        db.update("insert into annual_datasets(id,tenant_id,owner_id,zone,project_id,name,report_year,materials_json,snapshot_hash,agent_id,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",id,actor.tenant(),actor.id(),zone,body.projectId(),body.name(),body.year(),materials,hash,agent,clock.millis());
        auth.audit(actor,"ANNUAL_DATASET_CREATED",Map.of("datasetId",id,"snapshotHash",hash));return Map.of("id",id,"snapshotHash",hash);
    }
    @Transactional(readOnly=true) public Object list(Identity actor){actor=reader(actor);final Identity live=actor;return db.queryForList("select d.id,d.name,d.report_year,d.project_id,d.snapshot_hash,d.enabled,d.revision,j.task_id,case when j.state in ('WAITING','COMPILING') then case when t.state<>'RUNNING' then t.state when exists(select 1 from attempts a where a.unit_id=j.unit_id and a.state in ('RESERVED','RUNNING')) then 'COMPILING' else 'WAITING' end else j.state end as state,j.artifact_id,j.evidence_json,d.zone from annual_datasets d left join annual_report_jobs j on j.dataset_id=d.id left join tasks t on t.id=j.task_id where d.tenant_id=? and d.owner_id=? order by d.created_at desc limit 200",actor.tenant(),actor.id()).stream().filter(r->live.zones().contains(r.get("zone"))).toList();}
    @Transactional public Object revoke(Identity actor,String id,ExecutionResources.Switch body){locks.lock(actor.tenant());actor=operator(actor);dataset(actor,id);if(db.update("update annual_datasets set enabled=?,revision=revision+1 where id=? and revision=?",body.enabled(),id,body.expectedVersion())!=1)throw ApiException.conflict("EXECUTION_VERSION_CONFLICT");auth.audit(actor,"ANNUAL_DATASET_ACCESS_CHANGED",Map.of("datasetId",id,"enabled",body.enabled()));return Map.of("revision",body.expectedVersion()+1);}
    @Transactional public Object compile(Identity actor,String key,Compile body){
        locks.lock(actor.tenant());if(!resources.enforced())throw ApiException.conflict("EXECUTION_POLICY_REQUIRED");actor=operator(actor);var d=dataset(actor,body.datasetId());if(!Boolean.TRUE.equals(d.get("enabled")))throw ApiException.forbidden();
        var result=control.createScopedTask(actor,key,new Requests.Task((String)d.get("agent_id"),(String)d.get("zone"),List.of("Annual report "+body.datasetId()+" "+body.platform()),List.of(),List.of(),false,d.get("report_year")+" 年报"),(String)d.get("project_id"));
        String task=(String)result.get("taskId");var old=db.queryForList("select task_id from annual_report_jobs where dataset_id=?",body.datasetId());if(!old.isEmpty()){if(!task.equals(old.get(0).get("task_id")))throw ApiException.conflict("REPORT_ALREADY_REQUESTED");return result;}
        String unit=db.queryForObject("select id from work_units where task_id=?",String.class,task);
        var spec=Map.of("schema","annual-report.v1","taskId",task,"datasetId",body.datasetId(),"snapshotHash",d.get("snapshot_hash"),"name",d.get("name"),"year",d.get("report_year"),"materials",json.read((String)d.get("materials_json")),"platform",body.platform(),"datasetRevision",d.get("revision"));
        String encoded=json.canonical(spec);db.update("insert into annual_report_jobs(task_id,unit_id,tenant_id,dataset_id,platform,spec_json,spec_hash,state,created_at) values(?,?,?,?,?,?,?,'WAITING',?)",task,unit,actor.tenant(),body.datasetId(),body.platform(),encoded,AuthService.hash(encoded),clock.millis());
        auth.audit(actor,"ANNUAL_REPORT_REQUESTED",Map.of("taskId",task,"datasetId",body.datasetId()));return result;
    }
    Map<String,Object> frame(Identity node,String attempt,long epoch){if(!resources.enforced())throw ApiException.conflict("EXECUTION_POLICY_REQUIRED");var lease=control.annualLease(node,attempt,epoch,false);var j=one("select * from annual_report_jobs where task_id=?",lease.taskId());db.update("update annual_report_jobs set state='COMPILING' where task_id=?",lease.taskId());return Map.of("spec",json.read((String)j.get("spec_json")),"specHash",j.get("spec_hash"));}
    private Map<String,Object> facts(Map<String,Object> d){
        var totals=new TreeMap<String,BigDecimal>();for(Object m:(List<?>)json.read((String)d.get("materials_json"))){var v=(Map<?,?>)m;totals.merge((String)v.get("metric"),new BigDecimal(v.get("amount").toString()),BigDecimal::add);}
        BigDecimal revenue=totals.get("REVENUE"),cost=totals.get("COST"),prior=totals.get("PRIOR_REVENUE");
        var facts=new TreeMap<String,Object>();facts.put("revenue",revenue.setScale(2).toPlainString());facts.put("cost",cost.setScale(2).toPlainString());facts.put("profit",revenue.subtract(cost).setScale(2).toPlainString());facts.put("priorRevenue",prior.setScale(2).toPlainString());facts.put("growthPercent",prior.signum()==0?"N/A":revenue.subtract(prior).multiply(new BigDecimal("100")).divide(prior,2,RoundingMode.HALF_UP).toPlainString());return facts;
    }
    private record Check(String code,String status,String reason) {}
    private Check check(String code,boolean runnable,boolean passed,String failure) {
        return new Check(code,!runnable?"NOT_RUN":passed?"PASSED":"FAILED",!runnable?"INVALID_CANDIDATE_FORMAT":passed?"MATCHED":failure);
    }
    @Transactional public Object candidate(Identity node,String attempt,Candidate body){
        locks.lock(node.tenant());if(!resources.enforced())throw ApiException.conflict("EXECUTION_POLICY_REQUIRED");var lease=control.annualLease(node,attempt,body.epoch(),true);var j=one("select * from annual_report_jobs where task_id=?",lease.taskId());var d=dataset(lease.owner(),(String)j.get("dataset_id"));
        if(!Boolean.TRUE.equals(d.get("enabled"))||!body.specHash().equals(j.get("spec_hash")))throw ApiException.conflict("REPORT_SOURCE_CHANGED");
        var frozen=(Map<?,?>)json.read((String)j.get("spec_json"));if(!Objects.equals(d.get("revision"),frozen.get("datasetRevision")))throw ApiException.conflict("REPORT_SOURCE_CHANGED");
        String receipt=AuthService.hash(json.canonical(body));if(j.get("receipt_hash")!=null){if(!receipt.equals(j.get("receipt_hash")))throw ApiException.conflict("RESULT_CONFLICT");return Map.of("accepted",true,"duplicate",true,"state",j.get("state"));}
        control.annualLease(node,attempt,body.epoch(),false);
        Object decoded;try{decoded=json.read(body.candidateJson());}catch(RuntimeException malformed){decoded=null;}
        var expectedFacts=facts(d);var sourceIds=((List<?>)json.read((String)d.get("materials_json"))).stream().map(m->(String)((Map<?,?>)m).get("id")).sorted().toList();
        Map<?,?> c=decoded instanceof Map<?,?> value?value:Map.of();
        Map<?,?> candidateFacts=c.get("facts") instanceof Map<?,?> value?value:Map.of();
        boolean format=c.keySet().equals(Set.of("schema","snapshotHash","facts","sourceIds"))&&"annual-candidate.v1".equals(c.get("schema"))&&candidateFacts.keySet().equals(expectedFacts.keySet());
        var checks=new ArrayList<Check>();
        checks.add(new Check("SOURCE_AUTHORIZATION","PASSED","CURRENT_AUTHORIZATION_VALID"));
        checks.add(check("CANDIDATE_FORMAT",true,format,"CANDIDATE_FORMAT_INVALID"));
        checks.add(check("SNAPSHOT_VERSION",format,d.get("snapshot_hash").equals(c.get("snapshotHash")),"SNAPSHOT_MISMATCH"));
        checks.add(check("RECOMPUTED_TOTALS",format,List.of("revenue","cost","priorRevenue").stream().allMatch(k->Objects.equals(expectedFacts.get(k),candidateFacts.get(k))),"TOTALS_MISMATCH"));
        checks.add(check("PROFIT_RECONCILIATION",format,Objects.equals(expectedFacts.get("profit"),candidateFacts.get("profit")),"PROFIT_MISMATCH"));
        checks.add(check("GROWTH_ROUNDING",format,Objects.equals(expectedFacts.get("growthPercent"),candidateFacts.get("growthPercent")),"GROWTH_MISMATCH"));
        checks.add(check("EXACT_SOURCE_COVERAGE",format,sourceIds.equals(c.get("sourceIds")),"SOURCES_MISMATCH"));
        boolean valid=checks.stream().allMatch(v->"PASSED".equals(v.status()));
        checks.add(new Check("SERVER_RENDERING",valid?"PASSED":"NOT_RUN",valid?"SERVER_FILE_CREATED":"CANDIDATE_REJECTED"));
        var evidence=Map.of("schema","annual-evidence.v2","snapshotHash",d.get("snapshot_hash"),"specHash",body.specHash(),"attemptId",attempt,"epoch",body.epoch(),"checks",checks,"verified",valid,"verifiedAt",clock.millis(),"candidateHash",AuthService.hash(body.candidateJson()),"factsHash",AuthService.hash(json.canonical(expectedFacts)));
        String artifact="";if(valid){String text="# "+d.get("name")+" · "+d.get("report_year")+" 年度经营报告\n\n| 指标 | 金额（元） |\n|---|---:|\n| 营业收入 | "+expectedFacts.get("revenue")+" |\n| 营业成本 | "+expectedFacts.get("cost")+" |\n| 经营利润（收入减成本） | "+expectedFacts.get("profit")+" |\n| 上年营业收入 | "+expectedFacts.get("priorRevenue")+" |\n\n收入同比："+expectedFacts.get("growthPercent")+("N/A".equals(expectedFacts.get("growthPercent"))?"（上年收入为零，无法计算）":"%")+"。\n\n## 资料索引\n\n"+String.join("、",sourceIds)+"\n\n资料快照："+d.get("snapshot_hash")+"\n\n按已授权资料编制的经营摘要。\n";
            artifact=(String)artifacts.create(lease.owner(),new ArtifactService.Create("annual-report-"+d.get("report_year")+".md",(String)d.get("zone"),Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))),"annual:"+lease.taskId()).get("artifactId");artifacts.attach(lease.owner(),lease.taskId(),new ArtifactService.Attachment(artifact,1));}
        String state=valid?"DELIVERED":"REJECTED";db.update("update annual_report_jobs set state=?,candidate_json=?,receipt_hash=?,artifact_id=?,evidence_json=? where task_id=?",state,body.candidateJson(),receipt,artifact,json.canonical(evidence),lease.taskId());
        db.update("insert into unit_results values(?,?,?,?)",lease.unitId(),attempt,json.write(Map.of("kind","ANNUAL_REPORT","businessVerified",valid,"artifactId",artifact,"outcome",state,"evidence",evidence)),clock.millis());
        db.update("update attempts set state=?,failure_code=? where id=?",valid?"SUCCEEDED":"FAILED",valid?null:"REPORT_VERIFICATION_FAILED",attempt);db.update("update work_units set state=? where id=?",valid?"SUCCEEDED":"FAILED",lease.unitId());control.refreshTask(lease.taskId());
        auth.audit(node,"ANNUAL_REPORT_"+state,Map.of("taskId",lease.taskId(),"attemptId",attempt));return Map.of("accepted",true,"duplicate",false,"state",state,"artifactId",artifact,"evidence",evidence);
    }
    @Transactional public Object failure(Identity node,String attempt,Failure body){
        locks.lock(node.tenant());var lease=control.annualLease(node,attempt,body.epoch(),true);
        var j=one("select * from annual_report_jobs where task_id=?",lease.taskId());if(!body.specHash().equals(j.get("spec_hash")))throw ApiException.conflict("REPORT_SOURCE_CHANGED");
        if("RUNTIME_FAILED".equals(j.get("state")))return Map.of("accepted",true,"duplicate",true);
        control.annualLease(node,attempt,body.epoch(),false);
        db.update("update attempts set state='FAILED',failure_code='RUNTIME_FAILED' where id=?",attempt);
        db.update("update work_units set state='FAILED' where id=?",lease.unitId());db.update("update annual_report_jobs set state='RUNTIME_FAILED' where task_id=?",lease.taskId());control.refreshTask(lease.taskId());
        auth.audit(node,"ANNUAL_REPORT_RUNTIME_FAILED",Map.of("taskId",lease.taskId(),"attemptId",attempt));return Map.of("accepted",true,"duplicate",false);
    }
    @Transactional(readOnly=true) public Object projects(Identity actor){actor=reader(actor);final Identity live=actor;return db.queryForList("select id,name,zone,enabled from execution_projects where tenant_id=?",actor.tenant()).stream().filter(r->live.zones().contains(r.get("zone"))).toList();}
    @Transactional(readOnly=true) public Object delivery(Identity actor,String id){var d=dataset(actor,id);if(!Boolean.TRUE.equals(d.get("enabled")))throw ApiException.forbidden();var j=one("select artifact_id from annual_report_jobs where dataset_id=? and state='DELIVERED'",id);return artifacts.read(actor,(String)j.get("artifact_id"),1);}
    @Transactional(readOnly=true) public Object detail(Identity actor,String id){var d=dataset(actor,id);var result=new LinkedHashMap<>(d);result.remove("agent_id");var jobs=db.queryForList("select * from annual_report_jobs where dataset_id=?",id);for(var j:jobs)if(Set.of("WAITING","COMPILING").contains(j.get("state"))){String state=db.queryForObject("select state from tasks where id=?",String.class,j.get("task_id"));j.put("state",!"RUNNING".equals(state)?state:db.queryForObject("select count(*) from attempts where unit_id=? and state in ('RESERVED','RUNNING')",Integer.class,j.get("unit_id"))>0?"COMPILING":"WAITING");}result.put("job",jobs);return result;}
}
