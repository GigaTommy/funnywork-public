package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Durable notification facts only. No push provider or user message content is involved here. */
@Service
public class DesktopNotifications {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    public DesktopNotifications(JdbcTemplate db,ObjectMapper json,Clock clock) { this.db=db;this.json=json;this.clock=clock; }
    private JsonNode decode(Object value) {
        if(value==null)return json.createObjectNode();
        try{return json.readTree((String)value);}catch(Exception error){throw new IllegalStateException("Invalid notification baseline",error);}
    }
    // Called while the source device row is locked, in the state publication transaction.
    public void observeState(Identity actor,String deviceId,String epoch,JsonNode current,Map<String,Object> prior) {
        boolean sameEpoch=epoch.equals(prior.get("runtime_epoch"));
        JsonNode old=sameEpoch?decode(prior.get("state_json")):json.createObjectNode();
        JsonNode baseline=sameEpoch?decode(prior.get("notification_baseline_json")):json.createObjectNode();
        ObjectNode next=json.createObjectNode();
        var sessions=db.queryForList("select id,native_id from sync_sessions where device_id=? and tenant_id=? and principal_id=?",deviceId,actor.tenant(),actor.id());
        Map<String,String> sessionIds=new HashMap<>();
        for(var row:sessions)sessionIds.put((String)row.get("native_id"),(String)row.get("id"));
        for(var fields=current.fields();fields.hasNext();) {
            var field=fields.next();String nativeId=field.getKey();JsonNode state=field.getValue();
            next.set(nativeId,baseline.has(nativeId)?baseline.get(nativeId):state.path("seq"));
            String sessionId=sessionIds.get(nativeId);
            if(!sameEpoch||sessionId==null||!controllable(state))continue;
            Map<String,String> previous=controllable(old.path(nativeId))?items(old.path(nativeId)):Map.of();
            for(var item:items(state).entrySet())if(!previous.containsKey(item.getKey()))
                record(actor.tenant(),actor.id(),deviceId,sessionId,epoch,item.getValue(),item.getKey());
        }
        db.update("update sync_devices set notification_baseline_json=? where id=?",next.toString(),deviceId);
    }
    private boolean controllable(JsonNode state) { return state.isObject()&&(!state.path("controllable").isBoolean()||state.path("controllable").asBoolean()); }
    private Map<String,String> items(JsonNode state) {
        Map<String,String> items=new LinkedHashMap<>();
        for(var item:state.path("pending")) {
            String id=item.path("eventId").asText();if(id.isEmpty()||id.length()>240||!item.path("request").isObject())continue;
            String event=item.path("event").asText();
            if(event.equals("approval/request"))items.put("event/"+id,"approval");
            if(event.equals("user-questions/request"))items.put("event/"+id,"question");
        }
        for(var item:state.path("projections").path("userQuestions").path("active")) {
            String id=item.path("callId").asText();
            if("continued".equals(item.path("state").asText())&&!id.isEmpty()&&id.length()<=240)items.put("call/"+id,"question");
        }
        return items;
    }
    // Called only for newly committed history events; replayed batches never call this method.
    public void observeEvent(Map<String,Object> session,JsonNode event) {
        if(!"turn/end".equals(event.path("type").asText()))return;
        long age=clock.millis()-event.path("time").asLong();if(age<0||age>300000)return;
        var device=db.queryForMap("select * from sync_devices where id=?",session.get("device_id"));
        if(Boolean.TRUE.equals(device.get("revoked"))||device.get("runtime_epoch")==null||clock.millis()-((Number)device.get("state_seen")).longValue()>30000)return;
        String nativeId=(String)session.get("native_id");JsonNode baseline=decode(device.get("notification_baseline_json")).path(nativeId);
        if(!baseline.isIntegralNumber()||event.path("seq").asLong()<baseline.asLong()||!controllable(decode(device.get("state_json")).path(nativeId)))return;
        record((String)session.get("tenant_id"),(String)session.get("principal_id"),(String)session.get("device_id"),(String)session.get("id"),(String)device.get("runtime_epoch"),"turn-ended","seq/"+event.path("seq").asLong());
    }
    private void record(String tenant,String principal,String device,String session,String epoch,String kind,String source) {
        String id;
        try {
            byte[] bytes=json.writeValueAsBytes(List.of(tenant,principal,device,session,epoch,kind,source));
            id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(Exception error){throw new IllegalStateException(error);}
        // Both writers hold the same device lock, so check/insert is serialized across instances.
        if(!db.queryForList("select id from sync_notification_events where id=?",id).isEmpty())return;
        db.update("insert into sync_notification_events(id,tenant_id,principal_id,device_id,session_id,epoch,kind,source_id,created_at) values(?,?,?,?,?,?,?,?,?)",id,tenant,principal,device,session,epoch,kind,source,clock.millis());
    }
}
