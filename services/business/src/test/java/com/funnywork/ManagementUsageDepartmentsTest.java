package com.funnywork;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementUsageDepartmentsTest {
 JdbcTemplate db;ManagementIdentity identity;ObjectMapper mapper=new ObjectMapper();Fixture service;Identity actor;
 class Fixture extends ManagementUsageDepartments {
  JsonNode response;Set<String> requested;boolean fail;
  Fixture(){super(db,identity,mapper);}
  @Override JsonNode fetch(String token,Set<String> users)throws Exception{assertEquals("session",token);requested=Set.copyOf(users);if(fail)throw new java.io.IOException("private connection detail");return response;}
 }
 @BeforeEach void setup(){
  db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
  db.execute("create table principals(id varchar(64),tenant_id varchar(64))");db.execute("create table management_identity_links(principal_id varchar(64),ruoyi_tenant varchar(64),ruoyi_user varchar(64))");
  db.update("insert into principals values('admin','business'),('member','business'),('local','business'),('foreign','different')");
  db.update("insert into management_identity_links values('admin','identity','1'),('member','identity','2'),('foreign','identity','3')");
  actor=new Identity("admin","business","USER",Set.of("ADMIN"),Set.of("sandbox"),1);identity=mock(ManagementIdentity.class);
  when(identity.authority("session")).thenReturn(new ManagementIdentity.Authority("identity","1",Set.of("funnyworker:usage:read")));
  service=new Fixture();
 }
 Map<String,Object> page(){return Map.of("items",List.of(Map.of("id","member","tasks",2),Map.of("id","local","tasks",1)),"summary",Map.of("tasks",3));}
 JsonNode response(String tenant,List<Map<String,Object>> items){return mapper.valueToTree(Map.of("tenantId",tenant,"observedAt",123L,"items",items));}
 Map<String,Object> mapped(String id){return Map.of("userId",id,"state","MAPPED","departmentId","10","departmentName","Engineering");}
 @Test void enrichesOnlyBoundSubjectsAndKeepsUnknownMembersDistinct(){
  service.response=response("identity",List.of(mapped("2")));var original=page();var value=service.enrich(actor,"session",original);var rows=(List<?>)value.get("items");
  assertEquals(Set.of("2"),service.requested);assertEquals("Engineering",((Map<?,?>)rows.get(0)).get("departmentName"));assertEquals("UNAVAILABLE",((Map<?,?>)rows.get(1)).get("departmentState"));assertEquals("PARTIAL",value.get("departmentCoverage"));assertEquals(original.get("summary"),value.get("summary"));assertFalse(((Map<?,?>)((List<?>)original.get("items")).get(0)).containsKey("departmentState"));
 }
 @Test void rejectsForeignDuplicateAndUnrequestedMetadataWithoutPartialAttribution(){
  for(var response:List.of(response("other",List.of(mapped("2"))),response("identity",List.of(mapped("99"))),response("identity",List.of(mapped("2"),mapped("2"))),response("identity",List.of()))){service.response=response;var value=service.enrich(actor,"session",page());assertEquals("UNAVAILABLE",value.get("departmentCoverage"));for(var row:(List<?>)value.get("items"))assertFalse(((Map<?,?>)row).containsKey("departmentName"));}
 }
 @Test void failuresMissingPermissionAndWrongCallerDoNotGuessDepartments(){
  service.fail=true;assertEquals("UNAVAILABLE",service.enrich(actor,"session",page()).get("departmentCoverage"));service.requested=null;
  assertEquals("UNAVAILABLE",service.enrich(actor,null,page()).get("departmentCoverage"));assertNull(service.requested);
  when(identity.authority("session")).thenReturn(new ManagementIdentity.Authority("identity","1",Set.of("funnyworker:tasks:read")));service.enrich(actor,"session",page());assertNull(service.requested);
  when(identity.authority("session")).thenReturn(new ManagementIdentity.Authority("identity","9",Set.of("*:*:*")));service.enrich(actor,"session",page());assertNull(service.requested);
 }
 @Test void unassignedIsKnownAndDoesNotBecomeUnavailable(){
  service.response=response("identity",List.of(Map.of("userId","2","state","UNASSIGNED")));var value=service.enrich(actor,"session",Map.of("items",List.of(Map.of("id","member","tasks",2))));assertEquals("COMPLETE",value.get("departmentCoverage"));assertEquals("UNASSIGNED",((Map<?,?>)((List<?>)value.get("items")).get(0)).get("departmentState"));
 }
}
