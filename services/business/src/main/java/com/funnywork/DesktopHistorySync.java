package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Account-owned replicas. Reading these records never invokes a desktop Agent. */
@Service
public class DesktopHistorySync {
    public record Device(String id,String name,String platform,String expectedPrincipalId) {
        public Device(String id,String name,String platform){this(id,name,platform,null);}
    }
    public record Registration(String deviceId,JsonNode header,long inheritedEventCount) {}
    public record Batch(String deviceId,List<JsonNode> events) {}
    public record Title(String title,Long revision) {}
    public record Archive(Boolean archived,Long revision) {}
    public record DeviceAccess(Long revision) {}
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DesktopAttachments attachments;
    private final DesktopNotifications notifications;
    private DesktopResults results;
    @org.springframework.beans.factory.annotation.Autowired
    void setResults(DesktopResults results) { this.results=results; }
    public DesktopHistorySync(JdbcTemplate db,AuthService auth,ObjectMapper mapper,Clock clock,DesktopAttachments attachments,DesktopNotifications notifications) {
        this.db=db;this.auth=auth;this.mapper=mapper;this.clock=clock;this.attachments=attachments;this.notifications=notifications;
    }
    private Identity actor(Identity actor) {
        actor=auth.current(actor);
        if(!actor.kind().equals("USER"))throw ApiException.forbidden();
        return actor;
    }
    private Identity lockActor(Identity actor) {
        db.queryForObject("select id from principals where id=? and tenant_id=? for update",String.class,actor.id(),actor.tenant());
        return actor(actor); // Recheck enabled/auth_version after a concurrent authorization change.
    }
    private static void valid(boolean condition) {
        if(!condition)throw new ApiException(400,"INVALID_SYNC_RECORD","Invalid history synchronization record");
    }
    private static boolean id(String value) {return value!=null&&value.matches("[A-Za-z0-9_-]{1,128}");}
    private String encode(JsonNode value) {
        try{return mapper.writeValueAsString(value);}catch(Exception e){throw new ApiException(400,"INVALID_SYNC_RECORD","Invalid JSON record");}
    }
    private JsonNode decode(String value) {
        try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Stored sync record is invalid",e);}
    }
    private Map<String,Object> device(Identity actor,String id) {
        var rows=db.queryForList("select * from sync_devices where id=? and tenant_id=? and principal_id=? and revoked=false for update",id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    private Map<String,Object> session(Identity actor,String id,boolean lock) {
        // Write callers hold the owner lock before this joined device/session lock.
        var rows=db.queryForList("select s.*,d.name as device_name,d.platform from sync_sessions s join sync_devices d on d.id=s.device_id where s.id=? and s.tenant_id=? and s.principal_id=?"+(lock?" for update":""),id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    @Transactional public Map<String,Object> registerDevice(Identity actor,Device request) {
        actor=actor(actor);
        valid(request!=null&&request.id()!=null&&request.id().matches("[A-Za-z0-9_-]{1,64}")&&request.name()!=null&&request.name().length()<=120&&!request.name().isBlank()&&request.platform()!=null&&Set.of("darwin","win32","linux").contains(request.platform()));
        if(request.expectedPrincipalId()!=null&&!actor.id().equals(request.expectedPrincipalId()))throw ApiException.forbidden();
        // Principal lock serializes concurrent registrations without an unsafe ownership upsert.
        actor=lockActor(actor);
        var existing=db.queryForList("select id from sync_devices where id=?",request.id());
        if(existing.isEmpty())db.update("insert into sync_devices(id,tenant_id,principal_id,name,platform,created_at,last_seen) values(?,?,?,?,?,?,?)",request.id(),actor.tenant(),actor.id(),request.name(),request.platform(),clock.millis(),clock.millis());
        else {device(actor,request.id());db.update("update sync_devices set name=?,platform=?,last_seen=? where id=?",request.name(),request.platform(),clock.millis(),request.id());}
        return Map.of("deviceId",request.id(),"principalId",actor.id(),"tenantId",actor.tenant());
    }
    @Transactional public void revokeDevice(Identity actor,String id) { changeDeviceAccess(actor,id,null,true); }
    @Transactional public void restoreDevice(Identity actor,String id) { changeDeviceAccess(actor,id,null,false); }
    @Transactional public void revokeDevice(Identity actor,String id,DeviceAccess request) { changeDeviceAccess(actor,id,request,true); }
    @Transactional public void restoreDevice(Identity actor,String id,DeviceAccess request) { changeDeviceAccess(actor,id,request,false); }
    private void changeDeviceAccess(Identity actor,String id,DeviceAccess request,boolean revoked) {
        actor=actor(actor);
        actor=lockActor(actor);
        var rows=db.queryForList("select * from sync_devices where id=? and tenant_id=? and principal_id=? for update",id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();
        updateDeviceAccess(id,rows.get(0),request,revoked);
    }
    /** Caller holds the owner and device locks and has checked its own authorization. */
    void updateDeviceAccess(String id,Map<String,Object> row,DeviceAccess request,boolean revoked) {
        long revision=((Number)row.get("access_revision")).longValue();
        if(request!=null) {
            valid(request.revision()!=null&&request.revision()>=0&&request.revision()<=9007199254740991L);
            if(request.revision()!=revision)throw ApiException.conflict("SYNC_DEVICE_ACCESS_CHANGED");
        }
        if(Boolean.TRUE.equals(row.get("revoked"))==revoked)return;
        valid(revision<9007199254740991L);
        if(revoked) {
            db.update("update sync_devices set revoked=true,access_revision=access_revision+1 where id=?",id);
            db.update("update sync_commands set status=case when status='claimed' then 'uncertain' else 'expired' end where device_id=? and status in ('queued','claimed')",id);
        } else {
            db.update("update sync_devices set revoked=false,last_seen=0,runtime_epoch=null,state_seen=0,state_json=null,workspaces_json=null,model_choices_json=null,session_forks=false,access_revision=access_revision+1 where id=?",id);
        }
    }
    @Transactional public Map<String,Object> registerSession(Identity actor,Registration request) {
        actor=actor(actor);valid(request!=null&&request.header()!=null&&request.header().isObject());
        var header=request.header();String nativeId=header.path("id").asText();
        valid(id(nativeId)&&header.path("version").isIntegralNumber()&&header.path("version").canConvertToInt()&&header.path("version").asInt()==4&&header.path("createdAt").isIntegralNumber()&&header.path("createdAt").canConvertToLong()&&header.path("createdAt").asLong()>=0&&header.path("createdAt").asLong()<=9007199254740991L&&header.path("isSeeded").isBoolean()&&request.inheritedEventCount()>=0&&request.inheritedEventCount()<=9007199254740991L&& (header.path("isSeeded").asBoolean()||request.inheritedEventCount()==0));
        valid(encode(header).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
        actor=lockActor(actor);
        device(actor,request.deviceId());
        db.queryForObject("select id from sync_devices where id=? for update",String.class,request.deviceId());
        var rows=db.queryForList("select * from sync_sessions where device_id=? and native_id=?",request.deviceId(),nativeId);
        if(!rows.isEmpty()) {
            var row=rows.get(0);
            if(!decode((String)row.get("header_json")).equals(decode(encode(header)))||((Number)row.get("inherited_event_count")).longValue()!=request.inheritedEventCount())throw ApiException.conflict("SYNC_HEADER_CONFLICT");
            return receipt(row);
        }
        String id=AuthService.id();
        db.update("insert into sync_sessions(id,tenant_id,principal_id,device_id,native_id,header_json,inherited_event_count,updated_at) values(?,?,?,?,?,?,?,?)",id,actor.tenant(),actor.id(),request.deviceId(),nativeId,encode(header),request.inheritedEventCount(),clock.millis());
        return Map.of("id",id,"nextSeq",0L);
    }
    private Map<String,Object> receipt(Map<String,Object> row) {return Map.of("id",row.get("id"),"nextSeq",row.get("next_seq"));}
    @Transactional public Map<String,Object> append(Identity actor,String id,Batch request) {
        actor=actor(actor);valid(request!=null&&request.events()!=null&&!request.events().isEmpty()&&request.events().size()<=100);
        valid(request.events().get(0)!=null);
        valid(encode(mapper.valueToTree(request)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=900000);
        // Validate the complete request before using its endpoints as the replay range.
        long previous=-1;
        for(var event:request.events()) {
            valid(event!=null&&event.isObject()&&event.path("seq").isIntegralNumber()&&event.path("seq").canConvertToLong()&&event.path("seq").asLong()>=0&&event.path("seq").asLong()<9007199254740991L&&event.path("type").isTextual()&&!event.path("type").asText().isBlank()&&event.path("type").asText().length()<=128&&event.has("data")&&event.path("time").isIntegralNumber()&&event.path("time").canConvertToLong()&&event.path("time").asLong()>=0&&event.path("time").asLong()<=9007199254740991L);
            long seq=event.path("seq").asLong();valid(previous<0||seq==previous+1);previous=seq;
        }
        actor=lockActor(actor);
        device(actor,request.deviceId());var row=session(actor,id,true);
        if(!request.deviceId().equals(row.get("device_id")))throw ApiException.forbidden();
        long next=((Number)row.get("next_seq")).longValue();
        String title=(String)row.get("title");
        String preview=(String)row.get("preview_text");Object previewSeq=row.get("preview_seq");boolean previewAttachment=Boolean.TRUE.equals(row.get("preview_attachment"));
        // Read the replay prefix once; keep structural JSON equality (not wire-byte equality).
        var replay=new HashMap<Long,String>();
        for(var stored:db.queryForList("select seq,event_json from sync_session_events where session_id=? and seq>=? and seq<? order by seq limit 100",id,request.events().get(0).path("seq").asLong(),Math.min(next,previous+1)))
            replay.put(((Number)stored.get("seq")).longValue(),(String)stored.get("event_json"));
        var inserts=new ArrayList<Object[]>();
        var accepted=new ArrayList<JsonNode>();
        for(var event:request.events()) {
            long seq=event.path("seq").asLong();
            if(seq>next)throw ApiException.conflict("SYNC_SEQUENCE_GAP");
            String encoded=encode(event);
            if(seq<next) {
                var stored=replay.get(seq);
                if(stored==null||!decode(stored).equals(decode(encoded)))throw ApiException.conflict("SYNC_EVENT_CONFLICT");
            } else {
                inserts.add(new Object[]{id,seq,encoded,DesktopHistorySearch.visibleText(event),event.path("type").asText()});accepted.add(event);next++;
                var overview=ConversationOverview.preview(event);
                if(overview!=null){preview=overview.text();previewSeq=seq;previewAttachment=overview.attachment();}
                if(title.isEmpty()&&event.path("type").asText().equals("user/message")&&event.path("data").path("source").path("kind").asText().equals("user")) {
                    for(var part:event.path("data").path("content")) {
                        if(part.path("type").asText().equals("text")) {
                            String text=part.path("text").asText().strip();
                            if(!text.isEmpty()){title=text.substring(0,Math.min(160,text.length()));break;}
                        }
                    }
                }
            }
        }
        if(!inserts.isEmpty()) {
            db.batchUpdate("insert into sync_session_events(session_id,seq,event_json,visible_text,event_type,projection_version) values(?,?,?,?,?,2)",inserts);
            attachments.recordReferences(id,accepted);
            if(results!=null)results.record(id,accepted);
            for(var event:accepted)notifications.observeEvent(row,event);
        }
        db.update("update sync_sessions set next_seq=?,title=?,updated_at=?,preview_text=?,preview_seq=?,preview_attachment=? where id=?",next,title,clock.millis(),preview,previewSeq,previewAttachment,id);
        return Map.of("id",id,"nextSeq",next);
    }
    public List<Map<String,Object>> list(Identity actor,String after,int limit) {
        actor=actor(actor);valid(after!=null&&after.length()<=64&&limit>0&&limit<=100);
        var rows=db.queryForList("select s.id,s.title,s.title_revision,s.archived,s.archive_revision,s.preview_text,s.preview_seq,s.preview_attachment,s.native_id,s.device_id,d.name as device_name,d.platform,d.last_seen,d.revoked,s.next_seq,s.updated_at from sync_sessions s join sync_devices d on d.id=s.device_id where s.tenant_id=? and s.principal_id=? and s.id>? order by s.id limit ?",actor.tenant(),actor.id(),after,limit);
        enrich(actor,rows,false);
        return rows;
    }
    public Map<String,Object> conversation(Identity actor,String id) {
        actor=actor(actor);valid(id!=null&&id.matches("[A-Za-z0-9_-]{1,64}"));
        var rows=db.queryForList("select s.id,s.title,s.title_revision,s.archived,s.archive_revision,s.preview_text,s.preview_seq,s.preview_attachment,s.native_id,s.device_id,d.name as device_name,d.platform,d.last_seen,d.revoked,s.next_seq,s.updated_at from sync_sessions s join sync_devices d on d.id=s.device_id where s.id=? and s.tenant_id=? and s.principal_id=?",id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        enrich(actor,rows,true);return row;
    }
    private void enrich(Identity actor,List<Map<String,Object>> rows,boolean stripNativeId) {
        if(rows.isEmpty())return;
        var ids=rows.stream().map(row->(String)row.get("device_id")).distinct().toList();
        List<Object> parameters=new ArrayList<>();parameters.add(actor.tenant());parameters.add(actor.id());parameters.addAll(ids);
        var devices=db.queryForList("select id,state_json,state_seen,runtime_epoch,revoked from sync_devices where tenant_id=? and principal_id=? and id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",parameters.toArray());
        Map<String,JsonNode> snapshots=new HashMap<>();Map<String,Long> seen=new HashMap<>();long now=clock.millis();
        for(var device:devices)if(!Boolean.TRUE.equals(device.get("revoked"))&&device.get("runtime_epoch")!=null&&now>=((Number)device.get("state_seen")).longValue()&&now-((Number)device.get("state_seen")).longValue()<=30000) {
            String id=(String)device.get("id");snapshots.put(id,decode((String)device.get("state_json")));seen.put(id,((Number)device.get("state_seen")).longValue());
        }
        for(var row:rows){
            row.put("online",!Boolean.TRUE.equals(row.get("revoked"))&&now-((Number)row.get("last_seen")).longValue()<=60000);
            String device=(String)row.get("device_id");JsonNode state=snapshots.getOrDefault(device,mapper.createObjectNode()).path((String)row.get("native_id"));
            String status=taskState(state);
            if(status!=null){row.put("task_state",status);row.put("task_state_remaining_ms",30000-(now-seen.get(device)));}
            if(stripNativeId)row.remove("native_id");
        }
    }
    private String taskState(JsonNode state) {
        if(!state.isObject()||!state.path("running").isBoolean())return null;
        if(state.path("controllable").isBoolean()&&!state.path("controllable").asBoolean())return null;
        boolean question=false;
        for(var pending:state.path("pending"))if(pending.path("request").isObject()&&!pending.path("eventId").asText().isEmpty()){
            if("approval/request".equals(pending.path("event").asText()))return "approval";
            if("user-questions/request".equals(pending.path("event").asText()))question=true;
        }
        for(var item:state.path("projections").path("userQuestions").path("active"))if("continued".equals(item.path("state").asText())&&!item.path("callId").asText().isEmpty())question=true;
        return question?"question":state.path("running").asBoolean()?(state.path("admissionWaiting").asInt()>0?"waiting":"running"):"idle";
    }
    @Transactional public Map<String,Object> rename(Identity actor,String id,Title request) {
        actor=actor(actor);
        valid(request!=null&&request.title()!=null&&request.revision()!=null&&request.revision()>=0);
        String title=request.title().strip();
        valid(!title.isEmpty()&&title.length()<=160&&title.codePoints().noneMatch(Character::isISOControl));
        actor=lockActor(actor);
        var row=session(actor,id,true);
        long revision=((Number)row.get("title_revision")).longValue();
        if(revision!=request.revision())throw ApiException.conflict("SYNC_TITLE_CHANGED");
        valid(revision<Long.MAX_VALUE);
        db.update("update sync_sessions set title=?,title_revision=? where id=?",title,revision+1,id);
        return Map.of("title",title,"revision",revision+1);
    }
    @Transactional public Map<String,Object> archive(Identity actor,String id,Archive request) {
        actor=actor(actor);
        valid(request!=null&&request.archived()!=null&&request.revision()!=null&&request.revision()>=0);
        actor=lockActor(actor);
        var row=session(actor,id,true);
        long revision=((Number)row.get("archive_revision")).longValue();
        if(revision!=request.revision())throw ApiException.conflict("SYNC_ARCHIVE_CHANGED");
        valid(revision<Long.MAX_VALUE);
        db.update("update sync_sessions set archived=?,archive_revision=? where id=?",request.archived(),revision+1,id);
        return Map.of("archived",request.archived(),"revision",revision+1);
    }
    public Map<String,Object> page(Identity actor,String id,long offset,int limit) {
        actor=actor(actor);valid(offset>=0&&limit>0&&limit<=100);var row=session(actor,id,false);
        List<JsonNode> events=new ArrayList<>();int bytes=0;
        for(String raw:db.queryForList("select event_json from sync_session_events where session_id=? and seq>=? order by seq limit ?",String.class,id,offset,limit)) {
            int size=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes+size>900000&&!events.isEmpty())break;
            events.add(decode(raw));bytes+=size;
        }
        return Map.of("header",decode((String)row.get("header_json")),"inheritedEventCount",row.get("inherited_event_count"),"events",events,"nextSeq",events.isEmpty()?offset:events.get(events.size()-1).path("seq").asLong()+1,"durableNextSeq",row.get("next_seq"),"deviceName",row.get("device_name"));
    }
}
