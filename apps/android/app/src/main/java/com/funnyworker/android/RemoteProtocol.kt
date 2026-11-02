package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun JSONObject.text(key: String): String = opt(key) as? String ?: ""
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.sequence(key: String): Long? = (opt(key) as? Number)?.let {
    val value = it.toLong()
    value.takeIf { value >= 0 && value <= 9007199254740991L && it.toDouble() == value.toDouble() }
}

data class RemoteQuestion(val id: String, val title: String, val detail: String, val multiple: Boolean, val options: List<Pair<String, String>>)
data class RemoteInteraction(val id: String, val correlation: String, val epoch: String, val kind: String,
    val title: String, val reason: String, val detail: String, val questions: List<RemoteQuestion>)
data class RemoteState(val online: Boolean, val epoch: String, val running: Boolean, val turn: Long?,
    val interactions: List<RemoteInteraction>, val attachmentUploads: Boolean, val queue: List<RemoteQueueItem> = emptyList()) {
    companion object {
        fun parse(root: JSONObject): RemoteState {
            require(root.opt("online") is Boolean && root.opt("epoch") is String)
            val state = root.optJSONObject("state") ?: JSONObject()
            val epoch = root.text("epoch")
            val interactions = mutableListOf<RemoteInteraction>()
            fun add(value: JSONObject, kind: String, correlation: String, request: JSONObject) {
                if (correlation.isBlank() || correlation.length > 128) return
                val questions = request.optJSONArray("questions")?.objects().orEmpty().mapNotNull { question ->
                    val id = question.text("id"); val title = question.text("question")
                    if (id.isBlank() || title.isBlank()) null else RemoteQuestion(id, title, question.text("detail"), question.optBoolean("multiSelect"),
                        question.optJSONArray("options")?.objects().orEmpty().mapNotNull {
                            it.text("label").takeIf(String::isNotBlank)?.let { label -> label to it.text("description") }
                        }.distinctBy { it.first })
                }
                interactions += RemoteInteraction("$epoch/${if (kind == "continued") "call" else "event"}/$correlation", correlation, epoch, kind,
                    request.text("toolName"), request.text("reason"), if (kind == "approval") request.toString(2) else value.text("detail"), questions)
            }
            for (item in state.optJSONArray("pending")?.objects().orEmpty()) {
                val request = item.optJSONObject("request") ?: continue
                when (item.text("event")) {
                    "approval/request" -> add(item, "approval", item.text("eventId"), request)
                    "user-questions/request" -> add(item, "questions", item.text("eventId"), request)
                }
            }
            for (item in state.optJSONObject("projections")?.optJSONObject("userQuestions")?.optJSONArray("active")?.objects().orEmpty())
                if (item.text("state") == "continued") add(item, "continued", item.text("callId"), item)
            return RemoteState(root.optBoolean("online") && ServiceIdentity.validId(epoch) && state.opt("controllable") != false,
                epoch, state.optBoolean("running"), state.sequence("turnStart"), interactions.distinctBy { it.id }, root.optBoolean("attachmentUploads"), RemoteQueue.parse(state.optJSONArray("queue")))
        }
    }
}

/** Payloads follow the iOS wire contract; UI labels are never sent as protocol choices. */
object RemoteCommands {
    val received = setOf("queued", "claimed", "accepted")
    val terminal = setOf("accepted", "rejected", "expired")
    val statuses = received + terminal + "uncertain"
    /** A stale snapshot cannot make an already submitted or expired interaction actionable again. */
    fun blocksInteraction(record: JSONObject, interaction: String): Boolean = record.text("interaction") == interaction &&
        (record.optBoolean("admissionRejected") || record.text("status") in setOf("unknown", "queued", "claimed", "accepted", "uncertain"))
    /** These exact admission failures happen before insertion. A timeout/404 is not such proof. */
    fun admissionRejected(record: JSONObject, failure: ApiFailure): JSONObject? {
        if (failure.status != 409 || failure.code !in setOf("SYNC_DECISION_EXPIRED", "SYNC_DEVICE_OFFLINE")) return null
        return JSONObject(record.toString()).put("status", "rejected").put("pending", false)
            .put("admissionRejected", true).put("admissionCode", failure.code)
    }
    fun prompt(text: String, steer: Boolean, state: RemoteState, attachments: JSONArray = JSONArray()): Pair<String, JSONObject> {
        require((text.isNotBlank() || attachments.length() > 0) && text.length <= 16000)
        require(!steer || state.running && state.turn != null)
        val payload = JSONObject().put("text", text).put("mode", if (steer) "steer" else "queue")
        if (attachments.length() > 0) { require(state.attachmentUploads); payload.put("attachments", attachments) }
        if (steer) payload.put("expectedTurn", state.turn)
        return "prompt" to payload
    }
    fun cancel(state: RemoteState): Pair<String, JSONObject> {
        require(state.running && state.turn != null)
        return "cancel" to JSONObject().put("expectedTurn", state.turn)
    }
    fun decision(item: RemoteInteraction, allow: Boolean): Pair<String, JSONObject> {
        require(item.kind == "approval")
        return "decision" to JSONObject().put("eventId", item.correlation).put("outcome", if (allow) "allowed-once" else "rejected")
    }
    fun validAnswer(question: RemoteQuestion, answer: JSONObject): Boolean {
        val selected = answer.optJSONArray("selected") ?: JSONArray()
        val labels = (0 until selected.length()).map { selected.opt(it) as? String ?: return false }
        return answer.text("custom").length <= 8000 && (question.multiple || labels.size <= 1) &&
            labels.distinct().size == labels.size && labels.all { label -> question.options.any { it.first == label } } &&
            (labels.isNotEmpty() || answer.text("custom").isNotBlank())
    }
    fun answer(item: RemoteInteraction, values: JSONObject): Pair<String, JSONObject> {
        require(item.kind != "approval" && item.questions.isNotEmpty() && item.questions.map { it.id }.distinct().size == item.questions.size)
        val answers = JSONArray()
        for (q in item.questions) {
            val value = values.optJSONObject(q.id) ?: JSONObject()
            require(validAnswer(q, value))
            answers.put(JSONObject().put("id", q.id).put("selected", value.optJSONArray("selected") ?: JSONArray()).put("custom", value.text("custom")))
        }
        return (if (item.kind == "continued") "answer" else "decision") to JSONObject()
            .put(if (item.kind == "continued") "callId" else "eventId", item.correlation)
            .put("answer", JSONObject().put("answers", answers))
    }
    fun prepare(state: RemoteState, kind: String, payload: JSONObject, interaction: String? = null): JSONObject {
        require(state.online && ServiceIdentity.validId(state.epoch))
        require(payload.toString().toByteArray().size <= 65536)
        return JSONObject().put("id", UUID.randomUUID().toString()).put("epoch", state.epoch).put("kind", kind)
            .put("payload", JSONObject(payload.toString())).put("createdAt", System.currentTimeMillis())
            .put("interaction", interaction ?: "").put("status", "unknown").put("pending", true)
    }
    fun body(record: JSONObject): JSONObject = JSONObject().put("id", record.getString("id")).put("epoch", record.getString("epoch"))
        .put("kind", record.getString("kind")).put("payload", record.getJSONObject("payload"))
    fun reconcile(record: JSONObject, receipt: JSONObject): JSONObject {
        require(record.getString("id") == receipt.getString("id") && receipt.text("status") in statuses)
        return JSONObject(record.toString()).put("status", receipt.getString("status"))
            .put("pending", if (receipt.text("status") == "uncertain") record.optBoolean("pending") else false)
    }
}
