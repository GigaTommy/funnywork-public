package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only audit projection. Raw payloads and task contents never leave this boundary. */
@Service
public class ManagementAudit {
 private final JdbcTemplate db;private final AuthService auth;private final Json json;private final Clock clock;
 private static final Set<String> IDS=Set.of("datasetId","projectId","poolId","repositoryId","principalId","userId","taskId","deviceId","nodeId","caseId","attemptId","toolVersionId","gatewayId","agentId","skillId","profileId","operationId","sourceId","evidenceId","artifactId","continuation","nextContinuation","deploymentId","ticketId");
 private static final Set<String> NUMBERS=Set.of("version","revision","restoredFrom","checkedAt","count","units","planVersion","addedUnits","epoch","httpStatus","latencyMs","afterSeq","throughSeq");
 public ManagementAudit(JdbcTemplate db,AuthService auth,Json json,Clock clock){this.db=db;this.auth=auth;this.json=json;this.clock=clock;}
 private boolean fullScope(Identity actor){
  for(String zones:db.queryForList("select zones from principals where tenant_id=?",String.class,actor.tenant()))try{if(!actor.zones().containsAll(json.strings(zones)))return false;}catch(RuntimeException invalid){return false;}
  for(String zone:db.queryForList("select distinct zone from tasks where tenant_id=? union select distinct zone from nodes where tenant_id=?",String.class,actor.tenant(),actor.tenant()))if(!actor.zones().contains(zone))return false;
  return true;
 }
 private Map<String,Object> metadata(String raw){
  var safe=new LinkedHashMap<String,Object>();Object parsed;try{parsed=json.read(raw);}catch(RuntimeException invalid){return safe;}if(!(parsed instanceof Map<?,?> payload))return safe;
  payload.forEach((key,value)->{
   if(!(key instanceof String name))return;
   if(IDS.contains(name)&&value instanceof String id&&id.matches("[A-Za-z0-9._:-]{1,120}"))safe.put(name,id);
   else if(NUMBERS.contains(name)&&value instanceof Number number)safe.put(name,number);
   else if(Set.of("enabled","configurationReady","atCreation").contains(name)&&value instanceof Boolean)safe.put(name,value);
   else if(Set.of("outcome","state","type","failureCode").contains(name)&&value instanceof String text&&text.matches("[A-Z][A-Z0-9_]{0,63}"))safe.put(name,text);
  });return safe;
 }
 @Transactional(readOnly=true)
 public Map<String,Object> page(Identity actor,long from,long until,long after,int limit,String type,String reference){
  actor=auth.current(actor);actor.requireUser("ADMIN");
  if(from<0||until<=from||until-from>93L*86400000||after<0||limit<1||limit>100||!type.matches("[A-Z_]{0,80}")||!reference.matches("[A-Za-z0-9._:-]{0,120}"))throw new ApiException(400,"INVALID_AUDIT_FILTER","Invalid audit filter");
  boolean full=fullScope(actor);var params=new ArrayList<Object>(List.of(actor.tenant(),from,until));String filter="";if(after>0){filter=" and e.id<?";params.add(after);}
  if(!type.isEmpty()){filter+=" and e.event_type=?";params.add(type);}int scan=limit*2;params.add(scan+1);
  var raw=db.queryForList("select e.id,e.task_id,e.actor_id,e.event_type,e.payload_json,e.created_at,e.audit_zones,t.zone as task_zone,p.display_name as actor_name from events e left join tasks t on t.id=e.task_id and t.tenant_id=e.tenant_id left join principals p on p.id=e.actor_id and p.tenant_id=e.tenant_id where e.tenant_id=? and e.created_at>=? and e.created_at<?"+filter+" order by e.id desc limit ?",params.toArray());
  boolean more=raw.size()>scan;if(more)raw=raw.subList(0,scan);var items=new ArrayList<Map<String,Object>>();long scanned=after;
  for(var row:raw){
   scanned=((Number)row.get("id")).longValue();boolean visible=false;
   if(row.get("task_id")!=null)visible=row.get("task_zone")!=null&&actor.zones().contains(row.get("task_zone"));
   else if(row.get("audit_zones")!=null)try{var scope=json.strings((String)row.get("audit_zones"));visible=scope.isEmpty()?full:actor.zones().containsAll(scope);}catch(RuntimeException ignored){visible=false;}
   else visible=full;
   if(!visible)continue;var details=metadata((String)row.get("payload_json"));
   if(row.get("task_id")!=null)details.put("taskId",row.get("task_id"));
   if(!reference.isEmpty()&&!reference.equals(row.get("actor_id"))&&details.entrySet().stream().noneMatch(entry->IDS.contains(entry.getKey())&&reference.equals(entry.getValue())))continue;
   var item=new LinkedHashMap<String,Object>();for(String field:List.of("id","actor_id","actor_name","event_type","created_at"))item.put(field,row.get(field));item.put("details",details);items.add(item);
   if(items.size()==limit){more=more||row!=raw.get(raw.size()-1);break;}
  }
  var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("observedAt",clock.millis());result.put("legacyTenantAuditVisible",full);result.put("scannedThrough",scanned);if(more)result.put("nextCursor",scanned);return result;
 }
}
