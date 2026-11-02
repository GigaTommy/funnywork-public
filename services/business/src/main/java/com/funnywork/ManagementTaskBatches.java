package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Durable, bounded batches. Each confirmed task has an independent transaction and receipt. */
@Service
public class ManagementTaskBatches {
 private final JdbcTemplate db;private final ManagementTasks tasks;private final ManagementTaskCommands commands;private final AuthService auth;private final TenantLocks locks;private final Json json;private final Clock clock;private final TransactionTemplate transactions;
 public ManagementTaskBatches(JdbcTemplate db,ManagementTasks tasks,ManagementTaskCommands commands,AuthService auth,TenantLocks locks,Json json,Clock clock,PlatformTransactionManager manager){this.db=db;this.tasks=tasks;this.commands=commands;this.auth=auth;this.locks=locks;this.json=json;this.clock=clock;transactions=new TransactionTemplate(manager);}
 public record Preview(String action,List<String> tasks){}
 public record Item(String taskId,String expectedToken){}
 public record Batch(String action,List<Item> items){}
 private Identity actor(Identity actor){actor=auth.current(actor);actor.requireUser("ADMIN");return actor;}
 private void action(String action){if(!Set.of("PAUSE","RESUME","CANCEL").contains(action==null?"":action))throw new ApiException(400,"INVALID_BATCH","请选择支持的管理操作");}
 public Object preview(Identity actor,Preview body){actor=actor(actor);action(body.action());if(body.tasks()==null||body.tasks().isEmpty()||body.tasks().size()>50||new HashSet<>(body.tasks()).size()!=body.tasks().size())throw new ApiException(400,"INVALID_BATCH","请选择 1 至 50 个不同任务");var items=new ArrayList<Map<String,Object>>();for(String task:body.tasks()){if(task==null||task.isEmpty()||task.length()>64)throw new ApiException(400,"INVALID_BATCH","任务编号无效");try{var value=(Map<?,?>)commands.preview(actor,task,body.action());var row=(Map<?,?>)value.get("task");items.add(Map.of("taskId",task,"title",row.get("title"),"allowed",true,"expectedToken",value.get("expectedToken"),"effect",value.get("effect")));}catch(ApiException denied){items.add(Map.of("taskId",task,"allowed",false,"reason",denied.status==409?"STATE_CHANGED":"TASK_UNAVAILABLE"));}}return Map.of("action",body.action(),"items",items);}
 private String create(Identity actor,String key,Batch body){
  action(body.action());if(key==null||!key.matches("[A-Za-z0-9._:-]{16,120}")||body.items()==null||body.items().isEmpty()||body.items().size()>50)throw new ApiException(400,"INVALID_BATCH","批量请求应包含原请求键及 1 至 50 个任务");var ids=new HashSet<String>();
  for(var item:body.items())if(item==null||item.taskId()==null||item.taskId().isEmpty()||item.taskId().length()>64||!ids.add(item.taskId())||item.expectedToken()==null||!item.expectedToken().matches("[a-f0-9]{64}"))throw new ApiException(400,"INVALID_BATCH","批量确认记录无效");
  String hash=AuthService.hash(json.canonical(body));return transactions.execute(status->{locks.lock(actor.tenant());actor(actor);var old=db.queryForList("select id,request_hash from management_task_batches where tenant_id=? and actor_id=? and request_key=?",actor.tenant(),actor.id(),key);if(!old.isEmpty()){if(!hash.equals(old.get(0).get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return (String)old.get(0).get("id");}String id=AuthService.id();db.update("insert into management_task_batches values(?,?,?,?,?,?,?)",id,actor.tenant(),actor.id(),key,hash,body.action(),clock.millis());for(int index=0;index<body.items().size();index++){var item=body.items().get(index);db.update("insert into management_task_batch_items(batch_id,position,task_id,expected_token,state) values(?,?,?,?,'PENDING')",id,index,item.taskId(),item.expectedToken());}auth.audit(actor,"MANAGEMENT_TASK_BATCH_CONFIRMED",Map.of("batchId",id,"count",body.items().size(),"action",body.action()));return id;});
 }
 public Object submit(Identity actor,String key,Batch body){
  actor=actor(actor);String batch=create(actor,key,body);final Identity initiator=actor;
  for(int position=0;position<body.items().size();position++){
   final int ordinal=position;
   try{transactions.executeWithoutResult(status->{locks.lock(initiator.tenant());actor(initiator);var row=db.queryForMap("select * from management_task_batch_items where batch_id=? and position=?",batch,ordinal);if(Set.of("DONE","REJECTED").contains(row.get("state")))return;String task=(String)row.get("task_id"),commandKey=AuthService.hash(batch+":"+ordinal);Object receipt=commands.execute(initiator,task,commandKey,new ManagementTaskCommands.Command(body.action(),(String)row.get("expected_token")));db.update("update management_task_batch_items set state='DONE',result_json=? where batch_id=? and position=?",json.write(receipt),batch,ordinal);});}
   catch(RuntimeException failure){boolean definite=failure instanceof ApiException error&&error.status>=400&&error.status<500&&error.status!=408;String state=definite?"REJECTED":"UNCERTAIN";transactions.executeWithoutResult(status->{locks.lock(initiator.tenant());db.update("update management_task_batch_items set state=?,result_json=? where batch_id=? and position=? and state not in ('DONE','REJECTED')",state,json.write(Map.of("reason",definite?"REPREVIEW_REQUIRED":"RESULT_NOT_CONFIRMED")),batch,ordinal);});if(!definite)break;}
  }return read(actor,key);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object read(Identity actor,String key){
  actor=actor(actor);var batches=db.queryForList("select id,action from management_task_batches where tenant_id=? and actor_id=? and request_key=?",actor.tenant(),actor.id(),key);if(batches.isEmpty())throw ApiException.missing();var batch=batches.get(0);var items=new ArrayList<Map<String,Object>>();boolean unavailable=false,unfinished=false,rejected=false;
  for(var row:db.queryForList("select position,task_id,state,result_json from management_task_batch_items where batch_id=? order by position",batch.get("id"))){var item=new LinkedHashMap<String,Object>();item.put("position",row.get("position"));String state=(String)row.get("state");unfinished|=!Set.of("DONE","REJECTED").contains(state);rejected|=state.equals("REJECTED");try{var task=tasks.visible(actor,(String)row.get("task_id"));item.put("taskId",row.get("task_id"));item.put("title",task.get("title"));item.put("state",state);item.put("result",json.read((String)row.get("result_json")));}catch(ApiException denied){unavailable=true;item.put("state","SCOPE_UNAVAILABLE");}items.add(item);}
  return Map.of("requestKey",key,"action",batch.get("action"),"items",items,"state",unavailable?"SCOPE_UNAVAILABLE":unfinished?"PENDING_RECONCILIATION":rejected?"COMPLETED_WITH_REJECTIONS":"COMPLETED","settled",!unfinished,"physicalStopConfirmed",false);
 }
}
