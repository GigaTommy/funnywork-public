package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=${FUNNYWORK_TEST_DB_URL:jdbc:h2:mem:published-skills;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${FUNNYWORK_TEST_DB_USER:sa}","spring.datasource.password=${FUNNYWORK_TEST_DB_PASSWORD:}","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class PublishedSkillsIntegrationTest {
 @Autowired PublishedSkills skills;@Autowired ControlService control;@Autowired AuthService auth;
 Identity admin,owner,other,node;String agent;
 @BeforeEach void setup(){String tenant=AuthService.id();admin=user(tenant,"ADMIN");owner=user(tenant,"OPERATOR");other=user(AuthService.id(),"ADMIN");agent=(String)control.publishAgent(admin,new Requests.Agent("skills","1.0.0","model.read.v1")).get("agentId");var registration=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));node=auth.authenticate((String)registration.get("token"));}
 Identity user(String tenant,String role){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),token);return auth.authenticate(token);}
 String publish(String version){return (String)skills.publish(admin,new PublishedSkills.Publish("office",version,"sandbox","Use authorized tools, summarize source evidence.")).get("id");}
 @Test void immutableVersionSnapshotDigestAndRevocation(){String first=publish("1.0.0");skills.bind(admin,agent,first,true);control.createTask(owner,AuthService.id(),new Requests.Task(agent,"sandbox",List.of("summarize")));skills.bind(admin,agent,first,false);String second=publish("2.0.0");skills.bind(admin,agent,second,true);
  control.heartbeat(node,true);control.tick();var work=control.poll(node);String attempt=(String)work.get("attemptId");long epoch=((Number)work.get("epoch")).longValue();var catalog=skills.forAttempt(node,attempt,epoch);assertEquals(1,catalog.size());assertEquals(first,catalog.get(0).get("id"));assertEquals(AuthService.hash((String)catalog.get(0).get("instructions")),catalog.get(0).get("content_hash"));
  assertThrows(ApiException.class,()->skills.forAttempt(node,attempt,epoch+1));skills.disable(admin,first);assertTrue(skills.forAttempt(node,attempt,epoch).isEmpty());
 }
 @Test void rolesTenantsAndByteBudget(){String id=publish("1.0.0");assertEquals(0,((List<?>)skills.list(other,"sandbox","",50).get("items")).size());assertEquals(403,assertThrows(ApiException.class,()->skills.list(owner,"sandbox","",50)).status);assertEquals(404,assertThrows(ApiException.class,()->skills.bind(other,agent,id,true)).status);assertEquals(413,assertThrows(ApiException.class,()->skills.publish(admin,new PublishedSkills.Publish("oversized","1.0.0","sandbox","中".repeat(3000)))).status);
  skills.disable(admin,id);assertEquals(403,assertThrows(ApiException.class,()->skills.bind(admin,agent,id,true)).status);
 }
}
