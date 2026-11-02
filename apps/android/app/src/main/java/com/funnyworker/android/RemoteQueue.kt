package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject

data class RemoteQueueItem(val id: String, val text: String, val editable: Boolean)

/** Queue IDs belong to the current source epoch; edits preserve a separate local copy. */
object RemoteQueue {
    fun parse(rows: JSONArray?): List<RemoteQueueItem> {
        if (rows == null || rows.length() > 32) return emptyList()
        return rows.objects().mapNotNull {
            val id = it.text("id"); val text = it.opt("text") as? String
            if (id.isBlank() || id.toByteArray().size > 256 || text == null || text.length > 4000 || it.opt("editable") !is Boolean) null
            else RemoteQueueItem(id, text, it.getBoolean("editable"))
        }.distinctBy { it.id }
    }
    fun payload(remote: RemoteState, expected: RemoteQueueItem, action: String, text: String = ""): JSONObject {
        require(action in setOf("edit", "remove", "steer"))
        val current = remote.queue.single { it.id == expected.id }
        require(current.text == expected.text)
        require(if (action == "steer") remote.running else current.editable && expected.editable)
        require(action != "edit" || text.isNotBlank() && text.length <= 16000)
        return JSONObject().put("itemId", expected.id).put("action", action).also { if (action == "edit") it.put("text", text) }
    }
    fun waiting(records: List<JSONObject>, item: String? = null) = records.any {
        it.text("kind") == "queueEdit" && (item == null || it.optJSONObject("payload")?.text("itemId") == item) && it.text("status") !in RemoteCommands.terminal && !it.optBoolean("withdrawalManuallyRecovered")
    }
    fun withdrawing(records: List<JSONObject>) = records.any { it.has("withdrawalText") && !it.optBoolean("withdrawalApplied") && it.text("status") !in setOf("rejected", "expired") }
    fun canRecover(records: List<JSONObject>, now: Long = System.currentTimeMillis()): Boolean {
        val withdrawals = records.filter { it.has("withdrawalText") && !it.optBoolean("withdrawalApplied") && it.text("status") !in setOf("rejected", "expired") }
        return withdrawals.isNotEmpty() && withdrawals.all { it.text("status") == "uncertain" || now - it.optLong("createdAt", now) >= 30000 }
    }
    fun recover(records: List<JSONObject>, draft: String, now: Long = System.currentTimeMillis()): Pair<List<JSONObject>, String> {
        require(canRecover(records, now))
        var text = draft
        val next = records.map { JSONObject(it.toString()) }
        for (record in next.filter { it.has("withdrawalText") && !it.optBoolean("withdrawalApplied") && it.text("status") !in setOf("rejected", "expired") }.sortedBy { it.text("id") }) {
            text = if (text.isEmpty()) record.text("withdrawalText") else text + "\n\n" + record.text("withdrawalText")
            record.put("withdrawalApplied", true).put("withdrawalManuallyRecovered", true).put("pending", false)
        }
        return next to text
    }
    /** Return one atomic outbox/draft update; replaying the same receipt never duplicates text. */
    fun reconcile(record: JSONObject, receipt: JSONObject, draft: String): Pair<JSONObject, String> {
        val next = RemoteCommands.reconcile(record, receipt)
        if (next.has("withdrawalText") && !next.optBoolean("withdrawalApplied") && next.text("status") == "accepted") {
            next.put("withdrawalApplied", true)
            val text = next.text("withdrawalText")
            return next to if (draft.isEmpty()) text else "$draft\n\n$text"
        }
        return next to draft
    }
}
