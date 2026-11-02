package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:management-bridge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=management-test-bootstrap-012345678901234567890","funnywork.scheduling-enabled=false"})
class ManagementIdentityIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired org.springframework.test.web.servlet.MockMvc http;
 @MockitoSpyBean ManagementIdentity management;
 @BeforeEach void bind(){db.update("delete from management_identity_links");db.update("insert into management_identity_links values('000000','1','bootstrap-admin')");}
 @Test void boundClientCanSaveOwnPictureWithoutManagementWritePermission()throws Exception{
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"),"Gigi","","Gigi")).when(management).authority("avatar-client");
  var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
  String image="data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(output.toByteArray());
  String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("image",image));
  http.perform(post("/api/account/avatar").header("Authorization","Bearer ruoyi.avatar-client").contentType("application/json").content(body)).andExpect(status().isOk());
  http.perform(get("/api/account/session").header("Authorization","Bearer ruoyi.avatar-client")).andExpect(jsonPath("$.avatarUrl").value(image));
  doReturn(new ManagementIdentity.Authority("other-tenant","1",Set.of("funnyworker:desktop:read"))).when(management).authority("avatar-client");
  http.perform(post("/api/account/avatar").header("Authorization","Bearer ruoyi.avatar-client").contentType("application/json").content(body)).andExpect(status().isForbidden());
  db.update("delete from account_avatars");
 }
 @Test void historyContentPermissionIsSeparateAndRecheckedOnEveryHttpRequest() throws Exception {
  String chat=AuthService.id();db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,'demo','bootstrap-admin','private-title','model',0,1,1)",chat);
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:audit:read"))).when(management).authority("history-session");
  http.perform(get("/api/v1/management-history").header("Authorization","Bearer ruoyi.history-session")).andExpect(status().isForbidden());
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:history:read"))).when(management).authority("history-session");
  http.perform(get("/api/v1/management-history").header("Authorization","Bearer ruoyi.history-session")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  String path="/api/v1/management-history/MOBILE/"+chat+"/content";
  http.perform(get(path).header("Authorization","Bearer ruoyi.history-session")).andExpect(status().isForbidden());
  http.perform(get(java.net.URI.create("/api/v1/management-history/%4dOBILE/"+chat+"/content")).header("Authorization","Bearer ruoyi.history-session")).andExpect(status().is4xxClientError());
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:history:read","funnyworker:history:content"))).when(management).authority("history-session");
  http.perform(get(path).header("Authorization","Bearer ruoyi.history-session")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:history:read"))).when(management).authority("history-session");
  http.perform(get(path).header("Authorization","Bearer ruoyi.history-session")).andExpect(status().isForbidden());
 }
 @Test void exactPermissionAndExplicitScopeAreBothRequired(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:nodes:read"))).when(management).authority("session");
  assertEquals("demo",management.authenticate("session","GET","/api/v1/nodes").tenant());
  assertThrows(ApiException.class,()->management.authenticate("session","PUT","/api/v1/nodes/example/admission"));
  assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/v1/model-profiles"));
  doReturn(new ManagementIdentity.Authority("other","1",Set.of("*:*:*"))).when(management).authority("session");
  assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/v1/nodes"));
 }
 @Test void modelDeliveryUsesDesktopReadPermissionAndBusinessScope(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  assertEquals("demo",management.authenticate("session","GET","/api/account/model/signer").tenant());
  assertEquals("demo",management.authenticate("session","POST","/api/account/model/publication").tenant());
  assertEquals("demo",management.authenticate("session","GET","/api/account/policy").tenant());
  assertEquals("demo",management.authenticate("session","POST","/api/account/policy/receipt").tenant());
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:models:read"))).when(management).authority("session");
  assertThrows(ApiException.class,()->management.authenticate("session","POST","/api/account/model/publication"));
 }
 @Test void combinedStartupUsesDesktopReadPermissionAndRequiresBoundIdentity() throws Exception {
  String body="{\"modelDevice\":\"management-startup\",\"policyDevice\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer ruoyi.session").contentType("application/json").content(body))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.schema").value(1));
  int credentials=db.queryForObject("select count(*) from model_entry_credentials",Integer.class);
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:models:read"))).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer ruoyi.session").contentType("application/json").content(body))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DESKTOP_PERMISSION_REQUIRED"));
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  db.update("delete from management_identity_links");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/startup").header("Authorization","Bearer ruoyi.session").contentType("application/json").content(body))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("BUSINESS_SCOPE_REQUIRED"));
  assertEquals(credentials,db.queryForObject("select count(*) from model_entry_credentials",Integer.class));
 }
 @Test void desktopLoginReportsMissingPermissionAndBindingSeparately(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of())).when(management).authority("session");
  var permission=assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/account/session"));
  assertEquals(403,permission.status);
  assertEquals("DESKTOP_PERMISSION_REQUIRED",permission.code);
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  db.update("delete from management_identity_links");
  var binding=assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/account/session"));
  assertEquals("BUSINESS_SCOPE_REQUIRED",binding.code);
 }
 @Test void unrelatedExecutionAndCredentialRoutesRemainDenied(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("*:*:*"))).when(management).authority("session");
  for(String path:new String[]{"/api/v1/nodes/poll","/api/v1/attempts/abc/result","/api/v1/users","/api/v1/auth/rotate","/api/v1/principals/abc/password"})assertThrows(ApiException.class,()->management.authenticate("session","POST",path));
 }
 @Test void localBusinessRevocationTakesEffectOnNextRequest(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("*:*:*"))).when(management).authority("session");
  assertNotNull(management.authenticate("session","GET","/api/v1/nodes"));
  db.update("delete from management_identity_links");
  assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/v1/nodes"));
 }
 @Test void desktopTokenPrefixUsesTheSameBoundIdentity() throws Exception {
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("*:*:*"),"admin","研发部","小明")).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/account/session").header("Authorization","Bearer ruoyi.session"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.username").value("admin"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.department").value("研发部"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.nickname").value("小明"));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/nodes/poll").header("Authorization","Bearer ruoyi.session"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
 }

 @Test void memberBindingUsesIdentityTenantAndEnforcesReadPermission() throws Exception {
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:authorization:read"))).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/management-bindings/1").header("Authorization","Bearer session").header("X-FunnyWorker-Management","1"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.configured").value(true));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/management-bindings/987").header("Authorization","Bearer session").header("X-FunnyWorker-Management","1"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.configured").value(false));
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:nodes:read"))).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/management-bindings/1").header("Authorization","Bearer session").header("X-FunnyWorker-Management","1"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
 }

 @Test void phoneChatUsesExistingClientPermissionForReadAndSend(){
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  for(String path:java.util.List.of("/api/account/chats","/api/account/chats/models","/api/account/chats/chat/turns","/api/account/chats/chat/turns/turn/execute","/api/account/chats/chat/turns/turn/cancel"))for(String method:java.util.List.of("GET","POST"))assertEquals("demo",management.authenticate("session",method,path).tenant());
  assertThrows(ApiException.class,()->management.authenticate("session","GET","/api/account/chatsevil"));
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of())).when(management).authority("session");assertThrows(ApiException.class,()->management.authenticate("session","POST","/api/account/chats/chat/turns"));
 }
 @Test void clientHeaderSelectorOnlyAcceptsKnownMobileClient(){
  String payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"clientid\":\""+ManagementIdentity.MOBILE_CLIENT+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(ManagementIdentity.MOBILE_CLIENT,ManagementIdentity.clientId("header."+payload+".signature"));assertEquals(ManagementIdentity.DESKTOP_CLIENT,ManagementIdentity.clientId("invalid"));
  String unknown=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"clientid\":\"unknown\"}".getBytes());assertEquals(ManagementIdentity.DESKTOP_CLIENT,ManagementIdentity.clientId("header."+unknown+".signature"));
 }

 @Test void ruoyiAccountCanLoadMobileChatModelsThroughHttp()throws Exception{
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:desktop:read"))).when(management).authority("session");
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/account/chats/models").header("Authorization","Bearer ruoyi.session"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items").isArray());
 }

 @Test void runtimeRepairRegistrationUsesExistingAgentWritePermissionOnly()throws Exception{
  String path="/api/v1/maintenance/automation/problems/missing-problem/runtime-repair";
  String body="{\"expectedVersion\":4,\"evidenceDigest\":\""+"a".repeat(64)+"\"}";
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:agents:read"))).when(management).authority("repair-reader");
  http.perform(post(path).header("Authorization","Bearer ruoyi.repair-reader").contentType("application/json").content(body)).andExpect(status().isForbidden());
  doReturn(new ManagementIdentity.Authority("000000","1",Set.of("funnyworker:agents:write"))).when(management).authority("repair-writer");
  http.perform(post(path).header("Authorization","Bearer ruoyi.repair-writer").contentType("application/json").content(body)).andExpect(status().isNotFound());
  assertEquals("funnyworker:agents:write",ManagementIdentity.permission("POST",path));
  assertThrows(ApiException.class,()->ManagementIdentity.module(path+"/approve"));
 }

 @Test void taskAttributionAndScopeConfigurationHaveIndependentPermissions(){
  assertEquals("funnyworker:task-center:scope",ManagementIdentity.permission("GET","/api/v1/management-tasks/attribution-members"));
  assertEquals("funnyworker:task-center:scope",ManagementIdentity.permission("GET","/api/v1/management-tasks/member-contexts/member"));
  assertEquals("funnyworker:task-center:scope",ManagementIdentity.permission("PUT","/api/v1/management-tasks/member-contexts/member"));
  assertEquals("funnyworker:task-center:read",ManagementIdentity.permission("GET","/api/v1/management-tasks/task"));
  assertEquals("funnyworker:task-center:write",ManagementIdentity.permission("POST","/api/v1/management-tasks/task/commands"));
 }

 @Test void taskInputAndArtifactsDoNotShareMetadataOrWritePermissions(){
  assertEquals("funnyworker:task-center:write",ManagementIdentity.permission("GET","/api/v1/management-tasks/batches/key"));
  assertEquals("funnyworker:task-center:write",ManagementIdentity.permission("POST","/api/v1/management-tasks/batch-preview"));
  assertEquals("funnyworker:task-center:content",ManagementIdentity.permission("GET","/api/v1/management-tasks/task/units/unit/input"));
  assertEquals("funnyworker:task-center:artifacts",ManagementIdentity.permission("GET","/api/v1/management-tasks/task/artifacts"));
  assertEquals("funnyworker:task-center:artifacts",ManagementIdentity.permission("GET","/api/v1/management-tasks/task/artifacts/blob/1"));
  assertEquals("funnyworker:task-center:scope",ManagementIdentity.permission("PUT","/api/v1/management-tasks/content-access/admin"));
  assertEquals("funnyworker:task-center:read",ManagementIdentity.permission("GET","/api/v1/management-tasks/task/content-capabilities"));
 }

}
