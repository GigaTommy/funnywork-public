package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Commands are claimed once by the exact source runtime; uncertain outcomes are never replayed. */
@Service
public class DesktopInteractions {
 @org.springframework.beans.factory.annotation.Autowired private MaintenanceDesktopChannel maintenance;
 @org.springframework.beans.factory.annotation.Autowired private MaintenanceSyncChecks syncChecks;
 public record State(String epoch,JsonNode sessions,JsonNode workspaces,Boolean attachmentUploads,Boolean sessionForks,JsonNode choices) {
  public State(String epoch,JsonNode sessions,JsonNode workspaces,Boolean attachmentUploads,Boolean sessionForks){this(epoch,sessions,workspaces,attachmentUploads,sessionForks,null);}
  public State(String epoch,JsonNode sessions,JsonNode workspaces,Boolean attachmentUploads){this(epoch,sessions,workspaces,attachmentUploads,false,null);}
  public State(String epoch,JsonNode sessions){this(epoch,sessions,null,false);}
  public State(String epoch,JsonNode sessions,JsonNode workspaces){this(epoch,sessions,workspaces,false);}
 }
 public record Command(String id,String epoch,String kind,JsonNode payload) {}
 public record Result(String epoch,String status,JsonNode result) {}
 private final JdbcTemplate db;private final AuthService auth;private final ObjectMapper json;private final Clock clock;private final DesktopAttachments attachments;private final DesktopNotifications notifications;private final DesktopPolicies policies;
 public DesktopInteractions(JdbcTemplate db,AuthService auth,ObjectMapper json,Clock clock,DesktopAttachments attachments,DesktopNotifications notifications,DesktopPolicies policies){this.db=db;this.auth=auth;this.json=json;this.clock=clock;this.attachments=attachments;this.notifications=notifications;this.policies=policies;}
 private Identity actor(Identity value){value=auth.current(value);if(!"USER".equals(value.kind()))throw ApiException.forbidden();return value;}
 private static void valid(boolean value){if(!value)throw new ApiException(400,"INVALID_SYNC_COMMAND","Invalid remote command");}
 private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,64}");}
 private String encode(JsonNode value){return value.toString();}
 private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception error){throw new IllegalStateException("Invalid stored interaction",error);}}
 private Map<String,Object> device(Identity actor,String id){
  // Match registration/history/revocation: owner before device, including implicit FK locks on writes.
  db.queryForObject("select id from principals where id=? and tenant_id=? for update",String.class,actor.id(),actor.tenant());
  actor=actor(actor); // Authorization may have changed while waiting for the owner lock.
  var rows=db.queryForList("select * from sync_devices where id=? and tenant_id=? and principal_id=? and revoked=false for update",id,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
 }
 private Map<String,Object> session(Identity actor,String id){var rows=db.queryForList("select * from sync_sessions where id=? and tenant_id=? and principal_id=?",id,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
 private void validChoices(JsonNode value) {
  valid(value.isObject()&&value.size()>=2&&value.size()<=7&&value.path("models").isArray()&&value.path("models").size()<=1024&&value.path("presets").isArray()&&value.path("presets").size()<=1024);
  value.fieldNames().forEachRemaining(key->valid(Set.of("models","presets","workspaceOperations","maintenance","annualReport","maintenanceExecutors","clientBuild").contains(key)));
  if(value.has("clientBuild")){
   var build=value.path("clientBuild");
   valid(build.isObject()&&build.size()==4&&build.path("version").isTextual()&&build.path("version").asText().matches("[0-9]+[.][0-9]+[.][0-9]+(?:[-+][A-Za-z0-9.-]+)?")&&build.path("version").asText().length()<=80
    &&build.path("commit").isTextual()&&build.path("commit").asText().matches("[a-f0-9]{40}")&&build.path("tree").isTextual()&&build.path("tree").asText().matches("[a-f0-9]{40}")&&build.path("dirty").isBoolean());
  }
  if(value.has("maintenanceExecutors"))MaintenanceExecutorProtocol.validate(value.path("maintenanceExecutors"));
  if(value.has("workspaceOperations"))valid(value.path("workspaceOperations").isBoolean());
  if(value.has("annualReport"))valid(value.path("annualReport").isTextual()&&Set.of("annual-report.v1", "").contains(value.path("annualReport").asText()));
  if(value.has("maintenance")){var nativeCapability=value.path("maintenance");valid(nativeCapability.isObject()&&(nativeCapability.size()>=2&&nativeCapability.size()<=4&&(!nativeCapability.has("triage")||nativeCapability.path("triage").isBoolean())&&(!nativeCapability.has("delivery")||nativeCapability.path("delivery").isBoolean()))&&nativeCapability.path("executor").asText().equals("funnywork-native-v1")&&nativeCapability.path("gitVersion").isTextual()&&!nativeCapability.path("gitVersion").asText().isBlank()&&nativeCapability.path("gitVersion").asText().length()<=120);nativeCapability.fieldNames().forEachRemaining(key->valid(Set.of("executor","gitVersion","triage","delivery").contains(key)));}
  valid(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1048576);
  Set<String> models=new HashSet<>(),presets=new HashSet<>();
  for(var model:value.path("models"))valid(model.isObject()&&model.size()==3&&choiceText(model.path("provider"))&&choiceText(model.path("model"))&&choiceText(model.path("name"))&&models.add(model.path("provider").asText()+"\n"+model.path("model").asText()));
  for(var preset:value.path("presets"))valid(preset.isObject()&&preset.size()==2&&choiceText(preset.path("id"))&&choiceText(preset.path("name"))&&presets.add(preset.path("id").asText()));
 }
 private boolean choiceText(JsonNode value){return value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=256&&!value.asText().contains("\n")&&!value.asText().contains("\r");}
 @Transactional public void publish(Identity actor,String deviceId,State state){
  actor=actor(actor);valid(state!=null&&id(state.epoch())&&state.sessions()!=null&&state.sessions().isObject()&&state.sessions().size()<=10000&&encode(state.sessions()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=900000);
  for(var item:state.sessions()){valid(item.isObject()&&item.path("seq").isIntegralNumber()&&item.path("seq").canConvertToLong()&&item.path("seq").asLong()>=0&&item.path("seq").asLong()<=9007199254740991L&&item.path("running").isBoolean());if(item.has("admissionWaiting"))valid(item.path("admissionWaiting").isIntegralNumber()&&item.path("admissionWaiting").canConvertToInt()&&item.path("admissionWaiting").asInt()>=0&&item.path("admissionWaiting").asInt()<=10000);}
  JsonNode workspaces=state.workspaces()==null?json.createArrayNode():state.workspaces();
  valid(workspaces.isArray()&&workspaces.size()<=1000&&encode(workspaces).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=200000);
  Set<String> workspaceIds=new HashSet<>();
  for(var workspace:workspaces)valid(workspace.isObject()&&workspace.size()==2&&id(workspace.path("id").asText())&&workspace.path("title").isTextual()&&!workspace.path("title").asText().isBlank()&&workspace.path("title").asText().length()<=200&&workspaceIds.add(workspace.path("id").asText()));
  JsonNode choices=state.choices()==null?json.createObjectNode().set("models",json.createArrayNode()):state.choices();
  if(state.choices()==null)((com.fasterxml.jackson.databind.node.ObjectNode)choices).set("presets",json.createArrayNode());
  validChoices(choices);
  var prior=device(actor,deviceId);
  if(!state.epoch().equals(prior.get("runtime_epoch"))){
   db.update("update sync_commands set status=case when status='claimed' then 'uncertain' else 'expired' end where device_id=? and status in ('queued','claimed')",deviceId);
  }
  notifications.observeState(actor,deviceId,state.epoch(),state.sessions(),prior);
  db.update("update sync_devices set runtime_epoch=?,state_json=?,workspaces_json=?,model_choices_json=?,attachment_uploads=?,session_forks=?,last_seen=?,state_seen=? where id=?",state.epoch(),encode(state.sessions()),encode(workspaces),encode(choices),Boolean.TRUE.equals(state.attachmentUploads()),Boolean.TRUE.equals(state.sessionForks()),clock.millis(),clock.millis(),deviceId);
 }
 @Transactional public void heartbeat(Identity actor,String deviceId,State state){policies.lock(actor);publish(actor,deviceId,state);}
 @Transactional public List<Map<String,Object>> exchange(Identity actor,String deviceId,State state){policies.lock(actor);publish(actor,deviceId,state);var commands=new ArrayList<>(claim(actor,deviceId,state.epoch()));commands.addAll(maintenance.dispatch(actor,deviceId,state));commands.addAll(syncChecks.dispatch(actor,deviceId,state));return commands;}
 public Map<String,Object> state(Identity actor,String sessionId){
  actor=actor(actor);var row=session(actor,sessionId);var device=db.queryForMap("select * from sync_devices where id=?",row.get("device_id"));
  boolean online=!Boolean.TRUE.equals(device.get("revoked"))&&clock.millis()-((Number)device.get("state_seen")).longValue()<=30000&&device.get("runtime_epoch")!=null;
  JsonNode snapshot=device.get("state_json")==null?json.createObjectNode():decode((String)device.get("state_json")).path((String)row.get("native_id"));
  return Map.of("online",online,"epoch",Objects.toString(device.get("runtime_epoch"),""),"state",snapshot,"attachmentUploads",online&&Boolean.TRUE.equals(device.get("attachment_uploads")),"sessionForks",online&&Boolean.TRUE.equals(device.get("session_forks")));
 }
 /** Bounded, read-only scan; opening a result rechecks its current state before acting. */
 public Map<String,Object> pending(Identity actor,String after) {
  actor=actor(actor);valid(after!=null&&(after.isEmpty()||id(after)));
  var rows=db.queryForList("select s.id,s.title,s.title_revision,s.archived,s.archive_revision,s.device_id,d.name as device_name,s.native_id,s.updated_at from sync_sessions s join sync_devices d on d.id=s.device_id where s.tenant_id=? and s.principal_id=? and d.tenant_id=? and d.principal_id=? and s.id>? and d.revoked=false and d.runtime_epoch is not null and d.state_seen>=? order by s.id limit 33",actor.tenant(),actor.id(),actor.tenant(),actor.id(),after,clock.millis()-30000);
  Map<String,JsonNode> snapshots=new HashMap<>();List<Map<String,Object>> results=new ArrayList<>();String cursor=after;
  for(var row:rows.subList(0,Math.min(32,rows.size()))) {
   cursor=(String)row.get("id");String deviceId=(String)row.get("device_id");
   if(!snapshots.containsKey(deviceId)) {
    var devices=db.queryForList("select state_json from sync_devices where id=? and tenant_id=? and principal_id=? and revoked=false and runtime_epoch is not null and state_seen>=?",deviceId,actor.tenant(),actor.id(),clock.millis()-30000);
    snapshots.put(deviceId,devices.isEmpty()||devices.get(0).get("state_json")==null?json.createObjectNode():decode((String)devices.get(0).get("state_json")));
   }
   var state=snapshots.get(deviceId).path((String)row.get("native_id"));
   if(state.path("controllable").isBoolean()&&!state.path("controllable").asBoolean())continue;
   Set<String> approvals=new HashSet<>(),questions=new HashSet<>();
   for(var item:state.path("pending")) {
    if(!item.path("eventId").isTextual()||item.path("eventId").asText().isEmpty()||!item.path("request").isObject())continue;
    if("approval/request".equals(item.path("event").asText()))approvals.add(item.path("eventId").asText());
    if("user-questions/request".equals(item.path("event").asText()))questions.add("event/"+item.path("eventId").asText());
   }
   for(var item:state.path("projections").path("userQuestions").path("active"))
    if("continued".equals(item.path("state").asText())&&item.path("callId").isTextual()&&!item.path("callId").asText().isEmpty())questions.add("call/"+item.path("callId").asText());
   if(approvals.isEmpty()&&questions.isEmpty())continue;
   row.remove("native_id");row.put("online",true);
   results.add(Map.of("conversation",row,"approvals",approvals.size(),"questions",questions.size()));
  }
  return Map.of("items",results,"after",cursor,"hasMore",rows.size()>32);
 }
 @Transactional public Map<String,Object> enqueue(Identity actor,String sessionId,Command command){
  policies.lock(actor);policies.requireControl(actor);
  actor=actor(actor);valid(command!=null&&id(command.id())&&id(command.epoch())&&Set.of("prompt","cancel","answer","decision","fork","queueEdit").contains(command.kind())&&command.payload()!=null&&command.payload().isObject()&&encode(command.payload()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
  db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
  var row=session(actor,sessionId);var source=device(actor,(String)row.get("device_id"));
  var existing=db.queryForList("select * from sync_commands where id=?",command.id());
  if(!existing.isEmpty()){
   var old=existing.get(0);if(!actor.id().equals(old.get("principal_id"))||!actor.tenant().equals(old.get("tenant_id")))throw ApiException.missing();
   if(!sessionId.equals(old.get("session_id"))||!command.epoch().equals(old.get("epoch"))||!command.kind().equals(old.get("kind"))||!decode((String)old.get("payload_json")).equals(decode(encode(command.payload()))))throw ApiException.conflict("SYNC_COMMAND_CONFLICT");
   return view(old);
  }
  if(!command.epoch().equals(source.get("runtime_epoch"))||clock.millis()-((Number)source.get("state_seen")).longValue()>30000)throw ApiException.conflict("SYNC_DEVICE_OFFLINE");
  if("fork".equals(command.kind())) {
   valid(command.payload().size()==1&&command.payload().path("atSeq").isIntegralNumber()&&command.payload().path("atSeq").canConvertToLong()&&command.payload().path("atSeq").asLong()>=0&&command.payload().path("atSeq").asLong()<=9007199254740991L);
   if(!Boolean.TRUE.equals(source.get("session_forks")))throw ApiException.conflict("SYNC_FORK_UNSUPPORTED");
   JsonNode nativeState=decode((String)source.get("state_json")).path((String)row.get("native_id"));
   if(!nativeState.path("controllable").isBoolean()||!nativeState.path("controllable").asBoolean())throw ApiException.conflict("SYNC_FORK_UNAVAILABLE");
   var events=db.queryForList("select event_json from sync_session_events where session_id=? and seq=?",sessionId,command.payload().path("atSeq").asLong());
   valid(events.size()==1&&Set.of("user/message","assistant/message","assistant/attempt").contains(decode((String)events.get(0).get("event_json")).path("type").asText()));
  }
  if("queueEdit".equals(command.kind())) {
   valid(command.payload().path("itemId").isTextual()&&command.payload().path("itemId").asText().length()>0&&command.payload().path("itemId").asText().length()<=256&&Set.of("edit","remove","steer").contains(command.payload().path("action").asText()));
   if("edit".equals(command.payload().path("action").asText()))valid(command.payload().path("text").isTextual()&&!command.payload().path("text").asText().isBlank()&&command.payload().path("text").asText().length()<=16000);
  }
  if("prompt".equals(command.kind()))valid(command.payload().path("text").isTextual()&&(!command.payload().path("text").asText().isBlank()||command.payload().path("attachments").isArray()&&command.payload().path("attachments").size()>0)&&Set.of("queue","steer").contains(command.payload().path("mode").asText()));
  if(command.payload().has("attachments")){
   valid("prompt".equals(command.kind()));
   if(!Boolean.TRUE.equals(source.get("attachment_uploads")))throw ApiException.conflict("SYNC_ATTACHMENTS_UNSUPPORTED");
   attachments.retainMobileForCommand(actor,sessionId,command.payload().path("attachments"));
  }
  if("cancel".equals(command.kind())||("prompt".equals(command.kind())&&"steer".equals(command.payload().path("mode").asText())))valid(command.payload().path("expectedTurn").isIntegralNumber()&&command.payload().path("expectedTurn").canConvertToLong()&&command.payload().path("expectedTurn").asLong()>=0&&command.payload().path("expectedTurn").asLong()<=9007199254740991L);
  if("answer".equals(command.kind()))valid(command.payload().path("callId").isTextual()&&command.payload().path("callId").asText().length()<=128&&command.payload().path("answer").path("answers").isArray());
  if("decision".equals(command.kind())){
   valid(id(command.payload().path("eventId").asText()));
   JsonNode nativeState=decode((String)source.get("state_json")).path((String)row.get("native_id"));
   JsonNode pending=null;for(var item:nativeState.path("pending"))if(item.path("eventId").asText().equals(command.payload().path("eventId").asText())){pending=item;break;}
   if(pending==null)throw ApiException.conflict("SYNC_DECISION_EXPIRED");
   if(pending.path("event").asText().equals("approval/request"))valid(Set.of("allowed-once","rejected").contains(command.payload().path("outcome").asText()));
   else valid(pending.path("event").asText().equals("user-questions/request")&&command.payload().path("answer").path("answers").isArray());
  }
  db.update("insert into sync_commands(id,session_id,principal_id,tenant_id,device_id,epoch,kind,payload_json,created_at,expires_at) values(?,?,?,?,?,?,?,?,?,?)",command.id(),sessionId,actor.id(),actor.tenant(),row.get("device_id"),command.epoch(),command.kind(),encode(command.payload()),clock.millis(),clock.millis()+30000);
  return Map.of("id",command.id(),"status","queued");
 }
 public Map<String,Object> sessionCommand(Identity actor,String sessionId,String commandId) {
  actor=actor(actor);valid(id(commandId));session(actor,sessionId);expire("session_id",sessionId);
  var rows=db.queryForList("select * from sync_commands where id=? and session_id=? and tenant_id=? and principal_id=?",commandId,sessionId,actor.tenant(),actor.id());
  if(rows.isEmpty())throw ApiException.missing();return view(rows.get(0));
 }
 private Map<String,Object> view(Map<String,Object> row){return Map.of("id",row.get("id"),"kind",row.get("kind"),"status",row.get("status"),"createdAt",row.get("created_at"),"result",row.get("result_json")==null?json.createObjectNode():decode((String)row.get("result_json")));}
 @Transactional public List<Map<String,Object>> commands(Identity actor,String sessionId){actor=actor(actor);session(actor,sessionId);expire("session_id",sessionId);return db.queryForList("select * from sync_commands where session_id=? order by created_at desc,id desc limit 100",sessionId).stream().map(this::view).toList();}
 private void expire(String column,String id){db.update("update sync_commands set status=case when status='claimed' then 'uncertain' else 'expired' end where "+column+"=? and status in ('queued','claimed') and expires_at<?",id,clock.millis());}
 @Transactional public List<Map<String,Object>> claim(Identity actor,String deviceId,String epoch){
  policies.lock(actor);
  actor=actor(actor);var source=device(actor,deviceId);if(!epoch.equals(source.get("runtime_epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");expire("device_id",deviceId);
  if(!policies.canControl(actor))return List.of();
  var rows=db.queryForList("select * from sync_commands where device_id=? and epoch=? and status='queued' order by created_at,id limit 20 for update",deviceId,epoch);
  List<Map<String,Object>> deliveries=new ArrayList<>();for(var row:rows){db.update("update sync_commands set status='claimed' where id=?",row.get("id"));deliveries.add(Map.of("id",row.get("id"),"sessionId",Objects.toString(row.get("session_id"),""),"nativeId",(row.get("session_id")==null?"":db.queryForObject("select native_id from sync_sessions where id=?",String.class,row.get("session_id"))),"kind",row.get("kind"),"payload",decode((String)row.get("payload_json"))));}return deliveries;
 }
 @Transactional public void acknowledge(Identity actor,String deviceId,String commandId,Result result){
  actor=actor(actor);valid(result!=null&&id(result.epoch())&&Set.of("accepted","rejected","uncertain").contains(result.status())&&result.result()!=null&&encode(result.result()).length()<=400000);var source=device(actor,deviceId);if(!result.epoch().equals(source.get("runtime_epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");
  var rows=db.queryForList("select * from sync_commands where id=? and device_id=? for update",commandId,deviceId);if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
  valid(encode(result.result()).length()<=("workspace".equals(row.get("kind"))?400000:4096));
  if(!result.epoch().equals(row.get("epoch")))throw ApiException.conflict("SYNC_RUNTIME_CHANGED");
  if(!Set.of("claimed","uncertain").contains((String)row.get("status"))){if(result.status().equals(row.get("status"))&&decode((String)row.get("result_json")).equals(result.result()))return;throw ApiException.conflict("SYNC_COMMAND_SETTLED");}
  if("create".equals(row.get("kind"))&&"accepted".equals(result.status())) {
   String created=result.result().path("sessionId").asText();
   valid(id(created)&&db.queryForObject("select count(*) from sync_sessions where id=? and device_id=? and tenant_id=? and principal_id=? and native_id=?",Integer.class,created,deviceId,actor.tenant(),actor.id(),"mobile-"+commandId)==1);
  }
  if("fork".equals(row.get("kind"))&&"accepted".equals(result.status())) {
   valid(result.result().size()==1&&result.result().path("sessionId").isTextual());
   String created=result.result().path("sessionId").asText();
   var children=db.queryForList("select * from sync_sessions where id=? and device_id=? and tenant_id=? and principal_id=? and native_id=?",created,deviceId,actor.tenant(),actor.id(),"mobile-"+commandId);
   valid(id(created)&&children.size()==1);
   var child=children.get(0);var header=decode((String)child.get("header_json"));
   String parent=db.queryForObject("select native_id from sync_sessions where id=?",String.class,row.get("session_id"));
   valid(header.path("isSeeded").asBoolean(false)&&!"subagent".equals(header.path("origin").asText())&&parent.equals(header.path("parentSession").asText())&&((Number)child.get("inherited_event_count")).longValue()==decode((String)row.get("payload_json")).path("atSeq").asLong()+1);
   long cut=decode((String)row.get("payload_json")).path("atSeq").asLong();
   valid(((Number)child.get("next_seq")).longValue()>cut);
   var copied=db.queryForList("select c.event_json as child_event,p.event_json as parent_event from sync_session_events c join sync_session_events p on p.session_id=? and p.seq=c.seq where c.session_id=? and c.seq=?",row.get("session_id"),created,cut);
   valid(copied.size()==1&&decode((String)copied.get(0).get("child_event")).equals(decode((String)copied.get(0).get("parent_event"))));
  }
  db.update("update sync_commands set status=?,result_json=? where id=?",result.status(),encode(result.result()),commandId);
 }
 /** Only published Workspace identities are selectable; no remote filesystem paths are accepted. */
 public List<Map<String,Object>> devices(Identity actor,String after) {
  actor=actor(actor);valid(after!=null&&(after.isEmpty()||id(after)));
  var rows=db.queryForList("select * from sync_devices where tenant_id=? and principal_id=? and id>? order by id limit 100",actor.tenant(),actor.id(),after);
  return rows.stream().map(row->{
   boolean online=!Boolean.TRUE.equals(row.get("revoked"))&&row.get("runtime_epoch")!=null&&clock.millis()-((Number)row.get("state_seen")).longValue()<=30000;
   Map<String,Object> result=new LinkedHashMap<String,Object>(Map.<String,Object>of("id",row.get("id"),"name",row.get("name"),"platform",row.get("platform"),"lastSeen",row.get("last_seen"),"revoked",Boolean.TRUE.equals(row.get("revoked")),"accessRevision",row.get("access_revision"),"online",online,"epoch",Objects.toString(row.get("runtime_epoch"),""),"workspaces",online&&row.get("workspaces_json")!=null?decode((String)row.get("workspaces_json")):json.createArrayNode()));
   result.put("choices",online&&row.get("model_choices_json")!=null?decode((String)row.get("model_choices_json")):json.createObjectNode());return result;
  }).toList();
 }
 private boolean validCreationPayload(JsonNode payload){
  var names=payload.fieldNames();while(names.hasNext())if(!Set.of("workspaceId","model","agentPreset").contains(names.next()))return false;
  if(payload.has("agentPreset")&&!choiceText(payload.path("agentPreset")))return false;
  if(payload.has("model")){var model=payload.path("model");if(!model.isObject()||model.size()!=2||!choiceText(model.path("provider"))||!choiceText(model.path("model")))return false;}
  return true;
 }
 @Transactional public Map<String,Object> workspaceCommand(Identity actor,String deviceId,Command command) {
  policies.lock(actor);policies.requireControl(actor);actor=actor(actor);
  valid(command!=null&&id(command.id())&&id(command.epoch())&&"workspace".equals(command.kind())&&command.payload()!=null&&command.payload().isObject()&&encode(command.payload()).length()<=400000);
  var p=command.payload();String action=p.path("action").asText();
  valid(Set.of("create","rename","delete","files","read","write","mkdir","git","branchCreate","branchSwitch","branchDelete","worktreeAdd","worktreeRemove").contains(action));
  if(!"create".equals(action))valid(id(p.path("workspaceId").asText()));
  if(Set.of("create","read","write","mkdir","worktreeRemove").contains(action))valid(p.path("path").isTextual()&&!p.path("path").asText().isBlank()&&p.path("path").asText().length()<=4096);
  if(p.has("newBranch"))valid("worktreeAdd".equals(action)&&p.path("newBranch").isBoolean());
  if("rename".equals(action))valid(p.path("title").isTextual()&&!p.path("title").asText().isBlank()&&p.path("title").asText().length()<=200);
  if("write".equals(action))valid(p.path("text").isTextual()&&p.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=64000&&p.path("revision").isTextual()&&p.path("revision").asText().matches("new|[a-f0-9]{64}"));
  if(Set.of("branchCreate","branchSwitch","branchDelete","worktreeAdd").contains(action))valid(p.path("branch").isTextual()&&p.path("branch").asText().matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}"));
  var source=device(actor,deviceId);
  var existing=db.queryForList("select * from sync_commands where id=?",command.id());
  if(!existing.isEmpty()) {var old=existing.get(0);if(!actor.id().equals(old.get("principal_id"))||!actor.tenant().equals(old.get("tenant_id")))throw ApiException.missing();if(!deviceId.equals(old.get("device_id"))||!command.epoch().equals(old.get("epoch"))||!"workspace".equals(old.get("kind"))||!decode((String)old.get("payload_json")).equals(p))throw ApiException.conflict("SYNC_COMMAND_CONFLICT");expire("device_id",deviceId);return view(db.queryForMap("select * from sync_commands where id=?",command.id()));}
  if(!command.epoch().equals(source.get("runtime_epoch"))||clock.millis()-((Number)source.get("state_seen")).longValue()>30000)throw ApiException.conflict("SYNC_DEVICE_OFFLINE");
  valid(source.get("model_choices_json")!=null&&decode((String)source.get("model_choices_json")).path("workspaceOperations").asBoolean(false));
  if(!"create".equals(action)){boolean found=false;if(source.get("workspaces_json")!=null)for(var row:decode((String)source.get("workspaces_json")))if(row.path("id").equals(p.path("workspaceId")))found=true;if(!found)throw ApiException.conflict("SYNC_WORKSPACE_UNAVAILABLE");}
  db.update("insert into sync_commands(id,session_id,principal_id,tenant_id,device_id,epoch,kind,payload_json,created_at,expires_at) values(?,null,?,?,?,?,?,?,?,?)",command.id(),actor.id(),actor.tenant(),deviceId,command.epoch(),"workspace",encode(p),clock.millis(),clock.millis()+30000);
  return Map.of("id",command.id(),"status","queued");
 }
 @Transactional public Map<String,Object> createSession(Identity actor,String deviceId,Command command) {
  policies.lock(actor);policies.requireControl(actor);
  actor=actor(actor);
  valid(command!=null&&id(command.id())&&id(command.epoch())&&"create".equals(command.kind())&&command.payload()!=null&&command.payload().isObject()&&command.payload().size()>=1&&command.payload().size()<=3&&id(command.payload().path("workspaceId").asText())&&validCreationPayload(command.payload()));
  var source=device(actor,deviceId);
  var existing=db.queryForList("select * from sync_commands where id=?",command.id());
  if(!existing.isEmpty()) {
   var old=existing.get(0);
   if(!actor.id().equals(old.get("principal_id"))||!actor.tenant().equals(old.get("tenant_id")))throw ApiException.missing();
   if(!deviceId.equals(old.get("device_id"))||!command.epoch().equals(old.get("epoch"))||!"create".equals(old.get("kind"))||!decode((String)old.get("payload_json")).equals(decode(encode(command.payload()))))throw ApiException.conflict("SYNC_COMMAND_CONFLICT");
   expire("device_id",deviceId);
   return view(db.queryForMap("select * from sync_commands where id=?",command.id()));
  }
  if(!command.epoch().equals(source.get("runtime_epoch"))||clock.millis()-((Number)source.get("state_seen")).longValue()>30000)throw ApiException.conflict("SYNC_DEVICE_OFFLINE");
  boolean found=false;
  if(source.get("workspaces_json")!=null)for(var workspace:decode((String)source.get("workspaces_json")))if(workspace.path("id").asText().equals(command.payload().path("workspaceId").asText()))found=true;
  if(!found)throw ApiException.conflict("SYNC_WORKSPACE_UNAVAILABLE");
  JsonNode choices=source.get("model_choices_json")==null?json.createObjectNode():decode((String)source.get("model_choices_json"));
  if(command.payload().has("agentPreset")){boolean allowed=false;for(var preset:choices.path("presets"))if(preset.path("id").equals(command.payload().path("agentPreset")))allowed=true;if(!allowed)throw ApiException.conflict("SYNC_PRESET_UNAVAILABLE");}
  if(command.payload().has("model")){boolean allowed=false;for(var model:choices.path("models"))if(model.path("provider").equals(command.payload().path("model").path("provider"))&&model.path("model").equals(command.payload().path("model").path("model")))allowed=true;if(!allowed)throw ApiException.conflict("SYNC_MODEL_UNAVAILABLE");}
  db.update("insert into sync_commands(id,session_id,principal_id,tenant_id,device_id,epoch,kind,payload_json,created_at,expires_at) values(?,null,?,?,?,?,?,?,?,?)",command.id(),actor.id(),actor.tenant(),deviceId,command.epoch(),"create",encode(command.payload()),clock.millis(),clock.millis()+30000);
  return Map.of("id",command.id(),"status","queued");
 }
 @Transactional public Map<String,Object> deviceCommand(Identity actor,String deviceId,String commandId) {
  actor=actor(actor);device(actor,deviceId);expire("device_id",deviceId);
  var rows=db.queryForList("select * from sync_commands where id=? and device_id=? and tenant_id=? and principal_id=? and kind in ('create','workspace')",commandId,deviceId,actor.tenant(),actor.id());
  if(rows.isEmpty())return Map.of("id",commandId,"status","missing");
  return view(rows.get(0));
 }

}
