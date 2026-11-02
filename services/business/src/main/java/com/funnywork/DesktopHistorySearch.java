package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded, account-scoped search of durable visible messages; no desktop invocation. */
@Service
public class DesktopHistorySearch {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper mapper;
    private final Clock clock;
    public DesktopHistorySearch(JdbcTemplate db,AuthService auth,ObjectMapper mapper,Clock clock) {
        this.db=db;this.auth=auth;this.mapper=mapper;this.clock=clock;
    }
    public Map<String,Object> search(Identity actor,String query,String afterSession,long afterSeq,boolean archived,String deviceID) {
        actor=auth.current(actor);
        if(!actor.kind().equals("USER"))throw ApiException.forbidden();
        if(query==null||query.isBlank()||query.length()>160||afterSession==null||
            !(afterSession.isEmpty()||afterSession.matches("[A-Za-z0-9_-]{1,64}"))||afterSeq< -1||
            (afterSession.isEmpty()&&afterSeq!= -1)||deviceID==null||
            !(deviceID.isEmpty()||deviceID.matches("[A-Za-z0-9_-]{1,64}")))
            throw new ApiException(400,"INVALID_SEARCH","Invalid search parameters");
        var pattern=Pattern.compile(Pattern.quote(query.strip()),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        // Keyset pages keep each call bounded, including when few messages match. The client
        // follows the scan cursor, not the last match, so sparse results never skip history.
        var rows=db.queryForList("select s.id,s.title,s.device_id,s.archived,s.title_revision,s.archive_revision,s.updated_at,d.name as device_name,d.last_seen,d.revoked,e.seq,e.visible_text,case when e.visible_text is null then e.event_json else null end as event_json from sync_sessions s join sync_devices d on d.id=s.device_id join sync_session_events e on e.session_id=s.id where s.tenant_id=? and s.principal_id=? and s.archived=? and (?='' or s.device_id=?) and (s.id>? or (s.id=? and e.seq>?)) order by s.id,e.seq limit 65",actor.tenant(),actor.id(),archived,deviceID,deviceID,afterSession,afterSession,afterSeq);
        List<Map<String,Object>> items=new ArrayList<>();
        String nextSession=afterSession;long nextSeq=afterSeq;
        for(var row:rows.subList(0,Math.min(64,rows.size()))) {
            nextSession=(String)row.get("id");nextSeq=((Number)row.get("seq")).longValue();
            String text=(String)row.get("visible_text");
            if(text==null) {
                try {text=visibleText(mapper.readTree((String)row.get("event_json")));}
                catch(Exception failure){throw new IllegalStateException("Stored sync record is invalid",failure);}
            }
            var match=pattern.matcher(text);
            if(!match.find())continue;
            int start=Math.max(0,match.start()-80),end=Math.min(text.length(),Math.max(match.end(),start+240));
            if(start>0&&Character.isLowSurrogate(text.charAt(start)))start--;
            if(end<text.length()&&Character.isLowSurrogate(text.charAt(end)))end++;
            String excerpt=(start>0?"…":"")+text.substring(start,end)+(end<text.length()?"…":"");
            var conversation=new LinkedHashMap<String,Object>(row);
            conversation.remove("event_json");conversation.remove("visible_text");conversation.remove("seq");
            conversation.put("online",!Boolean.TRUE.equals(row.get("revoked"))&&clock.millis()-((Number)row.get("last_seen")).longValue()<=60000);
            conversation.remove("last_seen");conversation.remove("revoked");
            items.add(Map.of("conversation",conversation,"seq",nextSeq,"excerpt",excerpt));
        }
        return Map.of("items",items,"afterSession",nextSession,"afterSeq",nextSeq,"hasMore",rows.size()>64);
    }
    static String visibleText(JsonNode event) {
        String type=event.path("type").asText();
        if(!type.equals("user/message")&&!type.equals("assistant/message"))return "";
        var data=event.path("data");
        if(data.path("message").isObject())data=data.path("message");
        if(type.equals("user/message")&&data.has("source")&&!data.path("source").path("kind").asText().equals("user"))return "";
        StringBuilder text=new StringBuilder();
        var content=data.path("content");
        if(content.isArray())for(var part:content) {
            // Text and attachment display names only: exclude reasoning, tool arguments and paths.
            String kind=part.path("type").asText();
            if((kind.isEmpty()||kind.equals("text"))&&part.path("text").isTextual())text.append(part.path("text").asText()).append('\n');
            if(kind.equals("file"))text.append(part.path("attachment").path("name").asText()).append('\n');
        }
        return text.toString();
    }
}
