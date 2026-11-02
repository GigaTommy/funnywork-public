package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Local member assignment and immutable creation attribution; never an execution authorization. */
@Service
public class TaskAttribution {
 private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;private final Clock clock;private final ManagementTasks tasks;
 public TaskAttribution(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,Clock clock,ManagementTasks tasks){this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.clock=clock;this.tasks=tasks;}
 public record Assignment(String zone,String departmentId,int expectedVersion){}
 private Identity manager(Identity actor,String principal,String zone){actor=tasks.unrestricted(actor);actor.requireZone(zone);var target=db.queryForList("select zones from principals where id=? and tenant_id=? and kind='USER'",principal,actor.tenant());if(target.isEmpty())throw ApiException.missing();if(!actor.zones().containsAll(json.strings((String)target.get(0).get("zones")))||!json.strings((String)target.get(0).get("zones")).contains(zone))throw ApiException.forbidden();return actor;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> assignment(Identity actor,String principal,String zone){actor=manager(actor,principal,zone);var rows=db.queryForList("select m.department_id as \"departmentId\",m.version,coalesce(d.name,'') as \"departmentName\" from task_member_departments m left join task_departments d on d.id=m.department_id and d.tenant_id=m.tenant_id and d.zone=m.zone where m.principal_id=? and m.tenant_id=? and m.zone=?",principal,actor.tenant(),zone);return rows.isEmpty()?Map.of("departmentId","","version",0):rows.get(0);}
 @Transactional public Object assign(Identity actor,String principal,Assignment body){locks.lock(actor.tenant());if(body.zone()==null||body.departmentId()==null||body.departmentId().length()>64)throw new ApiException(400,"INVALID_MEMBER_DEPARTMENT","请选择成员、区域和部门");actor=manager(actor,principal,body.zone());if(!body.departmentId().isEmpty()&&db.queryForObject("select count(*) from task_departments where id=? and tenant_id=? and zone=?",Integer.class,body.departmentId(),actor.tenant(),body.zone())!=1)throw ApiException.forbidden();int version=((Number)assignment(actor,principal,body.zone()).get("version")).intValue();if(version!=body.expectedVersion()||version==Integer.MAX_VALUE)throw ApiException.conflict("MEMBER_DEPARTMENT_CHANGED");if(version==0)db.update("insert into task_member_departments values(?,?,?,?,1,?,?)",principal,actor.tenant(),body.zone(),body.departmentId(),clock.millis(),actor.id());else db.update("update task_member_departments set department_id=?,version=version+1,changed_at=?,changed_by=? where principal_id=? and zone=? and tenant_id=?",body.departmentId(),clock.millis(),actor.id(),principal,body.zone(),actor.tenant());auth.audit(actor,"TASK_MEMBER_DEPARTMENT_ASSIGNED",Map.of("principalId",principal,"zone",body.zone(),"departmentId",body.departmentId(),"version",version+1));return Map.of("version",version+1);}
 // Called only within the task-creation transaction, after the task row exists and under its tenant lock.
 void capture(Identity owner,String task,String zone,String project){
  var members=db.queryForList("select m.department_id,m.version,d.name from task_member_departments m left join task_departments d on d.id=m.department_id and d.tenant_id=m.tenant_id and d.zone=m.zone where m.principal_id=? and m.tenant_id=? and m.zone=?",owner.id(),owner.tenant(),zone);
  String department="",departmentName="";int memberVersion=0;
  if(!members.isEmpty()){var row=members.get(0);memberVersion=((Number)row.get("version")).intValue();if(row.get("name") instanceof String name){department=(String)row.get("department_id");departmentName=name;}}
  String projectName="";if(!project.isEmpty()){var rows=db.queryForList("select name from execution_projects where id=? and tenant_id=? and zone=?",project,owner.tenant(),zone);if(rows.isEmpty())throw ApiException.forbidden();projectName=(String)rows.get(0).get("name");}
  db.update("insert into task_creation_attribution(task_id,tenant_id,department_id,department_name,department_basis,member_version,project_id,project_name,project_basis,captured_at) values(?,?,?,?,?,?,?,?,?,?)",task,owner.tenant(),department,departmentName,department.isEmpty()?"UNKNOWN":"LOCAL_MEMBER_ASSIGNMENT",memberVersion,project,projectName,project.isEmpty()?"UNKNOWN":"SPECIALIZED_TASK_RESOURCE",clock.millis());
  if(!department.isEmpty()||!project.isEmpty())db.update("insert into task_management_context values(?,?,?,?,'CREATION_SNAPSHOT',1,?,?)",task,owner.tenant(),department,project,clock.millis(),owner.id());
 }
 String projectForRepository(Identity actor,String repository){var rows=db.queryForList("select distinct r.project_id from execution_routes r join execution_projects p on p.id=r.project_id and p.tenant_id=r.tenant_id where r.tenant_id=? and r.repository_id=?",actor.tenant(),repository);if(rows.size()>1)throw ApiException.conflict("EXECUTION_PROJECT_CONFLICT");return rows.isEmpty()?"":(String)rows.get(0).get("project_id");}
}
