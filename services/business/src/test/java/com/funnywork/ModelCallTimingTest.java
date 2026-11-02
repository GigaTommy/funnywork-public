package com.funnywork;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelCallTimingTest {
 private Map<String,Object> row(){return new HashMap<>(Map.of("ticket_state","SUCCEEDED","admitted_at",100L,"granted_at",200L,"started_at",250L,"ended_at",750L,"first_output_at",350L,"usage_quality","COMPLETE","output_tokens",5L));}
 @Test void queueDispatchAndDecodeAreSeparate(){var r=ModelCallTiming.of(row());assertEquals(100L,r.get("queue_ms"));assertEquals(50L,r.get("dispatch_ms"));assertEquals(500L,r.get("request_ms"));assertEquals(650L,r.get("total_ms"));assertEquals(100L,r.get("first_output_ms"));assertEquals(400L,r.get("decode_ms"));assertEquals(5L,r.get("decode_tokens"));}
 @Test void firstByteIsNotFirstOutputAndOldCallsDoNotInventTiming(){var row=row();row.remove("first_output_at");row.put("first_byte_at",260L);var r=ModelCallTiming.of(row);assertFalse(r.containsKey("first_output_ms"));assertFalse(r.containsKey("decode_ms"));assertEquals(500L,r.get("request_ms"));}
 @Test void unknownOrClockReversalDoesNotProduceTiming(){var row=row();row.put("ticket_state","UNKNOWN");assertTrue(ModelCallTiming.of(row).isEmpty());row=row();row.put("ended_at",240L);assertTrue(ModelCallTiming.of(row).isEmpty());}
 @Test void noSpeedForZeroIntervalOrUnconfirmedOutput(){var row=row();row.put("first_output_at",750L);assertFalse(ModelCallTiming.of(row).containsKey("decode_ms"));row=row();row.put("usage_quality","PARTIAL");assertFalse(ModelCallTiming.of(row).containsKey("decode_tokens"));assertEquals(100L,ModelCallTiming.of(row).get("first_output_ms"));}
}
