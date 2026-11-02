package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Database-call budget, independent of driver batch-rewrite optimizations. */
class DesktopHistoryBatchPerformanceTest {
    @Test void oneHundredEventsUseOneBatchAndReplayUsesOneRangeRead() throws Exception {
        var db=mock(JdbcTemplate.class);var auth=mock(AuthService.class);var mapper=new ObjectMapper();
        var actor=new Identity("owner","tenant","USER",Set.of("VIEWER"),Set.of("sandbox"),1);
        when(auth.current(actor)).thenReturn(actor);
        var row=new HashMap<String,Object>();row.put("next_seq",0L);row.put("title","");row.put("device_id","device");
        when(db.queryForList(startsWith("select s.*"),anyString(),anyString(),anyString())).thenReturn(List.of(row));
        when(db.queryForList(startsWith("select * from sync_devices"),anyString(),anyString(),anyString())).thenReturn(List.of(row));
        var sync=new DesktopHistorySync(db,auth,mapper,Clock.systemUTC(),mock(DesktopAttachments.class),mock(DesktopNotifications.class));
        var events=new ArrayList<JsonNode>();var stored=new ArrayList<Map<String,Object>>();
        for(int i=0;i<100;i++){
            var event=mapper.readTree("{\"seq\":"+i+",\"time\":1,\"type\":\"user/message\",\"data\":{\"content\":[]}}");
            events.add(event);stored.add(Map.of("seq",(long)i,"event_json",event.toString()));
        }
        sync.append(actor,"session",new DesktopHistorySync.Batch("device",events));
        verify(db,times(1)).batchUpdate(startsWith("insert into sync_session_events"),anyList());
        assertEquals(0,mockingDetails(db).getInvocations().stream().filter(call->call.getMethod().getName().equals("update")&&call.getArgument(0).toString().startsWith("insert into sync_session_events")).count());
        row.put("next_seq",100L);
        when(db.queryForList(startsWith("select seq,event_json"),anyString(),anyLong(),anyLong())).thenAnswer(call->{
            long from=call.getArgument(2),until=call.getArgument(3);
            return stored.stream().filter(record->((Number)record.get("seq")).longValue()>=from&&((Number)record.get("seq")).longValue()<until).toList();
        });
        clearInvocations(db);
        sync.append(actor,"session",new DesktopHistorySync.Batch("device",events));
        verify(db,times(1)).queryForList(startsWith("select seq,event_json"),anyString(),anyLong(),anyLong());
        assertEquals(0,mockingDetails(db).getInvocations().stream().filter(call->call.getMethod().getName().equals("batchUpdate")).count());
        assertEquals(0,mockingDetails(db).getInvocations().stream().filter(call->call.getMethod().getName().equals("queryForObject")&&call.getArgument(0).toString().contains("sync_session_events")).count());
        clearInvocations(db);
        sync.append(actor,"session",new DesktopHistorySync.Batch("device",List.of(events.get(0))));
        verify(db,times(1)).queryForList(startsWith("select seq,event_json"),eq("session"),eq(0L),eq(1L));
    }
}
