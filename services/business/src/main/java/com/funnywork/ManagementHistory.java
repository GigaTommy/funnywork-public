package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Bounded organization history reads. Metadata never includes titles, prompts or local paths. */
@Service
public class ManagementHistory {
    private static final List<String> SOURCES=List.of("DESKTOP","EMPLOYEE","MOBILE");
    private final JdbcTemplate db;private final AuthService auth;private final Json json;private final ObjectMapper mapper;private final Clock clock;
    public ManagementHistory(JdbcTemplate db,AuthService auth,Json json,ObjectMapper mapper,Clock clock){this.db=db;this.auth=auth;this.json=json;this.mapper=mapper;this.clock=clock;}
    private Identity admin(Identity actor){actor=auth.current(actor);actor.requireUser("ADMIN");return actor;}
    private static void valid(boolean condition){if(!condition)throw new ApiException(400,"INVALID_HISTORY_QUERY","Invalid history query");}
    private boolean visible(Identity actor,Map<String,Object> row){
        try{return actor.zones().containsAll(json.strings((String)row.get("owner_zones")))&&(row.get("zone")==null||actor.zones().contains(row.get("zone")));}
        catch(RuntimeException invalid){return false;}
    }
    private String table(String source){return switch(source){case "DESKTOP"->"sync_sessions";case "EMPLOYEE"->"employee_chats";case "MOBILE"->"mobile_chats";default->throw new ApiException(400,"INVALID_HISTORY_QUERY","Invalid source");};}
    private String fields(String source){return switch(source){
        case "DESKTOP"->"id,tenant_id,principal_id as owner_id,device_id,cast(null as varchar(64)) as zone,cast(null as bigint) as created_at,updated_at,next_seq as received_position,archived";
        case "EMPLOYEE"->"id,tenant_id,owner_id,cast(null as varchar(64)) as device_id,zone,created_at,cast(null as bigint) as updated_at,cast(null as bigint) as received_position,false as archived";
        case "MOBILE"->"id,tenant_id,owner_id,cast(null as varchar(64)) as device_id,cast(null as varchar(64)) as zone,created_at,updated_at,revision as received_position,false as archived";
        default->throw new ApiException(400,"INVALID_HISTORY_QUERY","Invalid source");};}
    private Map<String,Object> metadata(Map<String,Object> row){var safe=new LinkedHashMap<String,Object>(row);safe.remove("owner_zones");return safe;}

    @Transactional
    public Map<String,Object> list(Identity actor,String source,String owner,String after){
        actor=admin(actor);valid(source!=null&&(source.equals("ALL")||SOURCES.contains(source))&&owner!=null&&owner.matches("[A-Za-z0-9_-]{0,64}")&&after!=null&&(after.isEmpty()||after.matches("(?:DESKTOP|EMPLOYEE|MOBILE):[A-Za-z0-9_-]{1,64}")));
        String afterSource=after.isEmpty()?"":after.split(":",2)[0],afterId=after.isEmpty()?"":after.split(":",2)[1];
        var branches=new ArrayList<String>();var params=new ArrayList<Object>();
        for(var kind:SOURCES){
            if(!source.equals("ALL")&&!source.equals(kind)||kind.compareTo(afterSource)<0)continue;
            String ownerField=kind.equals("DESKTOP")?"principal_id":"owner_id";
            branches.add("(select '"+kind+"' as source,"+fields(kind)+" from "+table(kind)+" where tenant_id=? and id>?"+(owner.isEmpty()?"":" and "+ownerField+"=?")+" order by id limit 101)");
            params.add(actor.tenant());params.add(kind.equals(afterSource)?afterId:"");if(!owner.isEmpty())params.add(owner);
        }
        var rows=branches.isEmpty()?List.<Map<String,Object>>of():db.queryForList("select h.*,p.display_name as owner_name,p.zones as owner_zones from ("+String.join(" union all ",branches)+") h join principals p on p.id=h.owner_id and p.tenant_id=h.tenant_id order by h.source,h.id limit 101",params.toArray());
        var items=new ArrayList<Map<String,Object>>();String cursor=after;
        for(var row:rows.subList(0,Math.min(100,rows.size()))){cursor=row.get("source")+":"+row.get("id");if(visible(actor,row))items.add(metadata(row));}
        auth.auditRead(actor,"HISTORY_DIRECTORY_READ",Map.of("type",source,"principalId",owner,"sourceId",afterId,"count",items.size()));
        return Map.of("items",items,"after",cursor,"hasMore",rows.size()>100,"observedAt",clock.millis());
    }

