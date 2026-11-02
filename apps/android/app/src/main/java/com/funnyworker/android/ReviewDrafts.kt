package com.funnyworker.android

import org.json.JSONObject

/** Preserve each frozen round independently, including the legacy active draft. */
object ReviewDrafts {
    fun rounds(account: JSONObject, task: String): List<JSONObject> =
        (account.optJSONObject("reviewArchive")?.optJSONObject(task)?.let { rows ->
            rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.toList()
        }.orEmpty() + listOfNotNull(account.optJSONObject("reviews")?.optJSONObject(task)))
            .filter { it.sequence("end") != null }.associateBy { it.getLong("end") }.values.sortedByDescending { it.getLong("end") }

    fun select(account: JSONObject, task: String, snapshot: JSONObject): JSONObject {
        val end = requireNotNull(snapshot.sequence("end"))
        val next = JSONObject(account.toString())
        val active = next.optJSONObject("reviews") ?: JSONObject().also { next.put("reviews", it) }
        val archive = next.optJSONObject("reviewArchive") ?: JSONObject().also { next.put("reviewArchive", it) }
        val saved = archive.optJSONObject(task) ?: JSONObject().also { archive.put(task, it) }
        active.optJSONObject(task)?.let { current -> requireNotNull(current.sequence("end")); saved.put(current.getLong("end").toString(), current) }
        val selected = saved.optJSONObject(end.toString()) ?: JSONObject(snapshot.toString()).put("comment", "").put("notes", JSONObject())
        require(selected.sequence("end") == end)
        active.put(task, JSONObject(selected.toString()))
        return next
    }

    /** One account write appends only the selected round and consumes only that draft. */
    fun stage(account: JSONObject, task: String, title: String): JSONObject {
        val current = requireNotNull(account.optJSONObject("reviews")?.optJSONObject(task))
        val feedback = ReviewFeedback.text(current, title)
        val next = JSONObject(account.toString())
        val drafts = next.optJSONObject("drafts") ?: JSONObject().also { next.put("drafts", it) }
        drafts.put("desktop:$task", listOf(drafts.text("desktop:$task"), feedback).filter { it.isNotBlank() }.joinToString("\n\n"))
        next.getJSONObject("reviews").remove(task)
        next.optJSONObject("reviewArchive")?.optJSONObject(task)?.remove(current.getLong("end").toString())
        return next
    }
}
