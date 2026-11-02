package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/** Same-account, short-lived device selection. Codes never contain account credentials. */
@Service
public class DesktopDeviceLinks {
 public record Secret(String secret) {}
 private final JdbcTemplate db;private final AuthService auth;private final Clock clock;private final DesktopPolicies policies;
 public DesktopDeviceLinks(JdbcTemplate db,AuthService auth,Clock clock,DesktopPolicies policies){this.db=db;this.auth=auth;this.clock=clock;this.policies=policies;}
 private Identity actor(Identity actor){actor=auth.current(actor);if(!actor.kind().equals("USER"))throw ApiException.forbidden();return actor;}
 private Identity lockActor(Identity actor){policies.lock(actor);actor=actor(actor);db.queryForObject("select id from principals where id=? for update",String.class,actor.id());return actor(actor);}
 private Map<String,Object> source(Identity actor,String id){
  var rows=db.queryForList("select * from sync_devices where id=? and tenant_id=? and principal_id=? for update",id,actor.tenant(),actor.id());
  if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
 }
 private void online(Map<String,Object> row){
  long seen=((Number)row.get("state_seen")).longValue(),now=clock.millis();
  if(Boolean.TRUE.equals(row.get("revoked"))||row.get("runtime_epoch")==null||seen>now||now-seen>30000)throw ApiException.conflict("SYNC_DEVICE_OFFLINE");
 }
 private static byte[] hash(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
 private String secret(Secret request){if(request==null||request.secret()==null||!request.secret().matches("[a-f0-9]{64}"))throw new ApiException(400,"INVALID_DEVICE_LINK","Invalid device connection code");return request.secret();}
 @Transactional public Map<String,Object> create(Identity actor,String deviceId){
  actor=lockActor(actor);policies.requireCrossDevice(actor);var device=source(actor,deviceId);online(device);
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String secret=HexFormat.of().formatHex(bytes),id=AuthService.id();
  db.update("delete from sync_device_links where device_id=?",deviceId);
  db.update("insert into sync_device_links(id,device_id,tenant_id,principal_id,secret_hash,epoch,access_revision,expires_at) values(?,?,?,?,?,?,?,?)",id,deviceId,actor.tenant(),actor.id(),HexFormat.of().formatHex(hash(secret)),device.get("runtime_epoch"),device.get("access_revision"),clock.millis()+120000);
  return Map.of("id",id,"secret",secret,"expiresIn",120000);
 }
 @Transactional public Map<String,Object> inspect(Identity actor,String id,Secret request){return access(actor,id,request,false);}
 @Transactional public Map<String,Object> confirm(Identity actor,String id,Secret request){return access(actor,id,request,true);}
 private Map<String,Object> access(Identity actor,String id,Secret request,boolean confirm){
  String secret=secret(request);actor=lockActor(actor);policies.requireCrossDevice(actor);
  // Acquire the source before the code, matching creation and device authorization changes.
  var matches=db.queryForList("select device_id from sync_device_links where id=? and tenant_id=? and principal_id=?",id,actor.tenant(),actor.id());
  if(matches.isEmpty())throw ApiException.missing();var device=source(actor,(String)matches.get(0).get("device_id"));
  var codes=db.queryForList("select * from sync_device_links where id=? and tenant_id=? and principal_id=? for update",id,actor.tenant(),actor.id());
  if(codes.isEmpty())throw ApiException.missing();var code=codes.get(0);
  if(!MessageDigest.isEqual(hash(secret),HexFormat.of().parseHex((String)code.get("secret_hash"))))throw ApiException.missing();
  long remaining=((Number)code.get("expires_at")).longValue()-clock.millis();
  if(remaining<=0)throw ApiException.conflict("DEVICE_LINK_EXPIRED");
  online(device);
  if(!Objects.equals(code.get("epoch"),device.get("runtime_epoch"))||((Number)code.get("access_revision")).longValue()!=((Number)device.get("access_revision")).longValue())throw ApiException.conflict("DEVICE_LINK_CHANGED");
  if(confirm&&code.get("confirmed_at")==null)db.update("update sync_device_links set confirmed_at=? where id=?",clock.millis(),id);
  return Map.of("id",id,"deviceId",device.get("id"),"name",device.get("name"),"platform",device.get("platform"),"epoch",device.get("runtime_epoch"),"expiresIn",Math.min(remaining,120000),"confirmed",confirm||code.get("confirmed_at")!=null);
 }
 @Transactional public void cancel(Identity actor,String id,Secret request){
  String secret=secret(request);actor=lockActor(actor);
  db.update("delete from sync_device_links where id=? and tenant_id=? and principal_id=? and secret_hash=?",id,actor.tenant(),actor.id(),HexFormat.of().formatHex(hash(secret)));
 }
}
