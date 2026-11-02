package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mobile-push;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class MobilePushSubscriptionsTest {
    @Autowired MobilePushSubscriptions subscriptions;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired ObjectMapper json;
    Identity owner,other;String token,id;String secret="ab".repeat(32);
    @BeforeEach void setup(){String tenant=AuthService.id();token=AuthService.id();id=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),token);owner=auth.authenticate(token);String otherToken=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Other",Set.of("VIEWER"),Set.of("sandbox"),otherToken);other=auth.authenticate(otherToken);}
    MobilePushSubscriptions.Registration registration(){return new MobilePushSubscriptions.Registration(secret,"cd".repeat(32)+id.replace("-",""),"sandbox","zh-Hans",true,true,true);}
    @Test void retriesPreserveBindingButAccountAndPreferenceChangesReplaceIt(){
        var request=registration();var first=subscriptions.register(owner,id,token,request);var retry=subscriptions.register(owner,id,token,request);assertEquals(first.get("bindingId"),retry.get("bindingId"));
        assertFalse(db.queryForMap("select * from sync_push_installations where id=?",id).containsValue(secret));
        var changed=new MobilePushSubscriptions.Registration(secret,request.token(),"sandbox","en",true,false,false);
        var next=subscriptions.register(owner,id,token,changed);assertNotEquals(first.get("bindingId"),next.get("bindingId"));
        var rebound=subscriptions.register(other,id,"other-session",changed);assertNotEquals(next.get("bindingId"),rebound.get("bindingId"));
        subscriptions.logout(owner,token);subscriptions.remove(owner,id,secret);
        assertEquals(other.id(),db.queryForObject("select principal_id from sync_push_installations where id=?",String.class,id));
        subscriptions.remove(other,id,secret);assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
    }
    @Test void wrongInstallationSecretCannotChangeOwnerOrPreferences(){
        var request=registration();subscriptions.register(owner,id,token,request);
        var wrong=new MobilePushSubscriptions.Registration("ef".repeat(32),request.token(),"sandbox","en",false,false,false);
        assertEquals(403,assertThrows(ApiException.class,()->subscriptions.register(other,id,"other-session",wrong)).status);
        subscriptions.remove(owner,id,"ef".repeat(32));assertEquals(1,db.queryForObject("select count(*) from sync_push_installations where id=?",Integer.class,id));
    }
    @Test void reinstallRetiresPreviousDestinationAndLogoutRemovesCurrentSession(){
        var request=registration();subscriptions.register(owner,id,token,request);String replacement=AuthService.id();
        subscriptions.register(owner,replacement,token,request);assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
        subscriptions.logout(owner,token);assertTrue(db.queryForList("select id from sync_push_installations where id=?",replacement).isEmpty());
    }
    @Test void httpEndpointsRequireAuthenticationAndLogoutDisablesSubscription() throws Exception {
        http.perform(get("/api/account/push")).andExpect(status().isUnauthorized());
        http.perform(put("/api/account/push/installations/"+id).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsBytes(registration())))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.available").value(false));
        http.perform(post("/api/account/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
    }
    MobilePushSubscriptions.Registration request(long revision){var value=registration();return new MobilePushSubscriptions.Registration(value.secret(),value.token(),value.environment(),value.locale(),value.approvals(),value.questions(),value.turnEnded(),revision);}
    @Test void delayedRegistrationsCannotOverwriteNewAccountBindingOrRecreateRemovedSubscription(){
        var initial=subscriptions.register(owner,id,token,request(1));
        var current=subscriptions.register(other,id,"other-session",request(3));
        assertEquals("PUSH_REGISTRATION_STALE",assertThrows(ApiException.class,()->subscriptions.register(owner,id,token,request(2))).code);
        assertEquals(current.get("bindingId"),db.queryForObject("select binding_id from sync_push_installations where id=?",String.class,id));
        subscriptions.remove(other,id,secret,4L);
        assertThrows(ApiException.class,()->subscriptions.register(other,id,"other-session",request(3)));
        assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
        var restored=subscriptions.register(other,id,"other-session",request(5));assertNotEquals(initial.get("bindingId"),restored.get("bindingId"));
        assertThrows(ApiException.class,()->subscriptions.remove(other,id,secret,4L));
        assertEquals(restored.get("bindingId"),db.queryForObject("select binding_id from sync_push_installations where id=?",String.class,id));
    }
    @Test void equalRevisionOnlyAllowsIdenticalRetryAndLegacyCannotOverwriteOrderedClient(){
        var first=subscriptions.register(owner,id,token,request(1));
        assertEquals(first.get("bindingId"),subscriptions.register(owner,id,token,request(1)).get("bindingId"));
        assertThrows(ApiException.class,()->subscriptions.register(other,id,"other-session",request(1)));
        assertThrows(ApiException.class,()->subscriptions.register(owner,id,token,registration()));
        assertThrows(ApiException.class,()->subscriptions.remove(owner,id,secret));
        assertEquals(first.get("bindingId"),db.queryForObject("select binding_id from sync_push_installations where id=?",String.class,id));
    }
    @Test void logoutPreventsAlreadyAuthenticatedLateRequestsIncludingManagementSessions(){
        String session="ruoyi.test-session-"+id;
        subscriptions.register(owner,id,session,request(1));subscriptions.logout(owner,session);
        assertEquals(403,assertThrows(ApiException.class,()->subscriptions.register(owner,id,session,request(2))).status);
        assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
        subscriptions.register(owner,id,"ruoyi.new-session-"+id,request(3));
        assertEquals(owner.id(),db.queryForObject("select principal_id from sync_push_installations where id=?",String.class,id));
    }

    @Test void removalBeforeDelayedFirstRegistrationLeavesAnOrderedTombstone(){
        subscriptions.remove(owner,id,secret,2L);
        assertThrows(ApiException.class,()->subscriptions.register(owner,id,token,request(1)));
        assertTrue(db.queryForList("select id from sync_push_installations where id=?",id).isEmpty());
        subscriptions.register(owner,id,token,request(3));
        assertEquals(3L,db.queryForObject("select revision from sync_push_registration_order where id=?",Long.class,id));
    }
    @Test void concurrentFirstRegistrationAlwaysPreservesNewestRevision() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=workers.submit(()->{start.await();try{subscriptions.register(owner,id,token,request(1));}catch(ApiException error){assertEquals("PUSH_REGISTRATION_STALE",error.code);}return null;});
            var second=workers.submit(()->{start.await();subscriptions.register(other,id,"other-session",request(2));return null;});
            start.countDown();first.get(5,java.util.concurrent.TimeUnit.SECONDS);second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(other.id(),db.queryForObject("select principal_id from sync_push_installations where id=?",String.class,id));
        }finally{start.countDown();workers.shutdownNow();}
    }

}
