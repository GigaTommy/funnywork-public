package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable per-binding delivery, with authorization rechecked immediately before sending. */
@Component
public class MobilePushDispatcher {
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;private final ApnsProvider provider;
    private final TransactionTemplate tx;private final boolean enabled;private final LocalCredentialPolicy credentialPolicy;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"mobile-push");thread.setDaemon(true);return thread;});
    public MobilePushDispatcher(JdbcTemplate db,ObjectMapper json,Clock clock,ApnsProvider provider,LocalCredentialPolicy credentialPolicy,PlatformTransactionManager transactions,@Value("${funnywork.scheduling-enabled:true}") boolean enabled){this.db=db;this.json=json;this.clock=clock;this.provider=provider;this.credentialPolicy=credentialPolicy;this.tx=new TransactionTemplate(transactions);this.enabled=enabled;}
    @Scheduled(fixedDelay=5000) public void scheduled(){
        if(!enabled||!provider.enabled()||!running.compareAndSet(false,true))return;
        executor.submit(()->{try{tick();}finally{running.set(false);}});
    }
    @PreDestroy public void close(){executor.shutdownNow();}
    void tick(){
        if(!provider.enabled())return;
        expand();
        for(String id:db.queryForList("select id from sync_push_deliveries where state='queued' and next_attempt<=? order by next_attempt,id limit 8",String.class,clock.millis()))deliver(id);
        tx.executeWithoutResult(status->{
            long cutoff=clock.millis()-7*86400000L;
            db.update("delete from sync_push_deliveries where event_id in (select id from sync_notification_events where created_at<?)",cutoff);
            db.update("delete from sync_notification_events where created_at<?",cutoff);
        });
    }
    void expand(){
        for(String id:db.queryForList("select id from sync_notification_events where delivery_state='queued' order by created_at,id limit 32",String.class))tx.executeWithoutResult(status->{
            var rows=db.queryForList("select * from sync_notification_events where id=? for update",id);
            if(rows.isEmpty()||!"queued".equals(rows.get(0).get("delivery_state")))return;
            var event=rows.get(0);long now=clock.millis();
            if(now-((Number)event.get("created_at")).longValue()>300000){db.update("update sync_notification_events set delivery_state='expired' where id=?",id);return;}
            var subscriptions=db.queryForList("select * from sync_push_installations where tenant_id=? and principal_id=? and subscribed_at<=? and expires_at>?",event.get("tenant_id"),event.get("principal_id"),event.get("created_at"),now);
            for(var subscription:subscriptions) {
                if(!subscribed(subscription,(String)event.get("kind")))continue;
                String deliveryId=AuthService.hash(id+"/"+subscription.get("binding_id"));
                db.update("insert into sync_push_deliveries(id,event_id,installation_id,binding_id,next_attempt) values(?,?,?,?,?)",deliveryId,id,subscription.get("id"),subscription.get("binding_id"),now);
            }
            db.update("update sync_notification_events set delivery_state='expanded' where id=?",id);
        });
    }
    private boolean subscribed(Map<String,Object> row,String kind){return Boolean.TRUE.equals(row.get(switch(kind){case "approval"->"approvals";case "question"->"questions";default->"turn_ended";}));}
    void deliver(String id){tx.executeWithoutResult(transaction->{
        var jobs=db.queryForList("select * from sync_push_deliveries where id=? for update",id);
        if(jobs.isEmpty())return;var job=jobs.get(0);long now=clock.millis();
        if(!"queued".equals(job.get("state"))||((Number)job.get("next_attempt")).longValue()>now)return;
        var event=db.queryForMap("select * from sync_notification_events where id=?",job.get("event_id"));
        if(now-((Number)event.get("created_at")).longValue()>300000){finish(id,"expired",null);return;}
        var owners=db.queryForList("select * from principals where id=? and tenant_id=? for update",event.get("principal_id"),event.get("tenant_id"));
        var subscriptions=db.queryForList("select * from sync_push_installations where id=? for update",job.get("installation_id"));
        if(owners.isEmpty()||subscriptions.isEmpty()){finish(id,"cancelled",null);return;}
        var owner=owners.get(0);var subscription=subscriptions.get(0);
        if(!Boolean.TRUE.equals(owner.get("enabled"))||!Objects.equals(owner.get("auth_version"),subscription.get("auth_version"))||!job.get("binding_id").equals(subscription.get("binding_id"))
            ||!event.get("principal_id").equals(subscription.get("principal_id"))||!event.get("tenant_id").equals(subscription.get("tenant_id"))||((Number)subscription.get("expires_at")).longValue()<=now||!subscribed(subscription,(String)event.get("kind"))){finish(id,"cancelled",null);return;}
        if(!Boolean.TRUE.equals(subscription.get("management_session"))&&(!credentialPolicy.permits((String)owner.get("tenant_id"),(String)owner.get("kind"))||db.queryForList("select token_hash from api_tokens where token_hash=? and principal_id=? and revoked=false and expires_at>?",subscription.get("session_hash"),event.get("principal_id"),now).isEmpty())){finish(id,"cancelled",null);return;}
        var devices=db.queryForList("select * from sync_devices where id=? and tenant_id=? and principal_id=? for update",event.get("device_id"),event.get("tenant_id"),event.get("principal_id"));
        if(devices.isEmpty()||Boolean.TRUE.equals(devices.get(0).get("revoked"))||!current(event,devices.get(0),now)){finish(id,"cancelled",null);return;}
        int attempts=((Number)job.get("attempts")).intValue()+1;
        db.update("update sync_push_deliveries set attempts=? where id=?",attempts,id);
        try {
            var result=provider.send((String)subscription.get("token"),(String)subscription.get("environment"),id,payload(event,subscription),((Number)event.get("created_at")).longValue()+300000);
            if(result.accepted()){finish(id,"accepted",result.status());return;}
            if(result.invalidToken()){
                db.update("delete from sync_push_installations where id=? and binding_id=?",subscription.get("id"),subscription.get("binding_id"));finish(id,"cancelled",result.status());return;
            }
            if(result.retryable()){retry(id,attempts,result.status());return;}
            finish(id,"failed",result.status());
        }catch(InterruptedException error){Thread.currentThread().interrupt();retry(id,attempts,null);}
        catch(Exception error){retry(id,attempts,null);}
    });}
    private boolean current(Map<String,Object> event,Map<String,Object> device,long now){
        if("turn-ended".equals(event.get("kind")))return true;
        if(!event.get("epoch").equals(device.get("runtime_epoch"))||now-((Number)device.get("state_seen")).longValue()>30000)return false;
        try {
            var sessions=db.queryForList("select native_id from sync_sessions where id=? and device_id=?",event.get("session_id"),event.get("device_id"));if(sessions.isEmpty())return false;
            JsonNode state=json.readTree((String)device.get("state_json")).path((String)sessions.get(0).get("native_id"));
            if(state.path("controllable").isBoolean()&&!state.path("controllable").asBoolean())return false;
            String source=(String)event.get("source_id");
            for(var item:state.path("pending"))if(source.equals("event/"+item.path("eventId").asText())&&item.path("request").isObject()&&(("approval".equals(event.get("kind"))&&"approval/request".equals(item.path("event").asText()))||("question".equals(event.get("kind"))&&"user-questions/request".equals(item.path("event").asText()))))return true;
            if("question".equals(event.get("kind")))for(var item:state.path("projections").path("userQuestions").path("active"))if(source.equals("call/"+item.path("callId").asText())&&"continued".equals(item.path("state").asText()))return true;
        }catch(Exception ignored){}
        return false;
    }
    private Map<String,Object> payload(Map<String,Object> event,Map<String,Object> subscription){
        boolean chinese="zh-Hans".equals(subscription.get("locale"));
        String body=switch((String)event.get("kind")){
            case "approval"->chinese?"有操作需要你批准。":"An action needs your approval.";
            case "question"->chinese?"有问题需要你回答。":"A question needs your answer.";
            default->chinese?"任务已结束，打开查看结果。":"The task ended. Open to review the result.";
        };
        return Map.of("aps",Map.of("alert",Map.of("title","funnywork","body",body),"sound","default"),"version",1,"notificationId",event.get("id"),"sessionId",event.get("session_id"),"bindingId",subscription.get("binding_id"),"kind",event.get("kind"));
    }
    private void retry(String id,int attempts,Integer status){
        if(attempts>=5){finish(id,"failed",status);return;}
        db.update("update sync_push_deliveries set state='queued',next_attempt=?,response_status=? where id=?",clock.millis()+10000L*(1L<<(attempts-1)),status,id);
    }
    private void finish(String id,String state,Integer status){db.update("update sync_push_deliveries set state=?,response_status=? where id=?",state,status,id);}
}
