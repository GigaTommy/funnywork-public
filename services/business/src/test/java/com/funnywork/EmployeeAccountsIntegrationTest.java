package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:employee-accounts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class EmployeeAccountsIntegrationTest {
 @Autowired AuthService auth;@Autowired EmployeeAccounts accounts;@Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired Json json;
 @Test void unauthenticatedProtectedRequestsAreRejectedBeforeReadingTheirBodies() throws Exception {
  for(String path:List.of("/api/account/inference/tickets/test/execute","/api/v1/artifacts","/api/account/sync/devices/test/state")){
   http.perform(context->{
    var request=new org.springframework.mock.web.MockHttpServletRequest(context){
     @Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Unauthenticated body must not be buffered");}
    };
    request.setMethod("POST");request.setRequestURI(path);request.setServletPath(path);request.setContentType("application/json");
    return request;
   }).andExpect(status().isUnauthorized());
  }
 }
 @Test void androidHttpLoginUsesMobileLifetimeAndRemainsRevocable() throws Exception {
  String suffix=UUID.randomUUID().toString(), adminToken=auth.newToken();
  auth.create("admin-"+suffix,"tenant-"+suffix,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),adminToken);
  Identity admin=auth.authenticate(adminToken);
  var added=auth.addUser(admin,"Android",Set.of("VIEWER"),Set.of("sandbox"));
  String username="android-"+suffix;
  accounts.password(admin,(String)added.get("principalId"),username,"test-password-1234");
  String body=json.write(Map.of("username",username,"password","test-password-1234","device","Android test","client","android"));
  var result=http.perform(post("/api/account/login").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
  var login=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
  assertTrue(login.path("expires").asLong()-System.currentTimeMillis()/1000>=29*86400L);
  String token=login.path("token").asText();
  http.perform(get("/api/account/session").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.principalId").value(added.get("principalId")));
  http.perform(post("/api/account/logout").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isOk());
  assertThrows(ApiException.class,()->auth.authenticate(token));
  http.perform(post("/api/account/login").contentType("application/json").content(json.write(Map.of("username",username,"password","test-password-1234","device","Android test","client","unknown")))).andExpect(status().isBadRequest());
 }
 @Test void passwordSessionSharesPrincipalPermissionsAndRevocation() throws Exception {
  String suffix=UUID.randomUUID().toString();String token=auth.newToken();
  auth.create("admin-"+suffix,"tenant-"+suffix,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),token);
  Identity admin=auth.authenticate(token);
  var added=auth.addUser(admin,"Employee",Set.of("VIEWER"),Set.of("sandbox"));String principal=(String)added.get("principalId");
  accounts.password(admin,principal,"employee-"+suffix,"test-password-1234");
  assertThrows(ApiException.class,()->auth.authenticate((String)added.get("token")));
  var login=accounts.login("employee-"+suffix,"test-password-1234","desktop");String session=(String)login.get("token");
  Identity employee=auth.authenticate(session);assertEquals(principal,employee.id());assertEquals(Set.of("VIEWER"),employee.roles());
  http.perform(get("/api/account/session").header("Authorization","Bearer "+session)).andExpect(status().isOk());
  http.perform(put("/api/v1/principals/"+principal+"/password").header("Authorization","Bearer "+session).contentType("application/json").content("{\"username\":\"attack\",\"password\":\"test-password-1234\"}")).andExpect(status().isForbidden());
  accounts.password(admin,principal,"employee-"+suffix,"replacement-password-1234");
  http.perform(get("/api/account/session").header("Authorization","Bearer "+session)).andExpect(status().isUnauthorized());
  var replacement=accounts.login("employee-"+suffix,"replacement-password-1234","desktop");
  http.perform(post("/api/account/logout").header("Authorization","Bearer "+replacement.get("token")).contentType("application/json").content("{}")).andExpect(status().isOk());
  assertThrows(ApiException.class,()->auth.authenticate((String)replacement.get("token")));
  assertFalse(db.queryForObject("select password_hash from employee_passwords where principal_id=?",String.class,principal).contains("replacement-password"));
 }
 @Test void accountPicturePersistsAcrossDevicesAndCannotCrossUsers()throws Exception{
  String id=auth.id(),token=auth.newToken();auth.create("avatar-"+id,"tenant-"+id,"USER","Gigi",Set.of("ADMIN"),Set.of("sandbox"),token);var actor=auth.authenticate(token);
  var added=auth.addUser(actor,"Gigi",Set.of("VIEWER"),Set.of("sandbox"));String owner=(String)added.get("principalId");accounts.password(actor,owner,"avatar-user-"+id,"avatar-password-1234");
  String desktop=(String)accounts.login("avatar-user-"+id,"avatar-password-1234","desktop").get("token");String mobile=(String)accounts.login("avatar-user-"+id,"avatar-password-1234","ios",true).get("token");
  var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
  String image="data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray());
  http.perform(post("/api/account/avatar").header("Authorization","Bearer "+desktop).contentType("application/json").content(json.write(Map.of("image",image)))).andExpect(status().isOk());
  http.perform(get("/api/account/session").header("Authorization","Bearer "+mobile)).andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value(image));
  http.perform(get("/api/account/session").header("Authorization","Bearer "+desktop)).andExpect(jsonPath("$.avatarUrl").value(image));
  http.perform(post("/api/account/avatar").header("Authorization","Bearer "+mobile).contentType("application/json").content("{\"image\":\"data:image/png;base64,AAAA\"}")).andExpect(status().isBadRequest());
  http.perform(post("/api/account/avatar").contentType("application/json").content(json.write(Map.of("image",image)))).andExpect(status().isUnauthorized());
  assertEquals(0,db.queryForObject("select count(*) from account_avatars where principal_id=?",Integer.class,actor.id()));
 }
 @org.junit.jupiter.api.Test void managementFailureNeverFallsBackToLocalPasswords(){
  var local=org.mockito.Mockito.mock(EmployeeAccounts.class);
  var management=org.mockito.Mockito.mock(ManagementDesktopAccounts.class);
  org.mockito.Mockito.when(management.enabled()).thenReturn(true);
  var controller=new EmployeeAccountController(local,org.mockito.Mockito.mock(AuthService.class),management,org.mockito.Mockito.mock(MobilePushSubscriptions.class),org.mockito.Mockito.mock(AccountAvatars.class));
  for(int status:new int[]{401,503}){
   org.mockito.Mockito.doThrow(new ApiException(status,"UNAUTHENTICATED","Denied")).when(management).login("desktop-check","password","device",AccountClient.DESKTOP);
   org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,()->controller.login(new EmployeeAccountController.Login("desktop-check","password","device",null)));
  }
  org.mockito.Mockito.verifyNoInteractions(local);
 }
 @Test void mobileSessionIsBoundedAndLogoutStillRevokes()throws Exception{
  String suffix=auth.id(),token=auth.newToken();auth.create("admin-"+suffix,"tenant-"+suffix,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),token);var admin=auth.authenticate(token);
  var added=auth.addUser(admin,"Mobile",Set.of("VIEWER"),Set.of("sandbox"));accounts.password(admin,(String)added.get("principalId"),"mobile-"+suffix,"test-password-1234");
  long now=System.currentTimeMillis()/1000;var mobile=accounts.login("mobile-"+suffix,"test-password-1234","iOS",true);var desktop=accounts.login("mobile-"+suffix,"test-password-1234","desktop");
  assertTrue(((Number)mobile.get("expires")).longValue()-now>=29*86400);assertTrue(((Number)desktop.get("expires")).longValue()-now<9*3600);
  http.perform(post("/api/account/logout").header("Authorization","Bearer "+mobile.get("token")).contentType("application/json").content("{}")).andExpect(status().isOk());assertThrows(ApiException.class,()->auth.authenticate((String)mobile.get("token")));assertNotNull(auth.authenticate((String)desktop.get("token")));
 }

 @Test void successfulLoginGrantsWelcomeOnlyOnceAndBadPasswordGrantsNothing(){
  String suffix=auth.id(),token=auth.newToken();auth.create("admin-"+suffix,"tenant-"+suffix,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),token);var admin=auth.authenticate(token);
  var added=auth.addUser(admin,"Promotion",Set.of("VIEWER"),Set.of("sandbox"));String owner=(String)added.get("principalId"),username="promo-"+suffix;
  accounts.password(admin,owner,username,"test-password-1234");
  db.update("update model_promotion_policy set tenant_id=?,mode='WELCOME',issued=0,total_limit=1000000,daily_limit=1000000 where id=1",admin.tenant());
  assertThrows(ApiException.class,()->accounts.login(username,"incorrect","desktop"));assertEquals(0,db.queryForObject("select count(*) from model_user_budgets where owner_id=?",Integer.class,owner));
  db.update("update employee_passwords set retry_after=0 where principal_id=?",owner);
  accounts.login(username,"test-password-1234","desktop");accounts.login(username,"test-password-1234","ios",true);
  assertEquals(1000000,db.queryForObject("select total_limit from model_user_budgets where owner_id=?",Long.class,owner));
  assertEquals(1,db.queryForObject("select count(*) from model_allowance_credits where owner_id=?",Integer.class,owner));
  db.update("update model_promotion_policy set mode='DISABLED' where id=1");
 }
}
