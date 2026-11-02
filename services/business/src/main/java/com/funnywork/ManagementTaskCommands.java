package com.funnywork;

import java.util.*;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Explicit administrative commands reuse execution safety logic; never impersonate the owner. */
@Service
public class ManagementTaskCommands {
 private final JdbcTemplate db;private final ManagementTasks tasks;private final ControlService control;
 private final TenantLocks locks;private final Json json;private final AuthService auth;private final Clock clock;
 public ManagementTaskCommands(JdbcTemplate db,ManagementTasks tasks,ControlService control,TenantLocks locks,Json json,AuthService auth,Clock clock){this.db=db;this.tasks=tasks;this.control=control;this.locks=locks;this.json=json;this.auth=auth;this.clock=clock;}
 public record Preview(String action){}
 public record Command(String action,String expectedToken){}
 private void action(String action){if(action==null||!Set.of("PAUSE","RESUME","CANCEL").contains(action))throw new ApiException(400,"INVALID_TASK_ACTION","请选择暂停后续调度、恢复后续调度或取消");}
 private String token(Map<String,Object> task){return AuthService.hash(json.canonical(Map.of("task",task,"scopeVersion",task.getOrDefault("scopeVersion",0))));}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object preview(Identity actor,String id,String action){action(action);var task=tasks.detail(actor,id);if(!"RUNNING".equals(task.get("state")))throw ApiException.conflict("TASK_NOT_RUNNING");return Map.of("taskId",id,"action",action,"expectedToken",token(task),"effect",action.equals("CANCEL")?"请求取消并保留未知执行核对；不保证外部进程已停止，也不删除已有产物。":action.equals("PAUSE")?"停止后续调度；正在执行的步骤可以继续完成。":"恢复后续调度，仍需通过创建者授权与执行资源检查。", "task",task);}
 @Transactional public Object execute(Identity actor,String id,String key,Command body){locks.lock(actor.tenant());actor=auth.current(actor);tasks.visible(actor,id);action(body.action());if(key==null||!key.matches("[A-Za-z0-9_-]{1,120}")||body.expectedToken()==null||!body.expectedToken().matches("[a-f0-9]{64}"))throw new ApiException(400,"INVALID_TASK_COMMAND","请重新预览后提交");String hash=AuthService.hash(json.canonical(body));var old=db.queryForList("select * from management_task_commands where tenant_id=? and actor_id=? and request_key=?",actor.tenant(),actor.id(),key);if(!old.isEmpty()){var row=old.get(0);if(!id.equals(row.get("task_id"))||!hash.equals(row.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return json.read((String)row.get("result_json"));}
  var task=tasks.detail(actor,id);if(!body.expectedToken().equals(token(task)))throw ApiException.conflict("TASK_PREVIEW_CHANGED");if(!"RUNNING".equals(task.get("state")))throw ApiException.conflict("TASK_NOT_RUNNING");
  if(body.action().equals("CANCEL"))control.managementCancel(actor,id);else control.managementAdmission(actor,id,body.action().equals("PAUSE"));
  var result=Map.of("taskId",id,"requestKey",key,"action",body.action(),"state",db.queryForObject("select state from tasks where id=?",String.class,id),"submitted",true,"physicalStopConfirmed",false);
  db.update("insert into management_task_commands values(?,?,?,?,?,?,?,?,?)",AuthService.id(),actor.tenant(),actor.id(),id,key,hash,body.action(),json.canonical(result),clock.millis());auth.audit(actor,"MANAGEMENT_TASK_COMMAND",Map.of("taskId",id,"action",body.action(),"requestKey",key));return result;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object receipt(Identity actor,String id,String key){tasks.visible(actor,id);var rows=db.queryForList("select result_json from management_task_commands where tenant_id=? and actor_id=? and task_id=? and request_key=?",actor.tenant(),actor.id(),id,key);if(rows.isEmpty())throw ApiException.missing();return json.read((String)rows.get(0).get("result_json"));}
}
