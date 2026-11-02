package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:gateway-checks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.gateway-hosts=gateway.invalid","FUNNYWORK_GATEWAY_FIXTURE=private-fixture-value"})
@AutoConfigureMockMvc
class ManagedGatewaysIntegrationTest {
 @Autowired ManagedGateways gateways;@Autowired ToolRegistry tools;@Autowired AuthService auth;@Autowired Json json;@Autowired MockMvc http;
 Identity admin;String secret,tool,hash;
 @BeforeEach void setup(){
  secret=AuthService.id();String tenant=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),secret);admin=auth.authenticate(secret);
  var published=tools.publish(admin,new ToolRegistry.Publish(new ToolContract("fixture.write",ToolContract.Effect.IDEMPOTENT_WRITE,Map.of(),"vault://private/fixture","receipt"),"1.0.0","sandbox"));tool=(String)published.get("toolVersionId");hash=(String)published.get("contractHash");
 }
 ManagedGateways.Config config(String credential,int version,boolean enabled){return new ManagedGateways.Config("fixture","Fixture","sandbox","https://gateway.invalid/",credential,hash,enabled,version);}
 @Test void configurationCheckDoesNotClaimRemoteHealthOrExposeCredentials() throws Exception {
  gateways.save(admin,config("FUNNYWORK_GATEWAY_FIXTURE",0,true));var result=gateways.check(admin,"fixture",1);
  assertEquals(true,result.get("configurationReady"));assertEquals("UNVERIFIED",result.get("remoteStatus"));assertFalse(json.write(result).contains("private-fixture-value"));assertFalse(json.write(result).contains("vault://"));
  http.perform(post("/api/v1/managed-gateways/fixture/check").param("version","1").header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.remoteStatus").value("UNVERIFIED"));
  tools.disable(admin,tool);assertEquals(false,gateways.check(admin,"fixture",1).get("contractAvailable"));
  assertThrows(ApiException.class,()->gateways.check(admin,"fixture",0));
  gateways.save(admin,config("FUNNYWORK_GATEWAY_FIXTURE",1,false));assertThrows(ApiException.class,()->gateways.check(admin,"fixture",1));assertEquals(false,gateways.check(admin,"fixture",2).get("configurationReady"));
 }
 @Test void missingCredentialAndScopeAreReportedWithoutNetworkCalls(){
  gateways.save(admin,config("FUNNYWORK_GATEWAY_MISSING",0,true));var value=gateways.check(admin,"fixture",1);assertEquals(false,value.get("credentialPresent"));assertEquals(false,value.get("configurationReady"));
  for(String[] scope:List.of(new String[]{admin.tenant(),"VIEWER","sandbox"},new String[]{admin.tenant(),"ADMIN","other"},new String[]{AuthService.id(),"ADMIN","sandbox"})){
   String token=AuthService.id();auth.create(AuthService.id(),scope[0],"USER","Other",Set.of(scope[1]),Set.of(scope[2]),token);assertThrows(ApiException.class,()->gateways.check(auth.authenticate(token),"fixture",1));
  }
  auth.disable(admin,admin.id());assertThrows(ApiException.class,()->gateways.check(admin,"fixture",1));
 }
 @Test void contractSelectionContainsOnlyScopedEnabledWriteVersions(){
  tools.publish(admin,new ToolRegistry.Publish(new ToolContract("fixture.read",ToolContract.Effect.READ_ONLY,Map.of(),"vault://read","read"),"1.0.0","sandbox"));
  var ids=new ArrayList<String>();String after="";do{var page=gateways.contracts(admin,"sandbox",after,1);for(var value:(List<?>)page.get("items")){var row=(Map<?,?>)value;ids.add((String)row.get("id"));assertEquals(hash,row.get("contractHash"));assertFalse(row.containsKey("contract_json"));}after=(String)page.getOrDefault("nextCursor","");}while(!after.isEmpty());
  assertEquals(List.of(tool),ids);assertThrows(ApiException.class,()->gateways.contracts(admin,"other","",50));
  tools.disable(admin,tool);assertEquals(List.of(),gateways.contracts(admin,"sandbox","",50).get("items"));
 }
}
