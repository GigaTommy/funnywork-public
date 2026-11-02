package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Fixed result versions projected only from accepted native delivery events. */
@Service
public class DesktopResults {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    public DesktopResults(JdbcTemplate db,AuthService auth,ObjectMapper json){this.db=db;this.auth=auth;this.json=json;}
    private static void valid(boolean value){if(!value)throw new ApiException(400,"INVALID_RESULT_FILE","Invalid result manifest");}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception error){throw new IllegalStateException("Invalid stored result",error);}}
    private void owned(Identity actor,String session){
        var live=auth.current(actor);if(!"USER".equals(live.kind()))throw ApiException.forbidden();
        if(db.queryForObject("select count(*) from sync_sessions where id=? and tenant_id=? and principal_id=?",Integer.class,session,live.tenant(),live.id())!=1)throw ApiException.missing();
    }
    private String attachment(JsonNode content){
        var file=content.path("attachment");valid("file".equals(content.path("type").asText())&&file.path("attachmentId").asText().matches("sha256:[a-f0-9]{64}")&&file.path("bytes").isIntegralNumber()&&file.path("bytes").canConvertToLong()&&file.path("bytes").asLong()>=0&&file.path("bytes").asLong()<=268435456L&&file.path("name").isTextual()&&!file.path("name").asText().isBlank()&&file.path("name").asText().length()<=255);
        return file.path("attachmentId").asText().substring(7);
    }
    /** Runs in the owning session's append transaction; replay never calls this method. */
    void record(String session,List<JsonNode> events){
        for(var event:events){
            if(!"deliverables/presented".equals(event.path("type").asText()))continue;
            for(var file:event.path("data").path("files")){
                var result=file.path("result");if(result.isMissingNode())continue;
                valid(result.path("schema").isInt()&&result.path("schema").asInt()==1&&result.path("id").asText().matches("[A-Za-z0-9_-]{1,64}")&&result.path("version").isInt()&&result.path("version").asInt()>0&&result.path("version").asInt()<=1000&&result.path("variants").isArray()&&result.path("variants").size()<=256&&result.path("warnings").isArray()&&result.path("warnings").size()<=20&&Set.of("pending","ready","failed").contains(result.path("previewState").asText()));
                for(var warning:result.path("warnings"))valid(warning.isTextual()&&warning.asText().length()<=512);
                String id=result.path("id").asText(),hash=attachment(result.path("original"));int version=result.path("version").asInt();
                var keys=new HashSet<String>();long total=result.path("original").path("attachment").path("bytes").asLong();
                for(var variant:result.path("variants")){
                    valid(Set.of("pdf","thumbnail","workbook","sheet").contains(variant.path("role").asText())&&variant.path("key").asText().matches("[A-Za-z0-9_-]{1,80}")&&!"original".equals(variant.path("key").asText())&&keys.add(variant.path("key").asText()));
                    attachment(variant.path("content"));total=Math.addExact(total,variant.path("content").path("attachment").path("bytes").asLong());
                }
                valid(total<=268435456L);
                var old=db.queryForList("select original_hash,manifest_json from sync_results where session_id=? and result_id=? and version=?",session,id,version);
                if(old.isEmpty()){
                    valid(db.queryForObject("select count(*) from sync_results where session_id=?",Integer.class,session)<1000);
                    Integer last=db.queryForObject("select coalesce(max(version),0) from sync_results where session_id=? and result_id=?",Integer.class,session,id);
                    if(version!=last+1)throw ApiException.conflict("RESULT_VERSION_CONFLICT");
                    if(last>0){String previous=db.queryForObject("select original_hash from sync_results where session_id=? and result_id=? and version=?",String.class,session,id,last);if(!previous.equals(result.path("previousHash").asText()))throw ApiException.conflict("RESULT_VERSION_CONFLICT");}
                    db.update("insert into sync_results values(?,?,?,?,?,?,?)",session,id,version,hash,result.toString(),event.path("seq").asLong(),event.path("time").asLong());
                }else{
                    var prior=decode((String)old.get(0).get("manifest_json"));
                    if(!hash.equals(old.get(0).get("original_hash"))||!prior.path("original").equals(result.path("original"))||!prior.path("turn").equals(result.path("turn"))||!prior.path("previousHash").equals(result.path("previousHash")))throw ApiException.conflict("RESULT_CONTENT_CHANGED");
                    // Preview completion may add variants, but never replaces an already published variant.
                    for(var before:prior.path("variants")){boolean retained=false;for(var after:result.path("variants"))if(before.equals(after)){retained=true;break;}if(!retained)throw ApiException.conflict("RESULT_PREVIEW_CHANGED");}
                    db.update("update sync_results set manifest_json=?,event_seq=? where session_id=? and result_id=? and version=?",result.toString(),event.path("seq").asLong(),session,id,version);
                }
            }
        }
    }
    public Map<String,Object> list(Identity actor,String session,long after){
        owned(actor,session);valid(after>=-1);
        var rows=db.queryForList("select * from sync_results where session_id=? and event_seq>? order by event_seq,result_id,version limit 51",session,after);
        boolean more=rows.size()>50;if(more)rows=rows.subList(0,50);
        // One delivery event can contain several results. Never split that event's cursor boundary.
        if(more&&!rows.isEmpty()){long boundary=((Number)rows.get(rows.size()-1).get("event_seq")).longValue();rows=new ArrayList<>(rows);rows.removeIf(row->((Number)row.get("event_seq")).longValue()==boundary);if(rows.isEmpty())rows=db.queryForList("select * from sync_results where session_id=? and event_seq=? order by result_id,version",session,boundary);}
        var items=new ArrayList<Map<String,Object>>();long next=after;
        for(var row:rows){items.add(present(actor,session,row));next=Math.max(next,((Number)row.get("event_seq")).longValue());}
        return Map.of("items",items,"after",next,"hasMore",more);
    }
    public List<Map<String,Object>> versions(Identity actor,String session,String id){owned(actor,session);return db.queryForList("select * from sync_results where session_id=? and result_id=? order by version desc",session,id).stream().map(row->present(actor,session,row)).toList();}
    public JsonNode contentReference(Identity actor,String session,String id,int version,String key){
        owned(actor,session);var rows=db.queryForList("select * from sync_results where session_id=? and result_id=? and version=?",session,id,version);if(rows.isEmpty())throw ApiException.missing();
        var manifest=decode((String)rows.get(0).get("manifest_json"));if("original".equals(key))return manifest.path("original").path("attachment");
        for(var variant:manifest.path("variants"))if(key.equals(variant.path("key").asText()))return variant.path("content").path("attachment");throw ApiException.missing();
    }
    private Map<String,Object> present(Identity actor,String session,Map<String,Object> row){
        var manifest=decode((String)row.get("manifest_json"));var status=new LinkedHashMap<String,Object>();
        status.put("original",status(actor,manifest.path("original").path("attachment")));
        for(var variant:manifest.path("variants"))status.put(variant.path("key").asText(),status(actor,variant.path("content").path("attachment")));
        return Map.of("manifest",manifest,"eventSeq",row.get("event_seq"),"createdAt",row.get("created_at"),"transfers",status);
    }
    private Map<String,Object> status(Identity actor,JsonNode ref){var rows=db.queryForList("select next_offset,verified from sync_attachment_objects where tenant_id=? and principal_id=? and hash=?",actor.tenant(),actor.id(),ref.path("attachmentId").asText().substring(7));return rows.isEmpty()?Map.of("nextOffset",0L,"complete",false):Map.of("nextOffset",rows.get(0).get("next_offset"),"complete",rows.get(0).get("verified"));}
}
