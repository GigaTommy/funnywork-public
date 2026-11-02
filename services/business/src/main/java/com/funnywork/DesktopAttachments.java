package com.funnywork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.util.*;

/** Account-isolated content-addressed chunks; incomplete or corrupt uploads are never downloadable. */
@Service
public class DesktopAttachments {
 public record Chunk(String deviceId,long offset,String data) {}
 private final JdbcTemplate db;private final AuthService auth;private final ObjectMapper json;private final long maxBytes;
 @Value("${funnywork.sync.max-account-attachment-bytes:1073741824}") private long maxAccountBytes=1073741824L;
 @Value("${funnywork.sync.max-account-attachment-objects:10000}") private int maxAccountObjects=10000;
 @jakarta.annotation.PostConstruct void validateCapacity(){if(maxAccountBytes<maxBytes||maxAccountObjects<1)throw new IllegalArgumentException("Invalid account attachment capacity");}
 public Map<String,Object> capabilities(Identity actor){actor(actor);return Map.of("resultSchema",1,"formats",List.of("docx","xlsx","pptx","png"),"maxObjectBytes",maxBytes,"maxAccountBytes",maxAccountBytes,"binaryRangeDownloads",true);}
 public DesktopAttachments(JdbcTemplate db,AuthService auth,ObjectMapper json,@Value("${funnywork.sync.max-attachment-bytes:33554432}") long maxBytes){this.db=db;this.auth=auth;this.json=json;this.maxBytes=maxBytes;if(maxBytes<1||maxBytes>1073741824)throw new IllegalArgumentException("Invalid sync attachment capacity");}
 private Identity actor(Identity value){value=auth.current(value);if(!"USER".equals(value.kind()))throw ApiException.forbidden();return value;}
 private static void valid(boolean value){if(!value)throw new ApiException(400,"INVALID_SYNC_ATTACHMENT","Invalid synchronization attachment");}
 private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception error){throw new IllegalStateException("Invalid stored attachment",error);}}
 private Map<String,Object> session(Identity actor,String id){var rows=db.queryForList("select * from sync_sessions where id=? and tenant_id=? and principal_id=?",id,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
 /** Called inside the append transaction after its immutable event is accepted. */
 void recordReferences(String sessionId,JsonNode event){
  recordReferences(sessionId,List.of(event));
 }
 /** Batch discovery preserves the first immutable metadata for each hash. Caller holds the session lock. */
 void recordReferences(String sessionId,List<JsonNode> events){
  var references=new LinkedHashMap<String,String>();
  for(var event:events){
  Deque<JsonNode> work=new ArrayDeque<>();work.push(event);
  while(!work.isEmpty()){
   var node=work.pop();var ref=node.path("attachment");String type=node.path("type").asText();
   if(Set.of("image","file").contains(type)&&ref.isObject()&&ref.path("attachmentId").asText().matches("sha256:[a-f0-9]{64}")&&ref.path("bytes").isIntegralNumber()&&ref.path("bytes").canConvertToLong()&&ref.path("bytes").asLong()>=0){
    String hash=ref.path("attachmentId").asText().substring(7);
    references.putIfAbsent(hash,json.createObjectNode().put("type",type).set("attachment",metadata(ref)).toString());
   }
   if(node.isContainerNode())for(var child:node)work.push(child);
  }
  }
  if(references.isEmpty())return;
  // Bounded hash groups avoid driver parameter limits on attachment-heavy batches.
  var hashes=new ArrayList<>(references.keySet());var inserts=new ArrayList<Object[]>();
  for(int start=0;start<hashes.size();start+=100){
   var group=hashes.subList(start,Math.min(start+100,hashes.size()));
   var params=new ArrayList<Object>();params.add(sessionId);params.addAll(group);
   var existing=new HashSet<>(db.queryForList("select hash from sync_attachment_refs where session_id=? and hash in ("+String.join(",",Collections.nCopies(group.size(),"?"))+")",String.class,params.toArray()));
   for(var hash:group)if(!existing.contains(hash))inserts.add(new Object[]{sessionId,hash,references.get(hash)});
  }
  if(!inserts.isEmpty())db.batchUpdate("insert into sync_attachment_refs(session_id,hash,metadata_json) values(?,?,?)",inserts);
 }
 private JsonNode metadata(JsonNode ref){var value=json.createObjectNode().put("attachmentId",ref.path("attachmentId").asText()).put("bytes",ref.path("bytes").asLong());for(String key:List.of("name","mediaType")){if(ref.path(key).isTextual())value.put(key,ref.path(key).asText().substring(0,Math.min(512,ref.path(key).asText().length())));}return value;}
 public Map<String,Object> manifest(Identity actor,String sessionId){return manifest(actor,sessionId,"",null);}
 public Map<String,Object> manifest(Identity actor,String sessionId,String after,String hash){
  valid(after!=null&&(after.isEmpty()||after.matches("[a-f0-9]{64}"))&&(hash==null||hash.matches("[a-f0-9]{64}")));
  actor=actor(actor);session(actor,sessionId);
  var rows=db.queryForList("select r.hash,r.metadata_json,o.next_offset,o.verified from sync_attachment_refs r left join sync_attachment_objects o on o.hash=r.hash and o.tenant_id=? and o.principal_id=? where r.session_id=? and r.hash>? and (cast(? as varchar) is null or r.hash=?) order by r.hash limit 101",actor.tenant(),actor.id(),sessionId,after,hash,hash);
  boolean more=rows.size()>100;if(more)rows=rows.subList(0,100);
  List<Map<String,Object>> items=new ArrayList<>();for(var row:rows)items.add(Map.of("hash",row.get("hash"),"metadata",decode((String)row.get("metadata_json")),"nextOffset",row.get("next_offset")==null?0L:row.get("next_offset"),"complete",Boolean.TRUE.equals(row.get("verified"))));
  return Map.of("maxBytes",maxBytes,"items",items,"after",more?rows.get(rows.size()-1).get("hash"):"");
 }
 @Transactional public Map<String,Object> upload(Identity actor,String sessionId,String hash,Chunk request){
  actor=actor(actor);valid(hash.matches("[a-f0-9]{64}")&&request!=null&&request.offset()>=0&&request.data()!=null&&request.data().length()<=512000);
  var session=session(actor,sessionId);
  if(!Objects.equals(request.deviceId(),session.get("device_id")))throw ApiException.forbidden();
  db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
  if(db.queryForList("select id from sync_devices where id=? and revoked=false and tenant_id=? and principal_id=? for update",request.deviceId(),actor.tenant(),actor.id()).isEmpty())throw ApiException.missing();
  var refs=db.queryForList("select metadata_json from sync_attachment_refs where session_id=? and hash=?",sessionId,hash);if(refs.isEmpty())throw ApiException.missing();
  long expected=decode((String)refs.get(0).get("metadata_json")).path("attachment").path("bytes").asLong();
  return writeChunk(actor,hash,expected,request.offset(),request.data());
 }
 private Map<String,Object> writeChunk(Identity actor,String hash,long expected,long offset,String data){
  if(expected>maxBytes)throw new ApiException(413,"SYNC_ATTACHMENT_CAPACITY","Attachment exceeds synchronization capacity");
  byte[] bytes;try{bytes=Base64.getDecoder().decode(data);}catch(IllegalArgumentException error){throw new ApiException(400,"INVALID_SYNC_ATTACHMENT","Invalid attachment encoding");}
  valid(Base64.getEncoder().encodeToString(bytes).equals(data)&&offset<=expected&&bytes.length<=expected-offset&&(bytes.length>0||expected==0));
  var rows=db.queryForList("select * from sync_attachment_objects where tenant_id=? and principal_id=? and hash=? for update",actor.tenant(),actor.id(),hash);
  if(rows.isEmpty()){
   long reserved=db.queryForObject("select coalesce(sum(size_bytes),0) from sync_attachment_objects where tenant_id=? and principal_id=?",Long.class,actor.tenant(),actor.id());
   int count=db.queryForObject("select count(*) from sync_attachment_objects where tenant_id=? and principal_id=?",Integer.class,actor.tenant(),actor.id());
   if(expected>maxAccountBytes-reserved||count>=maxAccountObjects)throw new ApiException(413,"SYNC_ACCOUNT_ATTACHMENT_CAPACITY","Account attachment capacity reached");
   db.update("insert into sync_attachment_objects(tenant_id,principal_id,hash,size_bytes) values(?,?,?,?)",actor.tenant(),actor.id(),hash,expected);rows=db.queryForList("select * from sync_attachment_objects where tenant_id=? and principal_id=? and hash=?",actor.tenant(),actor.id(),hash);
  }
  var object=rows.get(0);valid(((Number)object.get("size_bytes")).longValue()==expected);long next=((Number)object.get("next_offset")).longValue();
  if(offset<next||Boolean.TRUE.equals(object.get("verified"))){
   var stored=db.queryForList("select encoded_data from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=? and byte_offset=?",actor.tenant(),actor.id(),hash,offset);
   if(stored.isEmpty()||!data.equals(stored.get(0).get("encoded_data")))throw ApiException.conflict("SYNC_ATTACHMENT_CONFLICT");
   return Map.of("nextOffset",next,"complete",object.get("verified"));
  }
  if(offset!=next)throw ApiException.conflict("SYNC_ATTACHMENT_GAP");
  db.update("insert into sync_attachment_chunks(tenant_id,principal_id,hash,byte_offset,encoded_data) values(?,?,?,?,?)",actor.tenant(),actor.id(),hash,next,data);next+=bytes.length;
  boolean complete=next==expected;
  if(complete){
   MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception error){throw new IllegalStateException(error);}
   final Identity owner=actor;
   db.query(connection->{var statement=connection.prepareStatement("select encoded_data from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=? order by byte_offset");statement.setString(1,owner.tenant());statement.setString(2,owner.id());statement.setString(3,hash);statement.setFetchSize(8);return statement;},(org.springframework.jdbc.core.RowCallbackHandler)row->digest.update(Base64.getDecoder().decode(row.getString(1))));
   if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw ApiException.conflict("SYNC_ATTACHMENT_HASH_MISMATCH");
  }
  db.update("update sync_attachment_objects set next_offset=?,verified=? where tenant_id=? and principal_id=? and hash=?",next,complete,actor.tenant(),actor.id(),hash);
  return Map.of("nextOffset",next,"complete",complete);
 }
 /** Called in the enqueue transaction before the command is published. */
 void retainMobileForCommand(Identity actor,String sessionId,JsonNode files){
  valid(files.isArray()&&files.size()>0&&files.size()<=4);
  Set<String> seen=new HashSet<>();long total=0;
  for(var file:files){
   String hash=file.path("hash").asText();
   valid(file.isObject()&&file.size()==4&&hash.matches("[a-f0-9]{64}")&&seen.add(hash)&&file.path("bytes").isIntegralNumber()&&file.path("bytes").canConvertToLong()&&file.path("bytes").asLong()>=0&&file.path("bytes").asLong()<=33554432&&file.path("name").isTextual()&&file.path("mediaType").isTextual());
   total+=file.path("bytes").asLong();valid(total<=33554432);
   var status=mobileStatus(actor,sessionId,hash);JsonNode saved=(JsonNode)status.get("metadata");
   valid(saved.path("bytes").asLong()==file.path("bytes").asLong()&&saved.path("name").equals(file.path("name"))&&saved.path("mediaType").equals(file.path("mediaType")));
   if(!Boolean.TRUE.equals(status.get("complete")))throw ApiException.conflict("SYNC_ATTACHMENT_INCOMPLETE");
   if(db.queryForObject("select count(*) from sync_attachment_refs where session_id=? and hash=?",Integer.class,sessionId,hash)==0)
    db.update("insert into sync_attachment_refs(session_id,hash,metadata_json) values(?,?,?)",sessionId,hash,json.createObjectNode().put("type","file").set("attachment",saved).toString());
  }
 }
 public record MobileFile(long bytes,String name,String mediaType) {}
 public record MobileChunk(long offset,String data) {}
 @Transactional public Map<String,Object> prepareMobile(Identity actor,String sessionId,String hash,MobileFile file){
  actor=actor(actor);session(actor,sessionId);
  valid(hash!=null&&hash.matches("[a-f0-9]{64}")&&file!=null&&file.bytes()>=0&&file.name()!=null&&!file.name().isBlank()&&file.name().length()<=255&&!Set.of(".","..").contains(file.name())&&!file.name().contains("/")&&!file.name().contains("\\")&&file.name().chars().noneMatch(Character::isISOControl)&&file.mediaType()!=null&&file.mediaType().length()<=128&&file.mediaType().matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"));
  if(file.bytes()>maxBytes)throw new ApiException(413,"SYNC_ATTACHMENT_CAPACITY","Attachment exceeds synchronization capacity");
  db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
  var metadata=json.createObjectNode().put("attachmentId","sha256:"+hash).put("bytes",file.bytes()).put("name",file.name()).put("mediaType",file.mediaType());
  var rows=db.queryForList("select metadata_json from sync_mobile_uploads where session_id=? and hash=?",sessionId,hash);
  if(rows.isEmpty()){
   int pending=db.queryForObject("select count(*) from sync_mobile_uploads u join sync_sessions s on s.id=u.session_id where s.tenant_id=? and s.principal_id=? and not exists(select 1 from sync_attachment_refs r where r.session_id=u.session_id and r.hash=u.hash)",Integer.class,actor.tenant(),actor.id());
   if(pending>=100)throw new ApiException(413,"SYNC_UPLOAD_CAPACITY","Too many pending attachments");
   db.update("insert into sync_mobile_uploads(session_id,hash,metadata_json) values(?,?,?)",sessionId,hash,metadata.toString());
  }else if(!decode((String)rows.get(0).get("metadata_json")).equals(decode(metadata.toString())))throw ApiException.conflict("SYNC_ATTACHMENT_CONFLICT");
  return mobileStatus(actor,sessionId,hash);
 }
 public Map<String,Object> mobileStatus(Identity actor,String sessionId,String hash){
  actor=actor(actor);session(actor,sessionId);valid(hash!=null&&hash.matches("[a-f0-9]{64}"));
  var refs=db.queryForList("select metadata_json from sync_mobile_uploads where session_id=? and hash=?",sessionId,hash);if(refs.isEmpty())throw ApiException.missing();
  JsonNode metadata=decode((String)refs.get(0).get("metadata_json"));
  var rows=db.queryForList("select next_offset,verified,size_bytes from sync_attachment_objects where tenant_id=? and principal_id=? and hash=?",actor.tenant(),actor.id(),hash);
  if(!rows.isEmpty())valid(((Number)rows.get(0).get("size_bytes")).longValue()==metadata.path("bytes").asLong());
  return Map.of("metadata",metadata,"maxBytes",maxBytes,"nextOffset",rows.isEmpty()?0L:rows.get(0).get("next_offset"),"complete",!rows.isEmpty()&&Boolean.TRUE.equals(rows.get(0).get("verified")));
 }
 @Transactional public Map<String,Object> uploadMobile(Identity actor,String sessionId,String hash,MobileChunk request){
  actor=actor(actor);session(actor,sessionId);valid(hash!=null&&hash.matches("[a-f0-9]{64}")&&request!=null&&request.offset()>=0&&request.data()!=null&&request.data().length()<=512000);
  db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
  var refs=db.queryForList("select metadata_json from sync_mobile_uploads where session_id=? and hash=?",sessionId,hash);if(refs.isEmpty())throw ApiException.missing();
  long expected=decode((String)refs.get(0).get("metadata_json")).path("bytes").asLong();
  return writeChunk(actor,hash,expected,request.offset(),request.data());
 }
 @Transactional public void discardMobile(Identity actor,String sessionId,String hash){
  actor=actor(actor);session(actor,sessionId);valid(hash!=null&&hash.matches("[a-f0-9]{64}"));
  db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
  db.update("delete from sync_mobile_uploads where session_id=? and hash=?",sessionId,hash);
  int retained=db.queryForObject("select count(*) from sync_attachment_refs r join sync_sessions s on s.id=r.session_id where s.tenant_id=? and s.principal_id=? and r.hash=?",Integer.class,actor.tenant(),actor.id(),hash)
   +db.queryForObject("select count(*) from sync_mobile_uploads u join sync_sessions s on s.id=u.session_id where s.tenant_id=? and s.principal_id=? and u.hash=?",Integer.class,actor.tenant(),actor.id(),hash);
  if(retained==0){
   db.update("delete from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=?",actor.tenant(),actor.id(),hash);
   db.update("delete from sync_attachment_objects where tenant_id=? and principal_id=? and hash=?",actor.tenant(),actor.id(),hash);
  }
 }
 public Map<String,Object> download(Identity actor,String sessionId,String hash,long offset){
  actor=actor(actor);session(actor,sessionId);valid(hash.matches("[a-f0-9]{64}")&&offset>=0);
  var rows=db.queryForList("select o.size_bytes from sync_attachment_objects o join sync_attachment_refs r on r.hash=o.hash where r.session_id=? and o.hash=? and o.tenant_id=? and o.principal_id=? and o.verified=true",sessionId,hash,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();
  var chunks=db.queryForList("select encoded_data from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=? and byte_offset=?",actor.tenant(),actor.id(),hash,offset);if(chunks.isEmpty())throw ApiException.missing();String encoded=(String)chunks.get(0).get("encoded_data");long next=offset+Base64.getDecoder().decode(encoded).length;
  return Map.of("data",encoded,"nextOffset",next,"complete",next==((Number)rows.get(0).get("size_bytes")).longValue());
 }
}
