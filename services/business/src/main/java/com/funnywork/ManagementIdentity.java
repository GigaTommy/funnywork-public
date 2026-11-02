package com.funnywork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
@Service
public class ManagementIdentity {
 private final JdbcTemplate db;private final ObjectMapper mapper;private final AuthService auth;private final Json json;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 public ManagementIdentity(JdbcTemplate db,ObjectMapper mapper,AuthService auth,Json json){this.db=db;this.mapper=mapper;this.auth=auth;this.json=json;}
 public record Authority(String tenant,String user,Set<String> permissions,String username,String department,String nickname){public Authority(String tenant,String user,Set<String> permissions,String username,String department){this(tenant,user,permissions,username,department,"");}public Authority(String tenant,String user,Set<String> permissions){this(tenant,user,permissions,user,"");}public Authority(String tenant,String user,Set<String> permissions,String username){this(tenant,user,permissions,username,"");}}
 public static final String DESKTOP_CLIENT="e5cd7e4891bf95d1d19206ce24a7b32e", MOBILE_CLIENT="f93768fc8eb9821d7838d65e4ba03b3f", ANDROID_CLIENT="b12c16d37fb30716a9315e197290c56e";
 private static final ObjectMapper CLIENT_MAPPER=new ObjectMapper();
 // This only selects an allowlisted header; the identity service validates the JWT and its client binding.
 static String clientId(String token){try{String[] parts=token.split("\\.");if(parts.length==3&&parts[1].length()<=16000){var payload=CLIENT_MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));String client=payload.path("clientid").asText();if(MOBILE_CLIENT.equals(client)||ANDROID_CLIENT.equals(client))return client;}}catch(Exception ignored){}return DESKTOP_CLIENT;}
 public Authority authority(String token){
  try {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767/funnyworker/identity")).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+token).header("clientid",clientId(token)).GET().build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());var body=mapper.readTree(response.body());
   if(response.statusCode()!=200 || body.path("code").asInt()!=200)throw new ApiException(401,"UNAUTHENTICATED","请重新登录");
   var data=body.path("data");Set<String> permissions=new HashSet<>();data.path("permissions").forEach(x->permissions.add(x.asText()));
   String tenant=data.path("tenantId").asText(),user=data.path("userId").asText();
   if(!tenant.matches("[A-Za-z0-9_-]{1,64}") || !user.matches("[0-9]{1,24}"))throw ApiException.forbidden();
   return new Authority(tenant,user,Set.copyOf(permissions),data.path("username").asText(user),data.path("department").asText(""),data.path("nickname").asText(""));
  } catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}catch(Exception e){throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}
 }
 public Identity authenticate(String token,String method,String path){
  Authority remote=authority(token);String module=module(path);
  String permission=permission(method,path);
  if(!module.equals("self") && !remote.permissions().contains("*:*:*") && !remote.permissions().contains(permission)){
   if(module.equals("desktop"))throw new ApiException(403,"DESKTOP_PERMISSION_REQUIRED","请联系管理员开通桌面客户端使用权限");
   throw ApiException.forbidden();
  }
  var rows=db.queryForList("select p.* from management_identity_links l join principals p on p.id=l.principal_id where l.ruoyi_tenant=? and l.ruoyi_user=? and p.enabled=true and p.kind='USER'",remote.tenant(),remote.user());
  if(rows.isEmpty())throw new ApiException(403,"BUSINESS_SCOPE_REQUIRED","请先配置业务授权");
  var p=rows.get(0);return auth.current(new Identity((String)p.get("id"),(String)p.get("tenant_id"),"USER",json.strings((String)p.get("roles")),json.strings((String)p.get("zones")),((Number)p.get("auth_version")).longValue()));
 }
 static boolean chatPath(String path){return path.equals("/api/account/chats")||path.startsWith("/api/account/chats/");}
 static String permission(String method,String path){
  if(path.equals("/api/v1/management-tasks/batch-preview")||path.equals("/api/v1/management-tasks/batches")||path.startsWith("/api/v1/management-tasks/batches/"))return "funnyworker:task-center:write";
  if(path.startsWith("/api/v1/management-tasks/content-access/"))return "funnyworker:task-center:scope";
  if(path.matches("/api/v1/management-tasks/[^/]+/units/[^/]+/input"))return "funnyworker:task-center:content";
  if(path.matches("/api/v1/management-tasks/[^/]+/artifacts(?:/[^/]+/[0-9]+)?"))return "funnyworker:task-center:artifacts";
  if(path.equals("/api/v1/management-tasks/attribution-members")||path.startsWith("/api/v1/management-tasks/member-contexts/")||path.equals("/api/v1/management-tasks/scope-members")||path.equals("/api/v1/management-tasks/scope-options")||path.startsWith("/api/v1/management-tasks/scopes/")||path.equals("/api/v1/management-tasks/departments")||path.matches("/api/v1/management-tasks/[^/]+/context(?:-options)?"))return "funnyworker:task-center:scope";
  if(method.equals("POST")&&path.matches("/api/v1/management-tasks/[^/]+/preview"))return "funnyworker:task-center:read";
  if(method.equals("POST")&&path.equals("/api/v1/execution-placement/preview"))return "funnyworker:resources:read";
  if(method.equals("GET")&&path.equals("/api/v1/maintenance/repositories"))return "funnyworker:tasks:read";
  if(path.matches("/api/v1/management-history/(DESKTOP|EMPLOYEE|MOBILE)/[A-Za-z0-9_-]{1,64}/content"))return "funnyworker:history:content";
  return "funnyworker:"+module(path)+":"+(method.equals("GET")||path.equals("/api/account/startup")||chatPath(path)||path.startsWith("/api/account/model/")||path.startsWith("/api/account/inference/")||path.startsWith("/api/account/sync/")||path.startsWith("/api/account/policy")?"read":"write");
 }
 static String module(String path){
  if(path.equals("/api/v1/annual-reports")||path.startsWith("/api/v1/annual-reports/"))return "annual";
  if(path.equals("/api/account/startup")||path.equals("/api/account/session")||(chatPath(path)||path.startsWith("/api/account/model/")||path.startsWith("/api/account/inference/"))||path.startsWith("/api/account/sync/")||path.startsWith("/api/account/policy"))return "desktop";
  if(path.equals("/api/v1/me")||path.equals("/api/account/avatar")||path.equals("/api/account/logout"))return "self";
  if(path.equals("/api/v1/management-audit"))return "audit";
  if(path.startsWith("/api/v1/management-history"))return "history";
  if(path.equals("/api/v1/management-usage"))return "usage";
  if(path.equals("/api/v1/management-tasks")||path.startsWith("/api/v1/management-tasks/"))return "task-center";
  if(path.startsWith("/api/v1/management-bindings"))return "authorization";
  if(path.startsWith("/api/v1/tenant/"))return "scheduling";
  if(path.matches("/api/v1/nodes(?:/[^/]+/admission)?"))return "nodes";
  if(path.startsWith("/api/v1/model-middleware/usage"))return "usage";
  if(path.startsWith("/api/v1/model-middleware")||path.startsWith("/api/v1/model-profiles")||path.startsWith("/api/v1/desktop-model-releases"))return "models";
  if(path.startsWith("/api/v1/office-capabilities"))return "office";
  if(path.startsWith("/api/v1/client-policy")||path.startsWith("/api/v1/desktop-policy")||path.startsWith("/api/v1/desktop-devices"))return "client";
  if(path.startsWith("/api/v1/managed-gateways")||path.startsWith("/api/v1/mcp-bindings")||path.startsWith("/api/v1/skills"))return "integrations";
  if(path.startsWith("/api/v1/tool-")||path.matches("/api/v1/agent-definitions/[^/]+/tools.*"))return "tools";
  if(path.startsWith("/api/v1/computer-controls"))return "computer";
  if(path.startsWith("/api/v1/recovery"))return "recovery";
  if(path.matches("/api/v1/tasks/[^/]+/documents.*"))return "documents";
  if(path.equals("/api/v1/maintenance/repositories")||path.equals("/api/v1/maintenance/delivery-policies"))return "agents";
  if(path.equals("/api/v1/maintenance/node-enrollments")||path.equals("/api/v1/maintenance/device-bindings"))return "nodes";
  if(path.equals("/api/v1/maintenance/requirements")||path.matches("/api/v1/maintenance/requirements/[A-Za-z0-9-]+(?:/(queue|impact))?"))return "tasks";
  if(path.matches("/api/v1/maintenance/requirement-dependencies/[A-Za-z0-9-]+"))return "agents";
  if(path.equals("/api/v1/maintenance/problems")||path.matches("/api/v1/maintenance/problems/[A-Za-z0-9-]+(?:/(decision|verify|deliver))?"))return "tasks";
  if(path.matches("/api/v1/maintenance/intake-configs/[A-Za-z0-9-]+/source-failures"))return "agents";
  if(path.equals("/api/v1/maintenance/intake-configs")||path.matches("/api/v1/maintenance/intake-configs/[A-Za-z0-9-]+/github-sync"))return "agents";
  if(path.equals("/api/v1/maintenance/intakes")||path.matches("/api/v1/maintenance/intakes/[A-Za-z0-9-]+(?:/(retry|recover))?"))return "tasks";
  if(path.equals("/api/v1/maintenance/automation")||path.matches("/api/v1/maintenance/automation/stages/[A-Za-z0-9-]+")||path.matches("/api/v1/maintenance/automation/problems/[A-Za-z0-9-]+/evidence"))return "tasks";
  if(path.matches("/api/v1/maintenance/automation/problems/[A-Za-z0-9-]+/runtime-repair"))return "agents";
  if(path.equals("/api/v1/maintenance/automation/policies")||path.matches("/api/v1/maintenance/repositories/[A-Za-z0-9-]+/checks"))return "agents";
  if(path.equals("/api/v1/maintenance/sync-checks")||path.matches("/api/v1/maintenance/sync-checks/[A-Za-z0-9-]+")||path.matches("/api/v1/maintenance/sync-checks/devices/[A-Za-z0-9_-]+/capabilities"))return "resources";
  if(path.equals("/api/v1/execution-resources")||path.equals("/api/v1/execution-resources/devices")||path.equals("/api/v1/execution-placement/preview"))return "resources";
  if(path.matches("/api/v1/execution-(projects|pools|profiles)(?:/[A-Za-z0-9-]+/(enabled|members))?")||path.equals("/api/v1/execution-grants")||path.equals("/api/v1/execution-routes"))return "resources";
  if(path.startsWith("/api/v1/tasks"))return "tasks";
  if(path.matches("/api/v1/agent-definitions/[^/]+/scheduling-policy"))return "scheduling";
  if(path.matches("/api/v1/agent-definitions/[^/]+/skills.*"))return "integrations";
  if(path.equals("/api/v1/agent-definitions/page")||path.equals("/api/v1/agent-definitions"))return "agents";
  throw ApiException.forbidden(); // Node execution and legacy credential administration are never exposed.
 }
}
