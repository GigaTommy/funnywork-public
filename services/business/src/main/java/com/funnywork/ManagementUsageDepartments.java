package com.funnywork;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Enrich only the already-authorized activity page with current identity metadata. */
@Service
public class ManagementUsageDepartments {
 private final JdbcTemplate db;private final ManagementIdentity identity;private final ObjectMapper mapper;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public ManagementUsageDepartments(JdbcTemplate db,ManagementIdentity identity,ObjectMapper mapper){this.db=db;this.identity=identity;this.mapper=mapper;}
 JsonNode fetch(String token,Set<String> users)throws Exception{
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767/funnyworker/usage/members")).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+token).header("clientid","e5cd7e4891bf95d1d19206ce24a7b32e").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(Map.of("userIds",users)))).build();
  var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());byte[] bytes=response.body();
  if(response.statusCode()!=200||bytes.length>65536)throw new IllegalStateException("Identity metadata unavailable");
  var body=mapper.readTree(bytes);if(body.path("code").asInt()!=200)throw new IllegalStateException("Identity metadata unavailable");return body.path("data");
 }
 public Map<String,Object> enrich(Identity actor,String token,Map<String,Object> page){
  var result=new LinkedHashMap<>(page);var items=new ArrayList<Map<String,Object>>();
  for(var value:(List<?>)page.get("items")){var row=new LinkedHashMap<String,Object>();((Map<?,?>)value).forEach((key,item)->row.put((String)key,item));row.put("departmentState","UNAVAILABLE");items.add(row);}
  result.put("items",items);result.put("departmentBasis","CURRENT_DEPARTMENT");result.put("departmentCoverage","UNAVAILABLE");
  if(token==null||items.isEmpty())return result;
  try{
   var remote=identity.authority(token);
   if(!remote.permissions().contains("*:*:*")&&!remote.permissions().contains("funnyworker:usage:read"))return result;
   if(db.queryForObject("select count(*) from management_identity_links where principal_id=? and ruoyi_tenant=? and ruoyi_user=?",Integer.class,actor.id(),remote.tenant(),remote.user())!=1)return result;
   var ids=items.stream().map(row->(String)row.get("id")).toList();
   if(ids.size()>100)throw new IllegalArgumentException("Activity page too large");
   var params=new ArrayList<Object>(List.of(remote.tenant(),actor.tenant()));params.addAll(ids);
   var links=db.queryForList("select l.principal_id,l.ruoyi_user from management_identity_links l join principals p on p.id=l.principal_id where l.ruoyi_tenant=? and p.tenant_id=? and l.principal_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",params.toArray());
   var users=new LinkedHashSet<String>();var owners=new HashMap<String,String>();for(var link:links){users.add((String)link.get("ruoyi_user"));owners.put((String)link.get("principal_id"),(String)link.get("ruoyi_user"));}
   if(users.isEmpty())return result;
   var data=fetch(token,users);
   if(!remote.tenant().equals(data.path("tenantId").asText())||!data.path("items").isArray()||data.path("items").size()!=users.size()||!data.path("observedAt").canConvertToLong())throw new IllegalStateException("Invalid metadata response");
   var metadata=new HashMap<String,JsonNode>();
   for(var row:data.path("items")){
    String id=row.path("userId").asText(),state=row.path("state").asText();
    if(!users.contains(id)||metadata.put(id,row)!=null||!Set.of("MAPPED","UNASSIGNED","UNAVAILABLE").contains(state))throw new IllegalStateException("Invalid metadata subject");
    if(state.equals("MAPPED")&&(!row.path("departmentId").asText().matches("[0-9]{1,19}")||!row.path("departmentName").isTextual()||row.path("departmentName").asText().length()>200))throw new IllegalStateException("Invalid department");
   }
   for(var item:items){var row=metadata.get(owners.get(item.get("id")));if(row==null)continue;item.put("departmentState",row.path("state").asText());if(row.path("state").asText().equals("MAPPED")){item.put("departmentId",row.path("departmentId").asText());item.put("departmentName",row.path("departmentName").asText());}}
   result.put("departmentCoverage",items.stream().allMatch(row->!"UNAVAILABLE".equals(row.get("departmentState")))?"COMPLETE":"PARTIAL");result.put("departmentObservedAt",data.path("observedAt").longValue());
  }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception unavailable){/* Activity remains valid; unavailable identity data is never guessed. */}
  return result;
 }
}
