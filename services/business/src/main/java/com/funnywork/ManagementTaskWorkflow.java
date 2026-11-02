package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Scoped workflow metadata. Existing purpose-specific protocols remain the only write authority. */
@Service
public class ManagementTaskWorkflow {
 private final JdbcTemplate db;private final ManagementTasks tasks;private final Json json;private final Clock clock;
 public ManagementTaskWorkflow(JdbcTemplate db,ManagementTasks tasks,Json json,Clock clock){this.db=db;this.tasks=tasks;this.json=json;this.clock=clock;}
 private String safe(Object value){return value instanceof String text&&text.matches("[A-Z0-9_]{1,80}")?text:"UNKNOWN";}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object inspect(Identity actor,String task){
  var metadata=tasks.visible(actor,task);var reports=db.queryForList("select j.state,j.dataset_id,j.artifact_id,j.evidence_json,j.created_at,d.name,d.report_year,d.enabled from annual_report_jobs j join annual_datasets d on d.id=j.dataset_id and d.tenant_id=j.tenant_id where j.task_id=? and j.tenant_id=?",task,actor.tenant());
  if(!reports.isEmpty()){
   var row=reports.get(0);String state=safe(row.get("state"));boolean evidence=false;if(row.get("evidence_json") instanceof String text&&json.read(text) instanceof Map<?,?> value)evidence=Boolean.TRUE.equals(value.get("verified"));boolean delivered=state.equals("DELIVERED")&&evidence&&row.get("artifact_id") instanceof String id&&!id.isEmpty();
   return Map.of("kind","ANNUAL_REPORT","subjectId",row.get("dataset_id"),"state",state,"executionState",metadata.get("state"),"validation",evidence?"SERVER_VERIFIED":"NOT_CONFIRMED","delivery",delivered?"DELIVERED":"NOT_CONFIRMED","nextStep",delivered?"正式文件已由后端重算并生成；有文件读取权限时可从文件目录下载。":state.equals("REJECTED")?"候选未通过后端核验；核对资料后新增修订资料集重新发起。":!"RUNNING".equals(metadata.get("state"))?"当前执行已结束；请负责人核对资料与资源后新增修订资料集，不自动重复编制。":"等待授权终端编制及后端独立核验。","workbench",Map.of("kind","ANNUAL_REPORT","id",row.get("dataset_id"),"canOpen",actor.id().equals(metadata.get("owner_id"))&&(actor.roles().contains("OPERATOR")||actor.roles().contains("VIEWER"))),"observedAt",clock.millis(),"contentIncluded",false);
  }
  var candidates=db.queryForList("select distinct p.id,p.task_id,p.state,p.version,p.repository_id from maintenance_specs s join work_units u on u.id=s.unit_id and u.tenant_id=s.tenant_id join maintenance_problems p on p.id=s.problem_id and p.tenant_id=s.tenant_id where u.task_id=? and s.tenant_id=?",task,actor.tenant());
  if(candidates.isEmpty())return Map.of("kind","GENERAL","executionState",metadata.get("state"),"validation","NOT_COVERED","delivery","NOT_COVERED","nextStep","执行状态与业务验收分开；此用途尚未接入专用验收和交付流程。","observedAt",clock.millis(),"contentIncluded",false);
  var problem=candidates.get(0);String subject=(String)problem.get("id"),current=(String)problem.get("task_id");
  try{tasks.visible(actor,current);}catch(ApiException unavailable){return Map.of("kind","MAINTENANCE","currentWorkflowVisible",false,"nextStep","当前主任务不在授权范围，不能读取其后续验收和交付状态。","contentIncluded",false);}
  var stages=db.queryForList("select id,subject_id,stage,state,error_code,attempts,updated_at from maintenance_stage_runs where tenant_id=? and repository_id=? and subject_id like ? order by created_at desc,id desc limit 101",actor.tenant(),problem.get("repository_id"),subject+":%");boolean more=stages.size()>100;if(more)stages=new ArrayList<>(stages.subList(0,100));for(var row:stages){row.put("stage",safe(row.get("stage")));row.put("state",safe(row.get("state")));if(row.get("error_code")!=null)row.put("error_code",safe(row.get("error_code")));}
  var jobs=db.queryForList("select id,task_id,mode,platform,state,problem_version,candidate_sha,created_at from maintenance_delivery_jobs where problem_id=? and tenant_id=? order by created_at desc,id desc limit 101",subject,actor.tenant());boolean moreJobs=jobs.size()>100;if(moreJobs)jobs=new ArrayList<>(jobs.subList(0,100));Set<String> visible=tasks.visibleIds(actor,jobs.stream().map(job->(String)job.get("task_id")).toList());var visibleJobs=new ArrayList<Map<String,Object>>();for(var job:jobs)if(visible.contains(job.get("task_id")))visibleJobs.add(job);
  boolean automated=db.queryForObject("select count(*) from maintenance_automation_policies where repository_id=? and tenant_id=? and enabled=true",Integer.class,problem.get("repository_id"),actor.tenant())>0;
  String candidate="";var currentReports=db.queryForList("select r.report_json from maintenance_reports r join work_units u on u.id=r.unit_id where u.task_id=? and r.tenant_id=? order by r.created_at desc limit 1",current,actor.tenant());if(!currentReports.isEmpty()&&json.read((String)currentReports.get(0).get("report_json")) instanceof Map<?,?> value&&value.get("resultSha") instanceof String sha&&sha.matches("[a-f0-9]{40}|[a-f0-9]{64}"))candidate=sha;
  for(var stage:stages)stage.put("currentCandidate",!candidate.isEmpty()&&(subject+":"+candidate).equals(stage.remove("subject_id")));
  // Confirm only the current candidate's original delivery protocol, never an HTTP MERGE receipt alone.
  boolean delivered=false;
  if(!candidate.isEmpty()&&"ACCEPTED".equals(problem.get("state")))for(var record:db.queryForList("select input_json from maintenance_stage_runs where tenant_id=? and repository_id=? and subject_id=? and stage='DELIVERY' and state='PASSED'",actor.tenant(),problem.get("repository_id"),subject+":"+candidate))if(json.read((String)record.get("input_json")) instanceof Map<?,?> input&&input.get("problemVersion") instanceof Number version&&version.intValue()==((Number)problem.get("version")).intValue())delivered=true;
  final String currentCandidate=candidate;if(!delivered&&!candidate.isEmpty()&&"ACCEPTED".equals(problem.get("state")))delivered=visibleJobs.stream().anyMatch(row->"DELIVER".equals(row.get("mode"))&&"DELIVERED".equals(row.get("state"))&&currentCandidate.equals(row.get("candidate_sha"))&&((Number)row.get("problem_version")).intValue()>=((Number)problem.get("version")).intValue()-1);
  var result=new LinkedHashMap<String,Object>();result.put("kind","MAINTENANCE");result.put("subjectId",subject);result.put("currentWorkflowVisible",true);result.put("requestedTaskIsCurrent",task.equals(current));result.put("problemVersion",problem.get("version"));result.put("state",safe(problem.get("state")));result.put("validation","ACCEPTED".equals(problem.get("state"))?"ACCEPTED":"NOT_CONFIRMED");result.put("delivery",delivered?"DELIVERY_FLOW_CONFIRMED":"NOT_CONFIRMED");result.put("automated",automated);result.put("stages",stages);result.put("jobs",visibleJobs);result.put("hasMoreHistory",more||moreJobs);result.put("nextStep",delivered?"开发交付流程已确认；这不表示生产发布或应用已安装。":automated?"由后端独立审查、自动验收和受控交付；出现未知执行时保留核对，不绕过原流程。":"在代码维护工作台按候选版本完成平台验证、验收与受控交付；元数据权限不开放这些写操作。");result.put("workbench",Map.of("kind","MAINTENANCE","id",subject,"canOpen",actor.roles().contains("OPERATOR")));result.put("observedAt",clock.millis());result.put("contentIncluded",false);return result;
 }
}
