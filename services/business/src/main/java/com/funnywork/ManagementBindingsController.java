package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
@RestController @RequestMapping("/api/v1/management-bindings")
public class ManagementBindingsController {
 private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Json json;private final ManagementIdentity management;private final ObjectMapper mapper;
 public ManagementBindingsController(JdbcTemplate db,AuthService auth,TenantLocks locks,Json json,ManagementIdentity management,ObjectMapper mapper){this.db=db;this.auth=auth;this.locks=locks;this.json=json;this.management=management;this.mapper=mapper;}
 public record Binding(@Pattern(regexp="[0-9]{1,24}") @NotNull String userId,@NotEmpty Set<@Pattern(regexp="ADMIN|OPERATOR|VIEWER|RECONCILER") String> roles,@NotEmpty Set<@Pattern(regexp="[a-z0-9-]{1,64}") String> zones){}
 @GetMapping("/{userId}") public Map<String,Object> detail(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header,@PathVariable String userId){
  actor=auth.current(actor);actor.requireUser("ADMIN");
  var remote=management.authority(header.substring(7));
  var rows=db.queryForList("select p.id,p.roles,p.zones,p.enabled from management_identity_links l join principals p on p.id=l.principal_id where l.ruoyi_tenant=? and l.ruoyi_user=? and p.tenant_id=?",remote.tenant(),userId,actor.tenant());
  if(rows.isEmpty())return Map.of("configured",false);
  var row=rows.get(0);var zones=json.strings((String)row.get("zones"));
  if(!actor.zones().containsAll(zones))throw ApiException.forbidden();
  return Map.of("configured",true,"enabled",row.get("enabled"),"roles",json.strings((String)row.get("roles")),"zones",zones);
 }
 @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Identity actor){actor=auth.current(actor);actor.requireUser("ADMIN");var scope=actor.zones();return db.queryForList("select l.ruoyi_tenant,l.ruoyi_user,p.id,p.display_name,p.roles,p.zones,p.enabled from management_identity_links l join principals p on p.id=l.principal_id where p.tenant_id=? order by l.ruoyi_user",actor.tenant()).stream().filter(row->scope.containsAll(json.strings((String)row.get("zones")))).toList();}
 @DeleteMapping("/{userId}") @Transactional public void revoke(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header,@PathVariable String userId){
  locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
  var remote=management.authority(header.substring(7));
  var rows=db.queryForList("select p.* from management_identity_links l join principals p on p.id=l.principal_id where l.ruoyi_tenant=? and l.ruoyi_user=? and p.tenant_id=?",remote.tenant(),userId,actor.tenant());
  if(rows.isEmpty())throw ApiException.missing();var target=rows.get(0);String id=(String)target.get("id");
  if(id.equals(actor.id())||!actor.zones().containsAll(json.strings((String)target.get("zones"))))throw ApiException.forbidden();
  auth.disable(actor,id);
 }
 @PutMapping @Transactional public Map<String,Object> save(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header,@Valid @RequestBody Binding body){
  locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
  if(!actor.roles().containsAll(body.roles())||!actor.zones().containsAll(body.zones()))throw ApiException.forbidden();
  var remote=management.authority(header.substring(7));
  try {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767/funnyworker/accounts/"+body.userId())).timeout(Duration.ofSeconds(4)).header("Authorization",header).header("clientid","e5cd7e4891bf95d1d19206ce24a7b32e").GET().build();
   var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());var result=mapper.readTree(response.body());
   if(response.statusCode()!=200||result.path("code").asInt()!=200||!remote.tenant().equals(result.path("data").path("tenantId").asText()))throw ApiException.forbidden();
   var existing=db.queryForList("select p.* from management_identity_links l join principals p on p.id=l.principal_id where l.ruoyi_tenant=? and l.ruoyi_user=? and p.tenant_id=?",remote.tenant(),body.userId(),actor.tenant());
   String principal;
   if(existing.isEmpty()){
    principal=AuthService.id();auth.create(principal,actor.tenant(),"USER",result.path("data").path("name").asText(),body.roles(),body.zones(),auth.newToken());
    db.update("insert into management_identity_links values(?,?,?)",remote.tenant(),body.userId(),principal);
   }else{
    var p=existing.get(0);principal=(String)p.get("id");
    if(principal.equals(actor.id()))throw new ApiException(409,"SELF_SCOPE_CHANGE","不能修改自己的业务授权");
    if(!actor.zones().containsAll(json.strings((String)p.get("zones"))))throw ApiException.forbidden();
    db.update("update principals set roles=?,zones=?,enabled=true,auth_version=auth_version+1 where id=?",json.write(body.roles()),json.write(body.zones()),principal);
   }
   db.update("update api_tokens set revoked=true where principal_id=?",principal);
   auth.audit(actor,"MANAGEMENT_SCOPE_CHANGED",Map.of("userId",body.userId(),"principalId",principal,"roles",body.roles(),"zones",body.zones()));
   return Map.of("principalId",principal);
  }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}catch(Exception e){throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}
 }
}
