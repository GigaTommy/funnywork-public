package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.ArrayList;

/** Restartable, bounded backfill. Immutable events remain the source of truth. */
@Service
public class HistoryTextProjection {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int batchSize;
    public HistoryTextProjection(JdbcTemplate db,ObjectMapper mapper,PlatformTransactionManager transactions,
            @Value("${funnywork.scheduling-enabled:true}") boolean enabled,
            @Value("${funnywork.sync.projection-batch-size:32}") int batchSize) {
        if(batchSize<1||batchSize>100)throw new IllegalArgumentException("Invalid history projection batch size");
        this.db=db;this.mapper=mapper;this.tx=new TransactionTemplate(transactions);this.enabled=enabled;this.batchSize=batchSize;
    }
    @Scheduled(fixedDelayString="${funnywork.sync.projection-delay-ms:5000}")
    public void scheduled(){if(enabled)runBatch();}
    public int runBatch(){
        return tx.execute(status->{
            var rows=db.queryForList("select session_id,seq,event_json from sync_session_events where projection_version<2 order by projection_version,session_id,seq limit ?",batchSize);
            var updates=new ArrayList<Object[]>();
            for(var row:rows){
                try{var event=mapper.readTree((String)row.get("event_json"));updates.add(new Object[]{DesktopHistorySearch.visibleText(event),event.path("type").asText(),row.get("session_id"),row.get("seq")});}
                catch(Exception invalid){throw new IllegalStateException("Stored history event cannot be projected");}
            }
            // Concurrent workers can read the same immutable prefix. Conditional writes are idempotent.
            if(!updates.isEmpty())db.batchUpdate("update sync_session_events set visible_text=?,event_type=?,projection_version=2 where session_id=? and seq=? and projection_version<2",updates);
            return rows.size();
        });
    }
}
