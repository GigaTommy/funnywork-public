package com.funnywork;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Explicit account-owned links from a phone chat to a separately created desktop task. */
@Service
public class MobileChatResultLinks {
    public record Link(String sessionId) {}
    private final JdbcTemplate db;private final AuthService auth;private final Clock clock;
    public MobileChatResultLinks(JdbcTemplate db,AuthService auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
    private Identity owner(Identity actor,String chat){var live=auth.current(actor);if(!"USER".equals(live.kind()))throw ApiException.forbidden();if(db.queryForObject("select count(*) from mobile_chats where id=? and tenant_id=? and owner_id=?",Integer.class,chat,live.tenant(),live.id())!=1)throw ApiException.missing();return live;}
    @Transactional public void link(Identity actor,String chat,Link body){
        var live=owner(actor,chat);if(body==null||body.sessionId()==null)throw ApiException.missing();
        db.queryForObject("select id from mobile_chats where id=? for update",String.class,chat);
        if(db.queryForObject("select count(*) from sync_sessions where id=? and tenant_id=? and principal_id=?",Integer.class,body.sessionId(),live.tenant(),live.id())!=1)throw ApiException.missing();
        if(db.queryForObject("select count(*) from mobile_chat_result_links where chat_id=? and session_id=?",Integer.class,chat,body.sessionId())==0)db.update("insert into mobile_chat_result_links values(?,?,?)",chat,body.sessionId(),clock.millis());
    }
    public List<Map<String,Object>> list(Identity actor,String chat){
        var live=owner(actor,chat);
        var rows=db.queryForList("select s.id,s.title,s.title_revision,s.archived,s.archive_revision,s.next_seq,s.updated_at,s.device_id,d.name as device_name,d.last_seen,d.revoked from mobile_chat_result_links l join sync_sessions s on s.id=l.session_id join sync_devices d on d.id=s.device_id where l.chat_id=? and s.tenant_id=? and s.principal_id=? order by l.created_at,s.id limit 100",chat,live.tenant(),live.id());
        long now=clock.millis();for(var row:rows){row.put("online",!Boolean.TRUE.equals(row.remove("revoked"))&&now-((Number)row.remove("last_seen")).longValue()<=60000);}
        return rows;
    }
}
