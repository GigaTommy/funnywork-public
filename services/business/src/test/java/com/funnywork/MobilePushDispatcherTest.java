package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:push-dispatch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class MobilePushDispatcherTest {
    @Autowired MobilePushDispatcher dispatcher;@Autowired MobilePushSubscriptions subscriptions;
    @Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired DesktopHistorySync sync;
    @Autowired DesktopInteractions interactions;@Autowired ObjectMapper json;
    @MockitoBean ApnsProvider provider;
    Identity owner;String token,installation,device,chat,event,job;
    String secret="ab".repeat(32);
    MobilePushSubscriptions.Registration registration(){return new MobilePushSubscriptions.Registration(secret,"cd".repeat(32)+installation.replace("-",""),"sandbox","zh-Hans",true,true,true);}
    @BeforeEach void setup() throws Exception {
        db.update("delete from sync_push_deliveries");db.update("delete from sync_notification_events");db.update("delete from sync_push_installations");
        when(provider.enabled()).thenReturn(true);when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenReturn(new ApnsProvider.Result(200,""));
        token=AuthService.id();installation=AuthService.id();device=AuthService.id();
        auth.create(AuthService.id(),AuthService.id(),"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),token);owner=auth.authenticate(token);
        subscriptions.register(owner,installation,token,registration());
        sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));
        chat=(String)sync.registerSession(owner,new DesktopHistorySync.Registration(device,json.readTree("{\"id\":\"native\",\"version\":4,\"createdAt\":1,\"isSeeded\":false}"),0)).get("id");
        publish(false);publish(true);
        event=db.queryForObject("select id from sync_notification_events where principal_id=?",String.class,owner.id());
        dispatcher.expand();job=db.queryForObject("select id from sync_push_deliveries where event_id=?",String.class,event);
    }
    void publish(boolean pending) throws Exception {
        interactions.publish(owner,device,new DesktopInteractions.State("runtime",json.readTree(pending?
            "{\"native\":{\"seq\":0,\"running\":true,\"pending\":[{\"eventId\":\"approval\",\"event\":\"approval/request\",\"request\":{\"secret\":\"private tool arguments\"}}]}}":
            "{\"native\":{\"seq\":0,\"running\":true,\"pending\":[]}}")));
    }
    String state(){return db.queryForObject("select state from sync_push_deliveries where id=?",String.class,job);}
    @Test void acceptedDeliveryIsNotSentAgainAndPayloadContainsOnlyRoutingAndGenericText() throws Exception {
        dispatcher.expand();dispatcher.tick();dispatcher.tick();assertEquals("accepted",state());
        assertEquals(1,db.queryForObject("select count(*) from sync_push_deliveries where event_id=?",Integer.class,event));
        var payload=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(provider).send(eq(registration().token()),eq("sandbox"),eq(job),payload.capture(),anyLong());
        assertEquals(chat,payload.getValue().get("sessionId"));assertEquals(event,payload.getValue().get("notificationId"));
        assertEquals(db.queryForObject("select binding_id from sync_push_installations where id=?",String.class,installation),payload.getValue().get("bindingId"));
        String body=json.writeValueAsString(payload.getValue());assertTrue(body.contains("有操作需要你批准"));assertFalse(body.contains("private tool arguments"));assertFalse(body.contains(owner.id()));
    }
    @ParameterizedTest @ValueSource(strings={"resolved","logout","rebound","disabled","version","token-revoked","token-expired","lease-expired","device-revoked","heartbeat","epoch","uncontrollable","preference"})
    void changesAfterFanoutCancelDelivery(String change) throws Exception {
        switch(change){
            case "resolved" -> publish(false);
            case "logout" -> subscriptions.logout(owner,token);
            case "rebound" -> {String otherToken=AuthService.id();auth.create(AuthService.id(),owner.tenant(),"USER","Other",Set.of("VIEWER"),Set.of("sandbox"),otherToken);subscriptions.register(auth.authenticate(otherToken),installation,otherToken,registration());}
            case "disabled" -> db.update("update principals set enabled=false where id=?",owner.id());
            case "version" -> db.update("update principals set auth_version=auth_version+1 where id=?",owner.id());
            case "token-revoked" -> db.update("update api_tokens set revoked=true where token_hash=?",AuthService.hash(token));
            case "token-expired" -> db.update("update api_tokens set expires_at=0 where token_hash=?",AuthService.hash(token));
            case "lease-expired" -> db.update("update sync_push_installations set expires_at=0 where id=?",installation);
            case "device-revoked" -> sync.revokeDevice(owner,device);
            case "heartbeat" -> db.update("update sync_devices set state_seen=0 where id=?",device);
            case "epoch" -> db.update("update sync_devices set runtime_epoch='new-runtime' where id=?",device);
            case "uncontrollable" -> {var state=json.readTree((String)db.queryForMap("select state_json from sync_devices where id=?",device).get("state_json"));((com.fasterxml.jackson.databind.node.ObjectNode)state.path("native")).put("controllable",false);db.update("update sync_devices set state_json=? where id=?",json.writeValueAsString(state),device);}
            case "preference" -> subscriptions.register(owner,installation,token,new MobilePushSubscriptions.Registration(secret,registration().token(),"sandbox","zh-Hans",false,true,true));
        }
        dispatcher.deliver(job);assertEquals("cancelled",state());verify(provider,never()).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
    }
    @Test void temporaryFailuresWaitThenRetryAndStopAfterFiveAttempts() throws Exception {
        when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenReturn(new ApnsProvider.Result(429,"TooManyRequests"));
        for(int attempt=1;attempt<=5;attempt++){
            db.update("update sync_push_deliveries set next_attempt=0 where id=?",job);dispatcher.deliver(job);
            assertEquals(attempt,db.queryForObject("select attempts from sync_push_deliveries where id=?",Integer.class,job));
            if(attempt<5){assertEquals("queued",state());dispatcher.deliver(job);assertEquals(attempt,db.queryForObject("select attempts from sync_push_deliveries where id=?",Integer.class,job));}
        }
        assertEquals("failed",state());dispatcher.deliver(job);verify(provider,times(5)).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
    }
    @Test void transientFailureCanRecoverWithoutCreatingAnotherDelivery() throws Exception {
        when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenThrow(new java.io.IOException("lost response")).thenReturn(new ApnsProvider.Result(200,""));
        dispatcher.deliver(job);assertEquals("queued",state());db.update("update sync_push_deliveries set next_attempt=0 where id=?",job);dispatcher.deliver(job);assertEquals("accepted",state());
        assertEquals(2,db.queryForObject("select attempts from sync_push_deliveries where id=?",Integer.class,job));
    }
    @ParameterizedTest @ValueSource(ints={400,410})
    void invalidTokenRetiresSubscription(int status) throws Exception {
        when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenReturn(new ApnsProvider.Result(status,status==400?"BadDeviceToken":"Unregistered"));
        dispatcher.deliver(job);assertEquals("cancelled",state());assertTrue(db.queryForList("select id from sync_push_installations where id=?",installation).isEmpty());
    }
    @Test void expiredEventDoesNotSendOrFanOutToNewSubscribers() throws Exception {
        db.update("update sync_notification_events set created_at=? where id=?",System.currentTimeMillis()-300001,event);
        dispatcher.deliver(job);assertEquals("expired",state());verify(provider,never()).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
        db.update("update sync_notification_events set delivery_state='queued' where id=?",event);dispatcher.expand();
        assertEquals("expired",db.queryForObject("select delivery_state from sync_notification_events where id=?",String.class,event));
    }
    @Test void registrationAfterEventDoesNotReceiveHistoricalAlert() {
        db.update("delete from sync_push_deliveries where event_id=?",event);db.update("update sync_notification_events set delivery_state='queued',created_at=created_at-1000 where id=?",event);
        dispatcher.expand();assertTrue(db.queryForList("select id from sync_push_deliveries where event_id=?",event).isEmpty());
    }
    @Test void durableTurnEndCanSendAfterRuntimeGoesOffline() throws Exception {
        db.update("update sync_notification_events set kind='turn-ended' where id=?",event);db.update("update sync_devices set state_seen=0,runtime_epoch='new-runtime' where id=?",device);
        dispatcher.deliver(job);assertEquals("accepted",state());verify(provider).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
    }
    @Test void concurrentWorkersSerializeTheSameDelivery() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenAnswer(call->{entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("send did not release");return new ApnsProvider.Result(200,"");});
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->dispatcher.deliver(job));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var second=workers.submit(()->dispatcher.deliver(job));release.countDown();
            first.get(5,java.util.concurrent.TimeUnit.SECONDS);second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("accepted",state());verify(provider).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test void continuedQuestionRemainsActionableAndUsesQuestionText() throws Exception {
        db.update("update sync_notification_events set kind='question',source_id='call/continued' where id=?",event);
        interactions.publish(owner,device,new DesktopInteractions.State("runtime",json.readTree("{\"native\":{\"seq\":0,\"running\":true,\"pending\":[],\"projections\":{\"userQuestions\":{\"active\":[{\"state\":\"continued\",\"callId\":\"continued\"}]}}}}")));
        dispatcher.deliver(job);assertEquals("accepted",state());
        var payload=org.mockito.ArgumentCaptor.forClass(Map.class);verify(provider).send(anyString(),anyString(),eq(job),payload.capture(),anyLong());
        assertEquals("question",payload.getValue().get("kind"));assertTrue(json.writeValueAsString(payload.getValue()).contains("有问题需要你回答"));
    }
    @Test void permanentProviderErrorDoesNotRetry() throws Exception {
        when(provider.send(anyString(),anyString(),anyString(),anyMap(),anyLong())).thenReturn(new ApnsProvider.Result(403,"InvalidProviderToken"));
        dispatcher.deliver(job);dispatcher.deliver(job);assertEquals("failed",state());verify(provider).send(anyString(),anyString(),anyString(),anyMap(),anyLong());
    }

}