    private Map<String,Object> authorized(Identity actor,String source,String id){
        valid(SOURCES.contains(source)&&id!=null&&id.matches("[A-Za-z0-9_-]{1,64}"));
        var rows=db.queryForList("select h.*,p.display_name as owner_name,p.zones as owner_zones from (select '"+source+"' as source,"+fields(source)+" from "+table(source)+" where tenant_id=? and id=?) h join principals p on p.id=h.owner_id and p.tenant_id=h.tenant_id",actor.tenant(),id);
        if(rows.isEmpty()||!visible(actor,rows.get(0)))throw ApiException.missing();return rows.get(0);
    }
    private static Map<String,Object> message(String role,String text,long position){
        int end=Math.min(text.length(),4096);if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return Map.of("role",role,"text",text.substring(0,end),"truncated",end<text.length(),"position",position);
    }
    @Transactional
    public Map<String,Object> content(Identity actor,String source,String id,long after){
        return content(actor,source,id,after,"");
    }
    @Transactional
    public Map<String,Object> content(Identity actor,String source,String id,long after,String continuation){
        actor=admin(actor);valid(after>=-1&&after<Long.MAX_VALUE);var conversation=authorized(actor,source,id);
        valid(continuation!=null&&continuation.length()<=120&&(source.equals("EMPLOYEE")||continuation.isEmpty()));
        var items=new ArrayList<Map<String,Object>>();long cursor=after;boolean more;String nextContinuation="";
        switch(source){
            case "DESKTOP"->{
                var rows=db.queryForList("select seq,visible_text,event_type,case when visible_text is null or event_type is null then event_json else null end as event_json from sync_session_events where session_id=? and seq>? order by seq limit 33",id,after);
                more=rows.size()>32;
                for(var row:rows.subList(0,Math.min(32,rows.size()))){
                    cursor=((Number)row.get("seq")).longValue();String text=(String)row.get("visible_text");
                    String type=(String)row.get("event_type");
                    if(text==null||type==null)try{var event=mapper.readTree((String)row.get("event_json"));text=DesktopHistorySearch.visibleText(event);type=event.path("type").asText();}catch(Exception invalid){throw new IllegalStateException("Invalid stored history event");}
                    if(!text.isEmpty())items.add(message("user/message".equals(type)?"USER":"ASSISTANT",text,cursor));
                }
            }
            case "MOBILE"->{
                var rows=db.queryForList("select ordinal,user_text,reply_text,state from mobile_chat_turns where chat_id=? and ordinal>? order by ordinal limit 17",id,after);more=rows.size()>16;
                for(var row:rows.subList(0,Math.min(16,rows.size()))){cursor=((Number)row.get("ordinal")).longValue();items.add(message("USER",(String)row.get("user_text"),cursor));if(!((String)row.get("reply_text")).isEmpty()){var reply=new LinkedHashMap<>(message("ASSISTANT",(String)row.get("reply_text"),cursor));reply.put("state",row.get("state"));items.add(reply);}}
            }
            case "EMPLOYEE"->{
                var page=employeePage(actor,conversation,after,continuation,items);
                cursor=page.after();more=page.more();nextContinuation=page.continuation();
            }
            default->throw new ApiException(400,"INVALID_HISTORY_QUERY","Invalid source");
        }
        var auditZones=conversation.get("zone")==null?json.strings((String)conversation.get("owner_zones")):Set.of((String)conversation.get("zone"));
        auth.auditRead(new Identity(actor.id(),actor.tenant(),actor.kind(),actor.roles(),auditZones,actor.authVersion()),"HISTORY_CONTENT_READ",Map.of("type",source,"sourceId",id,"principalId",conversation.get("owner_id"),"count",items.size(),"afterSeq",after,"throughSeq",cursor,"continuation",continuation,"nextContinuation",nextContinuation));
        return Map.of("conversation",metadata(conversation),"items",items,"after",cursor,"continuation",nextContinuation,"hasMore",more,"observedAt",clock.millis());
    }

