package com.funnyworker.android

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.math.BigDecimal

/** History metadata is independent of inference revisions and must never stop a turn. */
internal object MobileHistory {
    fun request(row: Row, title: String?, archived: Boolean?, id: String): JSONObject {
        require(ServiceIdentity.validId(row.id) && ServiceIdentity.validId(id))
        require(title != null || archived != null)
        val revision = requireNotNull(row.json.sequence("history_revision"))
        return JSONObject().put("chatID", row.id).put("id", id).put("expectedHistoryRevision", revision).also {
            if (title != null) { val value = title.trim(); require(value.isNotEmpty() && value.length <= 160); it.put("title", value) }
            if (archived != null) it.put("archived", archived)
        }
    }
    fun body(request: JSONObject) = JSONObject(request.toString()).also { it.remove("chatID") }
    fun group(row: Row, phases: Map<String, String>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int {
        if (phases[row.id] == TurnPhase.NEEDS_CHECK.name) return R.string.history_attention
        if (row.id in phases) return R.string.history_running
        val time = row.json.sequence("updated_at") ?: row.json.sequence("updatedAt") ?: return R.string.history_earlier
        val date = runCatching { Instant.ofEpochMilli(time).atZone(zone).toLocalDate() }.getOrNull()
        return when { date == null -> R.string.history_earlier; date >= today -> R.string.history_today; date >= today.minusDays(1) -> R.string.history_yesterday; else -> R.string.history_earlier }
    }
    val groups = listOf(R.string.history_attention, R.string.history_running, R.string.history_today, R.string.history_yesterday, R.string.history_earlier)
    fun metadata(row: JSONObject): JSONObject {
        require(ServiceIdentity.validId(row.text("id")) && row.opt("title") is String)
        require(row.sequence("history_revision") != null && row.opt("archived") is Boolean)
        return row
    }
}
data class SearchLocation(val chat: String, val turn: String, val field: String, val query: String, val navigation: String = java.util.UUID.randomUUID().toString()) {
    val messageId get() = if (turn.isEmpty()) "" else "$turn-${if (field == "user") "user" else "reply"}"
}
data class ModelAllowance(val configured: Boolean, val enabled: Boolean, val remaining: Long, val held: Long,
    val dailyRemaining: Long, val expires: Long?, val claimable: Long, val credited: Long = 0) {
    val active get() = configured && enabled && (expires ?: 0) > System.currentTimeMillis()
    companion object {
        fun money(value: Long) = "¥" + BigDecimal.valueOf(value, 6).toPlainString()
        fun parse(envelope: JSONObject, claim: Boolean = false): ModelAllowance {
            val row = if (claim) envelope.getJSONObject("budget") else envelope
            require(row.opt("configured") is Boolean && row.opt("enabled") is Boolean)
            val configured = row.getBoolean("configured"); val promo = row.getJSONObject("promotion")
            require(promo.text("mode") in setOf("DISABLED", "WELCOME", "DAILY"))
            fun amount(key: String) = requireNotNull(row.sequence(key))
            val held = if (configured) amount("held") else 0
            return ModelAllowance(configured, row.getBoolean("enabled"), amount("remaining_micros"), held,
                if (configured) (amount("daily_limit") - amount("daily_spent") - held).coerceAtLeast(0) else 0,
                if (configured) amount("expires_at") else null, requireNotNull(promo.sequence("available_micros")),
                if (claim) requireNotNull(envelope.sequence("credited_micros")) else 0)
        }
    }
}
data class PhoneUsage(val calls: Int = 0, val input: Long = 0, val output: Long = 0, val cached: Long? = null, val unconfirmed: Int = 0) {
    companion object {
        fun parse(turns: List<JSONObject>): PhoneUsage {
            var result = PhoneUsage(); var cacheKnown = true; var cached = 0L
            for (turn in turns) {
                val input = turn.sequence("input_tokens"); val output = turn.sequence("output_tokens"); val hit = turn.sequence("cached_tokens")
                if (turn.text("usage_quality") == "COMPLETE" && input != null && output != null && input <= 9007199254740991L - output && (hit == null || hit <= input) && (!turn.has("cached_tokens") || turn.isNull("cached_tokens") || hit != null)) {
                    result = result.copy(calls = result.calls + 1, input = Math.addExact(result.input, input), output = Math.addExact(result.output, output))
                    if (hit == null) cacheKnown = false else cached = Math.addExact(cached, hit)
                } else if (turn.sequence("started_at") != null) result = result.copy(unconfirmed = result.unconfirmed + 1)
            }
            return result.copy(cached = if (cacheKnown && result.calls > 0) cached else null)
        }
    }
}
