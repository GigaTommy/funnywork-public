package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
@Service
public class ManagementDesktopAccounts {
 private ModelBudgets budgets;
 @org.springframework.beans.factory.annotation.Autowired void promotionalBudgets(ModelBudgets budgets){this.budgets=budgets;}
 private final boolean enabled;private final String tenant;private final ManagementIdentity identity;private final ObjectMapper mapper;private final AuthService auth;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 public ManagementDesktopAccounts(@Value("${FUNNYWORK_RUOYI_ACCOUNT_ENABLED:false}") boolean enabled,@Value("${FUNNYWORK_RUOYI_DESKTOP_TENANT:000000}") String tenant,ManagementIdentity identity,ObjectMapper mapper,AuthService auth){this.enabled=enabled;this.tenant=tenant;this.identity=identity;this.mapper=mapper;this.auth=auth;}
 public boolean enabled(){return enabled;}
 public Map<String,Object> login(String username,String password,String device){return login(username,password,device,false);}
 public Map<String,Object> login(String username,String password,String device,boolean mobile){
  return login(username,password,device,mobile?AccountClient.IOS:AccountClient.DESKTOP);
 }
 public Map<String,Object> login(String username,String password,String device,AccountClient accountClient){
  try {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767/auth/login")).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json")
    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("tenantId",tenant,"username",username,"password",password,"grantType","password","clientId",accountClient.identityClient())))).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()>=500)throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");
   var body=mapper.readTree(response.body());
   if(body.path("code").asInt()==503)throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");
   if(response.statusCode()!=200||body.path("code").asInt()!=200)throw new ApiException(401,"UNAUTHENTICATED","账号或密码不正确");
   String token=body.path("data").path("access_token").asText();if(token.isBlank()||token.length()>16000)throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");
   Identity actor;
   try{actor=identity.authenticate(token,"GET","/api/account/session");}catch(ApiException e){logout(token);throw e;}
   auth.audit(actor,"MANAGEMENT_DESKTOP_LOGIN",Map.of("device",device));
   if(budgets!=null)budgets.claim(actor);
   return Map.of("token","ruoyi."+token,"username",username,"device",device,"expires",Instant.now().getEpochSecond()+Math.max(0,body.path("data").path("expire_in").asLong()));
  }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}catch(Exception e){throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}
 }
 public Map<String,Object> session(Identity actor,String token){var remote=identity.authority(token);return Map.of("username",remote.username(),"nickname",remote.nickname(),"tenant",actor.tenant(),"roles",actor.roles(),"department",remote.department());}
 public void logout(String token){
  try{
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767/auth/logout")).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+token).header("clientid",ManagementIdentity.clientId(token)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200||mapper.readTree(response.body()).path("code").asInt()!=200)throw new ApiException(503,"LOGOUT_UNCONFIRMED","退出结果尚未确认");
  }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}catch(Exception e){throw new ApiException(503,"IDENTITY_UNAVAILABLE","身份服务暂时不可用");}
 }
}
