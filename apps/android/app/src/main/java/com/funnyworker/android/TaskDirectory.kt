package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject

enum class TaskStage { APPROVAL, QUESTION, WAITING, RUNNING, ONLINE, OFFLINE, UNKNOWN }
data class SearchCursor(val session: String = "", val sequence: Long = -1) {
    fun follows(other: SearchCursor) = session > other.session || session == other.session && sequence > other.sequence
}
data class TaskSearchHit(val conversation: Row, val sequence: Long, val excerpt: String) {
    val id get() = "${conversation.id}:$sequence"
}
data class TaskSearchPage(val items: List<TaskSearchHit>, val cursor: SearchCursor, val more: Boolean) {
    companion object {
        fun parse(root: JSONObject, before: SearchCursor): TaskSearchPage {
            val rows = root.getJSONArray("items"); require(rows.length() <= 64)
            val session = root.getString("afterSession"); require(session.isEmpty() || ServiceIdentity.validId(session))
            val sequence = root.opt("afterSeq") as? Number ?: error("Invalid search cursor")
            val next = SearchCursor(session, sequence.toLong())
            require(next.sequence >= -1 && next.sequence <= 9007199254740991L && sequence.toDouble() == next.sequence.toDouble())
            require(root.opt("hasMore") is Boolean)
            val more = root.getBoolean("hasMore")
            require(next.follows(before) || !more && rows.length() == 0 && next == before)
            var previous = before
            val items = rows.objects().map { value ->
                val raw = value.getJSONObject("conversation"); val id = raw.getString("id"); require(ServiceIdentity.validId(id))
                val seq = requireNotNull(value.sequence("seq")); val position = SearchCursor(id, seq)
                require(position.follows(previous) && !position.follows(next)); previous = position
                val excerpt = value.getString("excerpt"); require(excerpt.length <= 512)
                TaskSearchHit(Row(id, raw.text("title"), raw), seq, excerpt)
            }
            require(items.size == rows.length())
            return TaskSearchPage(items, next, more)
        }
    }
}

object TaskDirectory {
    fun page(array: JSONArray, after: String): List<Row> {
        require(array.length() <= 100); var cursor = after
        return array.objects().map { row ->
            val id = row.getString("id"); require(ServiceIdentity.validId(id) && id > cursor); cursor = id
            Row(id, row.text("title"), row)
        }.also { require(it.size == array.length()) }
    }
    fun stage(row: Row, fresh: Boolean, received: Long, now: Long, online: Boolean = row.json.optBoolean("online")): TaskStage {
        if (!fresh || now < received) return TaskStage.UNKNOWN
        if (!online) return TaskStage.OFFLINE
        val remaining = row.json.sequence("task_state_remaining_ms") ?: 0
        if (remaining in 1..30000 && now - received < remaining) when (row.json.text("task_state")) {
            "approval" -> return TaskStage.APPROVAL
            "question" -> return TaskStage.QUESTION
            "waiting" -> return TaskStage.WAITING
            "running" -> return TaskStage.RUNNING
        }
        return TaskStage.ONLINE
    }
    fun unread(row: Row, read: Long) = (row.json.sequence("next_seq") ?: 0) > read
    fun titles(rows: List<Row>, query: String) = rows.filter {
        it.title.contains(query.trim(), ignoreCase = true) || remoteHostName(it.json.text("device_name"), it.json.text("device_id")).contains(query.trim(), ignoreCase = true)
    }
}
