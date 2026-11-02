package com.funnywork;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Account-scoped history metadata; does not change inference revisions or stop turns. */
@Service
public class MobileChatHistory {
    public record Update(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String id,
                         @Min(0) long expectedHistoryRevision,@Size(max=160) String title,Boolean archived) {}
    private record Cursor(long time,String id,boolean archived,String query) {}
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MobileChatHistory(JdbcTemplate db,AuthService auth,ObjectMapper json,Clock clock,PlatformTransactionManager manager) {
        this.db=db;this.auth=auth;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);
    }
    private Identity user(Identity actor) {
        var live=auth.current(actor);
        if(!"USER".equals(live.kind()))throw ApiException.forbidden();
        return live;
    }
    static Map<String,Object> summary(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","title","model_id","revision","updated_at","archived","history_revision"))result.put(key,row.get(key));
        result.put("modelId",row.get("model_id"));
        return result;
    }
    public Map<String,Object> read(Identity actor,String chatId) {
        var live=user(actor);
        var rows=db.queryForList("select * from mobile_chats where id=? and tenant_id=? and owner_id=?",chatId,live.tenant(),live.id());
        if(rows.isEmpty())throw ApiException.missing();
        return summary(rows.get(0));
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid history data",failure);}
    }
    public Map<String,Object> page(Identity actor,boolean archived,String query,String after) {
        var live=user(actor);String needle=query.strip();
        if(needle.length()>200||after.length()>2048)throw new ApiException(400,"INVALID_CHAT_HISTORY","Invalid history query");
        var args=new ArrayList<Object>(List.of(live.tenant(),live.id(),archived));
        String sql="select c.id,c.title,c.model_id,c.revision,c.updated_at,c.archived,c.history_revision from mobile_chats c where c.tenant_id=? and c.owner_id=? and c.archived=?";
        if(!needle.isEmpty()) {
            String pattern="%"+needle.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            sql+=" and (lower(c.title) like ? escape '!' or exists(select 1 from mobile_chat_turns t where t.chat_id=c.id and (lower(t.user_text) like ? escape '!' or lower(t.reply_text) like ? escape '!')))";
            args.add(pattern);args.add(pattern);args.add(pattern);
        }
        if(!after.isEmpty()) {
            Cursor cursor;
            try {
                cursor=json.readValue(Base64.getUrlDecoder().decode(after),Cursor.class);
                if(cursor.time()<0||cursor.id()==null||!cursor.id().matches("[A-Za-z0-9_-]{1,64}")||cursor.archived()!=archived||!needle.equals(cursor.query()))throw new IllegalArgumentException();
            }catch(Exception invalid){throw new ApiException(400,"INVALID_CHAT_CURSOR","Invalid history cursor");}
            sql+=" and (c.updated_at < ? or (c.updated_at = ? and c.id > ?))";
            args.add(cursor.time());args.add(cursor.time());args.add(cursor.id());
        }
        var rows=db.queryForList(sql+" order by c.updated_at desc,c.id limit 51",args.toArray());
        boolean more=rows.size()>50;
        if(more)rows=new ArrayList<>(rows.subList(0,50));
        String next="";
        if(more) {
            var last=rows.get(rows.size()-1);
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(encode(new Cursor(((Number)last.get("updated_at")).longValue(),(String)last.get("id"),archived,needle)).getBytes(StandardCharsets.UTF_8));
        }
        if(!needle.isEmpty())addSearchMatches(rows,needle);
        return Map.of("items",rows,"next",next,"historyManagement",true,"searchHits",true);
    }
    private String pattern(String needle) {
        return "%"+needle.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    private static String excerpt(String text,String needle) {
        int match=-1;
        for(int i=0;i<=text.length()-needle.length();i++)if(text.regionMatches(true,i,needle,0,needle.length())){match=i;break;}
        int start=Math.max(0,match-64),end=Math.min(text.length(),Math.max(0,match)+needle.length()+128);
        if(start>0&&Character.isLowSurrogate(text.charAt(start)))start--;
        if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end++;
        return (start>0?"…":"")+text.substring(start,end)+(end<text.length()?"…":"");
    }
    private void addSearchMatches(List<Map<String,Object>> rows,String needle) {
        if(rows.isEmpty())return;
        // One bounded query for the page; never fetch each chat's turns separately.
        String slots=String.join(",",Collections.nCopies(rows.size(),"?"));
        var args=new ArrayList<Object>();for(var row:rows)args.add(row.get("id"));args.add(pattern(needle));args.add(pattern(needle));
        var hits=db.queryForList("select * from (select t.chat_id,t.id,t.user_text,t.reply_text,t.created_at,row_number() over(partition by t.chat_id order by t.ordinal desc) as hit_rank from mobile_chat_turns t where t.chat_id in ("+slots+") and (lower(t.user_text) like ? escape '!' or lower(t.reply_text) like ? escape '!')) ranked where hit_rank=1",args.toArray());
        var byChat=new HashMap<Object,Map<String,Object>>();for(var hit:hits)byChat.put(hit.get("chat_id"),hit);
        for(var row:rows) {
            var hit=byChat.get(row.get("id"));var value=new LinkedHashMap<String,Object>();
            if(hit==null) {
                value.put("field","title");value.put("excerpt",excerpt((String)row.get("title"),needle));value.put("created_at",row.get("updated_at"));
            } else {
                String userText=(String)hit.get("user_text"),replyText=(String)hit.get("reply_text");
                boolean userMatch=userText.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
                value.put("field",userMatch?"user":"reply");value.put("turnId",hit.get("id"));
                value.put("excerpt",excerpt(userMatch?userText:replyText,needle));value.put("created_at",hit.get("created_at"));
            }
            row.put("search_match",value);
        }
    }
    private Map<String,Object> chat(Identity actor,String id) {
        var rows=db.queryForList("select * from mobile_chats where id=? and tenant_id=? and owner_id=? for update",id,actor.tenant(),actor.id());
        if(rows.isEmpty())throw ApiException.missing();
        return rows.get(0);
    }
    public Map<String,Object> update(Identity actor,String chatId,Update body) {
        var live=user(actor);
        if(!chatId.matches("[A-Za-z0-9_-]{1,64}")||body.id()==null||!body.id().matches("[A-Za-z0-9_-]{1,64}")||body.expectedHistoryRevision()<0||(body.title()==null&&body.archived()==null))
            throw new ApiException(400,"INVALID_CHAT_HISTORY","Invalid history update");
        String title=body.title()==null?null:body.title().strip().replaceAll("\\s+"," ");
        if(title!=null&&(title.isEmpty()||title.length()>160))throw new ApiException(400,"INVALID_CHAT_TITLE","Invalid conversation title");
        var payload=new LinkedHashMap<String,Object>();payload.put("chatId",chatId);payload.put("update",body);
        String hash=ModelMiddleware.requestDigest(encode(payload).getBytes(StandardCharsets.UTF_8));
        return tx.execute(status->{
            // Reused request IDs across different chats are serialized per account as well.
            db.queryForList("select id from principals where tenant_id=? and id=? for update",live.tenant(),live.id());user(live);
            var row=chat(live,chatId);
            var prior=db.queryForList("select request_hash,result_json from mobile_chat_history_updates where tenant_id=? and owner_id=? and request_id=?",live.tenant(),live.id(),body.id());
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.get(0).get("request_hash")))throw ApiException.conflict("CHAT_HISTORY_REQUEST_CONFLICT");
                try{return json.readValue((String)prior.get(0).get("result_json"),new TypeReference<Map<String,Object>>(){});}catch(Exception invalid){throw new IllegalStateException(invalid);}
            }
            if(((Number)row.get("history_revision")).longValue()!=body.expectedHistoryRevision())throw ApiException.conflict("CHAT_HISTORY_REVISION_CONFLICT");
            db.update("update mobile_chats set title=?,archived=?,history_revision=history_revision+1 where id=?",title==null?row.get("title"):title,body.archived()==null?row.get("archived"):body.archived(),chatId);
            var result=summary(chat(live,chatId));
            db.update("insert into mobile_chat_history_updates values(?,?,?,?,?,?,?)",live.tenant(),live.id(),body.id(),chatId,hash,encode(result),clock.millis());
            return result;
        });
    }
}
