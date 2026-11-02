package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:device-links;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class DesktopDeviceLinksTest {
 @Autowired DesktopDeviceLinks links;@Autowired DesktopHistorySync sync;@Autowired DesktopInteractions interactions;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 Identity owner,other;String device;
 Identity user(String tenant){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER","User",Set.of("VIEWER"),Set.of("sandbox"),token);return auth.authenticate(token);}
 @BeforeEach void setup(){owner=user(AuthService.id());other=user(owner.tenant());device=AuthService.id();sync.registerDevice(owner,new DesktopHistorySync.Device(device,"Mac","darwin"));publish("one");}
 void publish(String epoch){interactions.publish(owner,device,new DesktopInteractions.State(epoch,json.createObjectNode()));}
 DesktopDeviceLinks.Secret secret(Map<String,Object> code){return new DesktopDeviceLinks.Secret((String)code.get("secret"));}
 @Test void confirmationIsExplicitIdempotentAndAccountOwned(){
  var code=links.create(owner,device);String id=(String)code.get("id");var secret=secret(code);
  assertEquals(false,links.inspect(owner,id,secret).get("confirmed"));assertEquals(device,links.inspect(owner,id,secret).get("deviceId"));
  assertEquals(404,assertThrows(ApiException.class,()->links.inspect(other,id,secret)).status);
  assertEquals(404,assertThrows(ApiException.class,()->links.inspect(user(AuthService.id()),id,secret)).status);
  assertEquals(404,assertThrows(ApiException.class,()->links.inspect(owner,id,new DesktopDeviceLinks.Secret("0".repeat(64)))).status);
  assertFalse(db.queryForMap("select * from sync_device_links where id=?",id).containsValue(secret.secret()));
  assertEquals(true,links.confirm(owner,id,secret).get("confirmed"));var time=db.queryForObject("select confirmed_at from sync_device_links where id=?",Long.class,id);
  assertEquals(true,links.confirm(owner,id,secret).get("confirmed"));assertEquals(time,db.queryForObject("select confirmed_at from sync_device_links where id=?",Long.class,id));
 }
 @Test void refreshCancellationAndExpirationInvalidateCodes(){
  var first=links.create(owner,device);var second=links.create(owner,device);
  assertEquals(404,assertThrows(ApiException.class,()->links.inspect(owner,(String)first.get("id"),secret(first))).status);
  String id=(String)second.get("id");links.cancel(other,id,secret(second));assertEquals(false,links.inspect(owner,id,secret(second)).get("confirmed"));
  db.update("update sync_device_links set expires_at=0 where id=?",id);
  assertEquals(409,assertThrows(ApiException.class,()->links.confirm(owner,id,secret(second))).status);
  var third=links.create(owner,device);links.cancel(owner,(String)third.get("id"),secret(third));
  assertEquals(404,assertThrows(ApiException.class,()->links.inspect(owner,(String)third.get("id"),secret(third))).status);
  assertEquals(0,db.queryForObject("select count(*) from sync_device_links where device_id=?",Integer.class,device));
 }
 @Test void runtimeAuthorizationAndFreshnessMustRemainUnchanged(){
  var code=links.create(owner,device);String id=(String)code.get("id");publish("two");
  assertEquals(409,assertThrows(ApiException.class,()->links.confirm(owner,id,secret(code))).status);
  var current=links.create(owner,device);sync.revokeDevice(owner,device);
  assertEquals(409,assertThrows(ApiException.class,()->links.confirm(owner,(String)current.get("id"),secret(current))).status);
  sync.restoreDevice(owner,device);publish("two");
  assertEquals(409,assertThrows(ApiException.class,()->links.confirm(owner,(String)current.get("id"),secret(current))).status);
  db.update("update sync_devices set state_seen=0 where id=?",device);
  assertEquals(409,assertThrows(ApiException.class,()->links.create(owner,device)).status);
  assertEquals(404,assertThrows(ApiException.class,()->links.create(other,device)).status);
 }
}
