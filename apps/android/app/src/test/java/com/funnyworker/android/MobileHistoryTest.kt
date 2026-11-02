package com.funnyworker.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MobileHistoryTest {
    @Test fun historyUpdatesUseSeparateRevisionAndStableRequestBody() {
        val row = Row("chat", "old", JSONObject().put("history_revision", 7).put("revision", 99))
        val request = MobileHistory.request(row, " new title ", null, "request-1")
        val restored = JSONObject(request.toString())
        assertEquals(7L, MobileHistory.body(restored).getLong("expectedHistoryRevision"))
        assertEquals("request-1", MobileHistory.body(restored).getString("id"))
        assertEquals("new title", restored.getString("title"))
        assertFalse(MobileHistory.body(restored).has("chatID")); assertFalse(restored.has("archived"))
        assertThrows(IllegalArgumentException::class.java) { MobileHistory.request(row, " ", null, "r") }
        assertThrows(IllegalArgumentException::class.java) { MobileHistory.request(row.copy(json = JSONObject().put("history_revision", true)), null, true, "r") }
    }
    @Test fun attentionAndRunningOverrideDateWithoutLosingCalendarBoundaries() {
        val zone = ZoneId.of("Asia/Shanghai"); val today = LocalDate.of(2026,10,23)
        fun row(day: LocalDate) = Row("chat", "chat", JSONObject().put("updated_at", day.atStartOfDay(zone).toInstant().toEpochMilli()))
        assertEquals(R.string.history_today, MobileHistory.group(row(today), emptyMap(), today, zone))
        assertEquals(R.string.history_yesterday, MobileHistory.group(row(today.minusDays(1)), emptyMap(), today, zone))
        assertEquals(R.string.history_attention, MobileHistory.group(row(today.minusDays(10)), mapOf("chat" to TurnPhase.NEEDS_CHECK.name), today, zone))
        assertEquals(R.string.history_running, MobileHistory.group(row(today), mapOf("chat" to TurnPhase.EXECUTING.name), today, zone))
    }
    private fun wallet() = JSONObject().put("configured",true).put("enabled",true).put("remaining_micros",1234567)
        .put("held",200000).put("daily_limit",1000000).put("daily_spent",850000).put("expires_at",1799999999999)
        .put("promotion",JSONObject().put("mode","DISABLED").put("available_micros",0))
    @Test fun moneyIsExactAndReservationsCannotMakeDailyAllowanceNegative() {
        val value = ModelAllowance.parse(wallet())
        assertEquals("¥1.234567", ModelAllowance.money(value.remaining)); assertEquals(0L,value.dailyRemaining)
        assertEquals("¥0.000001",ModelAllowance.money(1))
        assertThrows(IllegalArgumentException::class.java) { ModelAllowance.parse(wallet().put("held",true)) }
        assertThrows(IllegalArgumentException::class.java) { ModelAllowance.parse(wallet().put("remaining_micros",-1)) }
        assertThrows(IllegalArgumentException::class.java) { ModelAllowance.parse(wallet().also { it.remove("remaining_micros") }) }
    }
    @Test fun incompleteUsageIsUnknownAndCachedTokensAreNotAddedToTotal() {
        val known = JSONObject().put("usage_quality","COMPLETE").put("input_tokens",30).put("output_tokens",20).put("cached_tokens",10)
        val unknown = JSONObject().put("started_at",123).put("usage_quality","UNKNOWN").put("input_tokens",0)
        val value = PhoneUsage.parse(listOf(known,unknown))
        assertEquals(1,value.calls);assertEquals(30L,value.input);assertEquals(20L,value.output);assertEquals(10L,value.cached);assertEquals(1,value.unconfirmed)
        assertNull(PhoneUsage.parse(listOf(JSONObject(known.toString()).also { it.remove("cached_tokens") })).cached)
        assertEquals(0, PhoneUsage.parse(listOf(JSONObject(known.toString()).put("cached_tokens",100))).calls)
    }
}
