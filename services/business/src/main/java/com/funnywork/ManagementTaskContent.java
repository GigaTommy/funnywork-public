package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Explicitly authorized, audited content reads. No event payloads or arbitrary result JSON. */
@Service
public class ManagementTaskContent {
 private final JdbcTemplate db;private final ManagementTasks tasks;private final TenantLocks locks;private final AuthService auth;private final Json json;private final Clock clock;
 public ManagementTaskContent(JdbcTemplate db,ManagementTasks tasks,TenantLocks locks,AuthService auth,Json json,Clock clock){this.db=db;this.tasks=tasks;this.locks=locks;this.auth=auth;this.json=json;this.clock=clock;}
 public record Access(boolean readInput,boolean readArtifacts,int expectedVersion){}
 private Identity manager(Identity actor,String principal){actor=tasks.unrestricted(actor);var rows=db.queryForList("select zones,roles from principals where id=? and tenant_id=? and kind='USER'",principal,actor.tenant());if(rows.isEmpty())throw ApiException.missing();if(!json.strings((String)rows.get(0).get("roles")).contains("ADMIN")||!actor.zones().containsAll(json.strings((String)rows.get(0).get("zones"))))throw ApiException.forbidden();return actor;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> access(Identity actor,String principal){actor=manager(actor,principal);return policy(actor.tenant(),principal);}
 private Map<String,Object> policy(String tenant,String principal){var rows=db.queryForList("select read_input as \"readInput\",read_artifacts as \"readArtifacts\",version from management_task_content_access where principal_id=? and tenant_id=?",principal,tenant);return rows.isEmpty()?Map.of("readInput",false,"readArtifacts",false,"version",0):rows.get(0);}
 @Transactional public Object save(Identity actor,String principal,Access body){locks.lock(actor.tenant());actor=manager(actor,principal);int version=((Number)policy(actor.tenant(),principal).get("version")).intValue();if(body.expectedVersion()!=version||version==Integer.MAX_VALUE)throw ApiException.conflict("TASK_CONTENT_ACCESS_CHANGED");if(version==0)db.update("insert into management_task_content_access values(?,?,?,?,1,?,?)",principal,actor.tenant(),body.readInput(),body.readArtifacts(),clock.millis(),actor.id());else db.update("update management_task_content_access set read_input=?,read_artifacts=?,version=version+1,changed_at=?,changed_by=? where principal_id=? and tenant_id=?",body.readInput(),body.readArtifacts(),clock.millis(),actor.id(),principal,actor.tenant());auth.audit(actor,"TASK_CONTENT_ACCESS_SAVED",Map.of("principalId",principal,"readInput",body.readInput(),"readArtifacts",body.readArtifacts(),"version",version+1));return Map.of("version",version+1);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object capabilities(Identity actor,String task){tasks.visible(actor,task);var policy=policy(actor.tenant(),actor.id());return Map.of("readInput",policy.get("readInput"),"readArtifacts",policy.get("readArtifacts"),"basis","EXPLICIT_CONTENT_ACCESS_AND_CURRENT_TASK_SCOPE");}
 private Map<String,Object> allowed(Identity actor,String task,String dimension){locks.lock(actor.tenant());var row=tasks.visible(actor,task);if(!Boolean.TRUE.equals(policy(actor.tenant(),actor.id()).get(dimension)))throw ApiException.forbidden();return row;}
 @Transactional public Object input(Identity actor,String task,String unit){allowed(actor,task,"readInput");var rows=db.queryForList("select id,ordinal,input_text from work_units where id=? and task_id=? and tenant_id=?",unit,task,actor.tenant());if(rows.isEmpty())throw ApiException.missing();auth.auditRead(actor,"MANAGEMENT_TASK_INPUT_READ",Map.of("taskId",task,"unitId",unit));return rows.get(0);}

 private Map<String,String> outputReferences(Identity actor,String task){
  var refs=new HashMap<String,String>();
  for(var row:db.queryForList("select r.result_json from work_units u join unit_results r on r.unit_id=u.id where u.task_id=? and u.tenant_id=? order by u.ordinal limit 100",task,actor.tenant())){
   Object decoded=json.read((String)row.get("result_json"));if(decoded instanceof Map<?,?> result&&"MODEL_OUTPUT".equals(result.get("kind"))&&result.get("artifactId") instanceof String id&&result.get("sha256") instanceof String sha)refs.put(id,sha);
  }return refs;
 }
 private List<Map<String,Object>> outputMetadata(Identity actor,String task,String zone,Map<String,String> refs){
  if(refs.isEmpty())return List.of();var args=new ArrayList<Object>(List.of(task,actor.tenant(),zone));args.addAll(refs.keySet());
  return db.queryForList("select a.id as artifact_id,1 as version,a.name,v.sha256,v.byte_size,'MODEL_OUTPUT' as source from artifacts a join artifact_versions v on v.artifact_id=a.id and v.version=1 join tasks t on t.id=? and t.tenant_id=a.tenant_id and t.owner_id=a.owner_id where a.tenant_id=? and a.zone=? and a.id in ("+String.join(",",Collections.nCopies(refs.size(),"?"))+")",args.toArray()).stream().filter(row->Objects.equals(refs.get(row.get("artifact_id")),row.get("sha256"))).toList();
 }
 @Transactional public Object artifacts(Identity actor,String task,String after,int afterVersion){
  var taskRow=allowed(actor,task,"readArtifacts");if(after==null||after.length()>64||afterVersion<0||after.isEmpty()!=(afterVersion==0))throw new ApiException(400,"INVALID_PAGE","请重新读取产物目录");String zone=(String)taskRow.get("zone");
  var rows=new ArrayList<>(db.queryForList("select l.artifact_id,l.version,a.name,v.sha256,v.byte_size,'INPUT_ATTACHMENT' as source from task_artifacts l join artifacts a on a.id=l.artifact_id and a.tenant_id=l.tenant_id join artifact_versions v on v.artifact_id=l.artifact_id and v.version=l.version where l.task_id=? and l.tenant_id=? and a.zone=? and (l.artifact_id>? or (l.artifact_id=? and l.version>?)) order by l.artifact_id,l.version limit 101",task,actor.tenant(),zone,after,after,afterVersion));
  rows.addAll(outputMetadata(actor,task,zone,outputReferences(actor,task)).stream().filter(row->((String)row.get("artifact_id")).compareTo(after)>0||after.equals(row.get("artifact_id"))&&((Number)row.get("version")).intValue()>afterVersion).toList());
  var unique=new LinkedHashMap<String,Map<String,Object>>();for(var row:rows)unique.put(row.get("artifact_id")+":"+row.get("version"),row);rows=new ArrayList<>(unique.values());rows.sort(Comparator.<Map<String,Object>,String>comparing(row->(String)row.get("artifact_id")).thenComparingInt(row->((Number)row.get("version")).intValue()));boolean more=rows.size()>100;if(more)rows=new ArrayList<>(rows.subList(0,100));
  auth.auditRead(actor,"MANAGEMENT_TASK_ARTIFACTS_LISTED",Map.of("taskId",task));return Map.of("items",rows,"hasMore",more,"nextArtifact",more?rows.get(rows.size()-1).get("artifact_id"):"","nextVersion",more?rows.get(rows.size()-1).get("version"):0,"coverage","TASK_ATTACHMENTS_AND_VERIFIED_MODEL_OUTPUTS","businessVerified",false);
 }
 @Transactional public Object artifact(Identity actor,String task,String id,int version){
  var row=allowed(actor,task,"readArtifacts");if(version<1||id.length()>64)throw ApiException.missing();
  boolean attached=db.queryForObject("select count(*) from task_artifacts where task_id=? and tenant_id=? and artifact_id=? and version=?",Integer.class,task,actor.tenant(),id,version)>0;
  String digest=version==1?outputReferences(actor,task).get(id):null;if(!attached&&digest==null)throw ApiException.missing();
  var bytes=db.queryForList("select v.content_base64,v.sha256,v.byte_size from artifacts a join artifact_versions v on v.artifact_id=a.id join tasks t on t.id=? and t.tenant_id=a.tenant_id and t.owner_id=a.owner_id where a.id=? and a.tenant_id=? and a.zone=? and v.version=?",task,id,actor.tenant(),row.get("zone"),version);if(bytes.isEmpty()||!attached&&!Objects.equals(digest,bytes.get(0).get("sha256")))throw ApiException.missing();
  auth.auditRead(actor,"MANAGEMENT_TASK_ARTIFACT_READ",Map.of("taskId",task,"artifactId",id,"version",version));var result=new LinkedHashMap<>(bytes.get(0));result.put("reviewState","UNREVIEWED");result.put("businessVerified",false);return result;
 }
}
