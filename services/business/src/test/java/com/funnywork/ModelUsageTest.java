package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ModelUsageTest {
 @Test void messagesPartialEventsMergeAndCachesAreDisjoint(){var u=new ModelUsage(new ObjectMapper(),"MESSAGES");u.event("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":5,\"cache_creation_input_tokens\":3,\"output_tokens\":0}}}");u.event("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}");u.event("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12}}");u.event("data: {\"type\":\"message_stop\"}");assertEquals(18L,u.input());assertEquals(12L,u.output());assertEquals(5L,u.cached());assertEquals("COMPLETE",u.quality());assertTrue(u.complete());}
 @Test void chunkedOpenAiSamplesReplaceAndReasoningIsNotAddedTwice(){var u=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS");String data="data: {\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"completion_tokens_details\":{\"reasoning_tokens\":2},\"prompt_tokens_details\":{\"cached_tokens\":10}}}\n\ndata: [DONE]\n";for(byte b:data.getBytes(StandardCharsets.UTF_8))u.bytes(new byte[]{b},1);assertEquals(20L,u.input());assertEquals(4L,u.output());assertEquals(10L,u.cached());assertTrue(u.complete());}
 @Test void missingUsageIsUnknownAndOversizedEventsDoNotAllocateUnboundedBuffers(){var u=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS");assertNull(u.input());assertEquals("UNKNOWN",u.quality());byte[] huge=("data: "+"x".repeat(70000)+"\n").getBytes(StandardCharsets.UTF_8);u.bytes(huge,huge.length);u.event("data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");assertEquals("PARTIAL",u.quality());}
 @Test void interruptedMessagesNeverClaimFinalOutputOrCompleteUsage(){var u=new ModelUsage(new ObjectMapper(),"MESSAGES");u.event("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");assertEquals(10L,u.input());assertNull(u.output());assertEquals("PARTIAL",u.quality());u.event("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}");assertEquals(8L,u.output());assertEquals("PARTIAL",u.quality());assertTrue(u.raw().contains("8"));u.event("data: {\"type\":\"message_stop\"}");assertEquals(8L,u.output());assertEquals("COMPLETE",u.quality());}
 @Test void terminalMessageWithoutFinalUsageDoesNotConfirmInitialZero(){var u=new ModelUsage(new ObjectMapper(),"MESSAGES");u.event("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");u.event("data: {\"type\":\"message_stop\"}");assertTrue(u.complete());assertNull(u.output());assertEquals("PARTIAL",u.quality());}
 @Test void nonStreamingMessagesHaveFinalUsage(){var u=new ModelUsage(new ObjectMapper(),"MESSAGES");u.response("{\"type\":\"message\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}".getBytes(StandardCharsets.UTF_8));assertEquals(0L,u.output());assertEquals("COMPLETE",u.quality());}
 @Test void deepSeekNativeCacheFieldsAreNormalizedAndConflictsNotTrusted(){
  var u=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS");u.event("data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}");u.event("data: [DONE]");assertEquals(80L,u.cached());assertEquals(100L,u.input());assertEquals("COMPLETE",u.quality());assertTrue(u.raw().contains("prompt_cache_hit_tokens"));
  u.event("data: {\"usage\":{\"prompt_tokens_details\":{\"cached_tokens\":70}}}");assertEquals("PARTIAL",u.quality());
 }
 @Test void cacheMissingIsDistinctFromZeroAndInvalidFieldsArePartial(){
  var u=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS");u.event("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}");u.event("data: [DONE]");assertNull(u.cached());assertEquals("COMPLETE",u.quality());
  u.event("data: {\"usage\":{\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":10}}");assertEquals(0L,u.cached());assertEquals("COMPLETE",u.quality());
  u.event("data: {\"usage\":{\"prompt_cache_miss_tokens\":9}}");assertEquals("PARTIAL",u.quality());
  u.event("data: {\"usage\":{\"prompt_tokens_details\":{\"cached_tokens\":true}}}");assertEquals("PARTIAL",u.quality());
 }
 @Test void outputTimingIgnoresEmptyHeadersAndUsageButIncludesReasoning(){
  var clock=java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1234),java.time.ZoneOffset.UTC);
  var u=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS",clock);
  u.event("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}");u.event("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");assertNull(u.firstOutputAt());
  u.event("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Thinking\"}}]}");assertEquals(1234L,u.firstOutputAt());u.event("data: [DONE]");assertEquals(1234L,u.firstOutputAt());
  var m=new ModelUsage(new ObjectMapper(),"MESSAGES",clock);m.event("data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}");assertEquals(1234L,m.firstOutputAt());
  var n=new ModelUsage(new ObjectMapper(),"CHAT_COMPLETIONS",clock);n.response("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes(StandardCharsets.UTF_8));assertNull(n.firstOutputAt());
 }
}
