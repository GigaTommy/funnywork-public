package com.funnyworker.android

import org.json.JSONObject

data class DesktopEvent(val seq: Long, val type: String, val text: String, val user: Boolean, val data: JSONObject, val content: List<JSONObject>)
data class DesktopPage(val events: List<DesktopEvent>, val next: Long, val durable: Long)
object RemoteHistory {
    /** Runtime/tool context uses user/message too, but is not a user or assistant reply. */
    fun messages(events: List<DesktopEvent>): List<DesktopEvent> = events.filter {
        it.text.isNotBlank() && when (it.type) {
            "user/message" -> it.user
            "assistant/message", "assistant/text", "content/message" -> true
            else -> false
        }
    }
    fun page(page: JSONObject, offset: Long): DesktopPage {
        val rows = page.getJSONArray("events").objects()
        require(rows.size == page.getJSONArray("events").length())
        val next = requireNotNull(page.sequence("nextSeq")); val durable = requireNotNull(page.sequence("durableNextSeq"))
        require(next == offset + rows.size && durable >= next)
        val events = rows.mapIndexed { index, row ->
            val seq = requireNotNull(row.sequence("seq")); require(seq == offset + index)
            val event = row.optJSONObject("event") ?: row
            val type = event.text("type").ifBlank { event.text("event") }
            val data = event.optJSONObject("data") ?: JSONObject()
            val message = data.optJSONObject("message") ?: data
            val parts = message.optJSONArray("content")?.objects().orEmpty()
            DesktopEvent(seq, type, parts.filter { it.text("type").let { kind -> kind.isEmpty() || kind == "text" } }
                .mapNotNull { (it.opt("text") as? String) }.joinToString("\n"),
                type == "user/message" && (message.optJSONObject("source")?.opt("kind") as? String).let { it == null || it == "user" }, data, parts)
        }
        return DesktopPage(events, next, durable)
    }
    /** Read a fixed sequence window even when the server ends a page at its byte limit. */
    suspend fun range(start: Long, end: Long, fetch: suspend (Long, Int) -> JSONObject): DesktopPage {
        require(start >= 0 && end >= start)
        var cursor = start; var durable = end
        val events = mutableListOf<DesktopEvent>()
        while (cursor < end) {
            val batch = page(fetch(cursor, minOf(100L, end - cursor).toInt()), cursor)
            require(batch.next > cursor && batch.next <= end && batch.durable >= end)
            events.addAll(batch.events); cursor = batch.next; durable = batch.durable
        }
        return DesktopPage(events, cursor, durable)
    }
    fun merge(previous: List<DesktopEvent>, incoming: List<DesktopEvent>): List<DesktopEvent> = (previous + incoming).associateBy { it.seq }.values.sortedBy { it.seq }
}

data class ReviewChange(val key: String, val path: String, val patch: String)
object ReviewChanges {
    fun parse(seq: Long, text: String): List<ReviewChange> = Regex("```(?:diff|patch)[^\\n]*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE).findAll(text).flatMapIndexed { index, match ->
        val parts = mutableListOf<String>(); val current = mutableListOf<String>()
        val lines = ReviewDiff.parse(match.groupValues[1])
        for ((offset, parsed) in lines.withIndex()) {
            val line = parsed.text
            val fileHeader = line.startsWith("--- ") && parsed.old == null && parsed.new == null &&
                lines.getOrNull(offset + 1)?.let { it.text.startsWith("+++ ") && it.old == null && it.new == null } == true && current.any { it.startsWith("+++ ") }
            if ((line.startsWith("diff --git ") || fileHeader) && current.isNotEmpty()) { parts += current.joinToString("\n"); current.clear() }
            current += line
        }
        if (current.isNotEmpty()) parts += current.joinToString("\n")
        parts.mapIndexed { part, patch ->
            val lines = patch.lines()
            val raw = lines.firstOrNull { it.startsWith("+++ ") && it != "+++ /dev/null" }
                ?: lines.firstOrNull { it.startsWith("--- ") && it != "--- /dev/null" }
            val path = raw?.drop(4)?.substringBefore('\t')?.removePrefix("a/")?.removePrefix("b/") ?: "#${seq}-$index-$part"
            ReviewChange("#$seq · $path · $index-$part", path, patch)
        }.asSequence()
    }.toList()
}

/** Freeze exact synced sequence numbers; gaps and turn boundaries may never be crossed. */
data class ReviewSnapshot(val end: Long, val sequences: List<Long>, val messages: List<DesktopEvent>) {
    val key: String get() = end.toString()
    fun json() = JSONObject().put("end", end).put("sequences", org.json.JSONArray(sequences)).put("messages", org.json.JSONArray(messages.map { JSONObject()
        .put("seq", it.seq).put("text", it.text).put("data", it.data).put("content", org.json.JSONArray(it.content)) }))
    companion object {
        fun rounds(events: List<DesktopEvent>): List<ReviewSnapshot> {
            val result = mutableListOf<ReviewSnapshot>(); val messages = mutableListOf<DesktopEvent>()
            var previous: Long? = null
            for (event in events.sortedBy { it.seq }) {
                if (previous?.let { event.seq != it + 1 } == true) messages.clear()
                when (event.type) {
                    "turn/start" -> messages.clear()
                    "turn/end" -> { result += ReviewSnapshot(event.seq, messages.map { it.seq }, messages.toList()); messages.clear() }
                    "assistant/message" -> if (event.text.isNotBlank() || event.content.any { it.has("attachment") }) messages += event
                }
                previous = event.seq
            }
            return result.asReversed()
        }
        fun latest(events: List<DesktopEvent>): ReviewSnapshot? {
            val sorted = events.sortedBy { it.seq }
            val end = sorted.lastOrNull { it.type == "turn/end" }?.seq ?: sorted.lastOrNull()?.seq ?: return null
            val prior = sorted.lastOrNull { it.seq < end && it.type in setOf("turn/start", "turn/end") }?.seq ?: -1
            val gap = sorted.zipWithNext().filter { (a, b) -> b.seq <= end && b.seq != a.seq + 1 }.lastOrNull()?.second?.seq ?: 0
            val messages = sorted.filter { it.seq > maxOf(prior, gap - 1) && it.seq <= end && it.type == "assistant/message" && (it.text.isNotBlank() || it.content.any { p -> p.has("attachment") }) }
            if (messages.isEmpty() && sorted.none { it.seq == end && it.type == "turn/end" }) return null
            return ReviewSnapshot(end, messages.map { it.seq }, messages)
        }
    }
}