    private record EmployeePage(long after,String continuation,boolean more) {}
    private EmployeePage employeePage(Identity actor,Map<String,Object> conversation,long after,String continuation,List<Map<String,Object>> items){
        String phase="OUTPUT",correctionId="";long turn=-1,outputAfter=-1,correctionAfter=-1;
        if(!continuation.isEmpty()){
            valid(continuation.matches("[0-9]{1,10}:OUTPUT:[0-9]{1,10}")||continuation.matches("[0-9]{1,10}:CORRECTION:[0-9]{1,10}:[A-Za-z0-9_-]{1,64}"));
            var parts=continuation.split(":");turn=Long.parseLong(parts[0]);phase=parts[1];long detail=Long.parseLong(parts[2]);
            valid(turn>after&&turn<=Integer.MAX_VALUE&&detail<=Integer.MAX_VALUE);
            if(phase.equals("OUTPUT"))outputAfter=detail;else{correctionAfter=detail;correctionId=parts[3];}
        }
        var params=new ArrayList<Object>(List.of(actor.tenant(),conversation.get("owner_id"),conversation.get("zone"),conversation.get("id"),continuation.isEmpty()?after:turn));
        String query="select c.ordinal,c.prompt,c.task_id,t.state from employee_chat_turns c join tasks t on t.id=c.task_id and t.tenant_id=? and t.owner_id=? and t.zone=? where c.chat_id=? and c.ordinal"+(continuation.isEmpty()?">?":"=?")+" order by c.ordinal limit 1";
        var rows=db.queryForList(query,params.toArray());
        if(rows.isEmpty()){if(!continuation.isEmpty())throw ApiException.missing();return new EmployeePage(after,"",false);}
        var row=rows.get(0);turn=((Number)row.get("ordinal")).longValue();String task=(String)row.get("task_id");
        if(continuation.isEmpty()){var prompt=new LinkedHashMap<>(message("USER",(String)row.get("prompt"),turn));prompt.put("taskId",task);prompt.put("state",row.get("state"));items.add(prompt);}
        if(phase.equals("OUTPUT")){
            var outputs=db.queryForList("select u.task_id,u.id,u.ordinal,r.result_json from work_units u join unit_results r on r.unit_id=u.id where u.task_id=? and u.ordinal>? order by u.ordinal limit 33",task,outputAfter);
            employeeOutputs(actor,conversation,outputs.subList(0,Math.min(32,outputs.size())),turn,items);
            if(outputs.size()>32)return new EmployeePage(after,turn+":OUTPUT:"+outputs.get(31).get("ordinal"),true);
        }
        var corrections=db.queryForList("select id,unit_ordinal,prompt,created_at from employee_chat_instructions where chat_id=? and task_id=? and (unit_ordinal>? or (unit_ordinal=? and id>?)) order by unit_ordinal,id limit 33",conversation.get("id"),task,correctionAfter,correctionAfter,correctionId);
        for(var correction:corrections.subList(0,Math.min(32,corrections.size()))){var item=new LinkedHashMap<>(message("CORRECTION",(String)correction.get("prompt"),turn));item.put("taskId",task);item.put("instructionId",correction.get("id"));item.put("createdAt",correction.get("created_at"));items.add(item);}
        if(corrections.size()>32){var last=corrections.get(31);return new EmployeePage(after,turn+":CORRECTION:"+last.get("unit_ordinal")+":"+last.get("id"),true);}
        var later=db.queryForList("select c.ordinal from employee_chat_turns c join tasks t on t.id=c.task_id and t.tenant_id=? and t.owner_id=? and t.zone=? where c.chat_id=? and c.ordinal>? order by c.ordinal limit 1",actor.tenant(),conversation.get("owner_id"),conversation.get("zone"),conversation.get("id"),turn);
        return new EmployeePage(turn,"",!later.isEmpty());
    }
    private void employeeOutputs(Identity actor,Map<String,Object> conversation,List<Map<String,Object>> results,long position,List<Map<String,Object>> items){
        var pending=new ArrayList<Map<String,Object>>();var hashes=new HashMap<String,Object>();var artifactIds=new LinkedHashSet<String>();
        for(var row:results){
            var result=json.read((String)row.get("result_json"));if(!(result instanceof Map<?,?> value)||!"MODEL_OUTPUT".equals(value.get("kind"))||!(value.get("artifactId") instanceof String artifact)||!artifact.matches("[A-Za-z0-9_-]{1,64}"))continue;
            var item=new LinkedHashMap<String,Object>();item.put("role","ASSISTANT");item.put("text","");item.put("position",position);item.put("taskId",row.get("task_id"));item.put("artifactId",artifact);item.put("version",1);item.put("availability","REFERENCE_ONLY");items.add(item);pending.add(item);artifactIds.add(artifact);hashes.put((String)row.get("id"),value.get("sha256"));item.put("unitId",row.get("id"));
        }
        if(!artifactIds.isEmpty()){
            // One bounded read, limited to original outputs owned by this source and in its zone.
            var params=new ArrayList<Object>(List.of(actor.tenant(),conversation.get("owner_id"),conversation.get("zone")));params.addAll(artifactIds);
            var outputs=new HashMap<String,Map<String,Object>>();
            for(var output:db.queryForList("select a.id,v.content_base64,v.sha256 from artifacts a join artifact_versions v on v.artifact_id=a.id and v.version=1 where a.tenant_id=? and a.owner_id=? and a.zone=? and v.byte_size<=65536 and a.id in ("+String.join(",",Collections.nCopies(artifactIds.size(),"?"))+")",params.toArray()))outputs.put((String)output.get("id"),output);
            for(var item:pending){
                var output=outputs.get((String)item.get("artifactId"));if(output==null||!Objects.equals(hashes.get(item.get("unitId")),output.get("sha256")))continue;
                try{
                    byte[] bytes=Base64.getDecoder().decode((String)output.get("content_base64"));
                    String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));if(!digest.equals(output.get("sha256")))continue;
                    String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    item.putAll(message("ASSISTANT",text,((Number)item.get("position")).longValue()));item.put("availability","ORIGINAL_OUTPUT");
                }catch(Exception invalid){/* Keep the reference; never invent text from missing or non-text output. */}
            }
        }
    }
}
