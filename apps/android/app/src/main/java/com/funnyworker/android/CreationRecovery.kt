package com.funnyworker.android

import org.json.JSONObject

/** Pure durable recovery: reads never fabricate acceptance or create a new command ID. */
object CreationRecovery {
    val terminal = setOf("accepted", "rejected", "expired", "uncertain")
    val statuses = terminal + setOf("queued", "claimed")
    fun valid(record: JSONObject) = runCatching {
        val request = record.getJSONObject("request")
        ServiceIdentity.validId(record.text("device")) && ServiceIdentity.validId(request.text("id")) &&
            ServiceIdentity.validId(request.text("epoch")) && request.text("kind") == "create" &&
            ServiceIdentity.validId(request.getJSONObject("payload").text("workspaceId"))
    }.getOrDefault(false)
    fun id(record: JSONObject) = record.getJSONObject("request").getString("id")
    fun status(record: JSONObject) = record.text("status").ifBlank { "unknown" }
    fun reconcile(record: JSONObject, receipt: JSONObject): JSONObject {
        require(receipt.text("id") == id(record) && receipt.text("status") in statuses)
        if (receipt.text("status") == "accepted") require(ServiceIdentity.validId(receipt.getJSONObject("result").getString("sessionId")))
        return JSONObject(record.toString()).put("status", receipt.text("status")).put("receipt", receipt).put("error", false)
    }
    fun canRetry(record: JSONObject, device: DeviceInfo?, now: Long): Boolean {
        if (!valid(record)) return false
        val created = record.sequence("createdAt") ?: return false
        val request = record.getJSONObject("request")
        return status(record) == "missing" && now - created in 0 until 25000 && device?.canCreate == true &&
            device.id == record.text("device") && device.epoch == request.text("epoch") &&
            device.workspaces.any { it.id == request.getJSONObject("payload").text("workspaceId") }
    }
    fun canClear(record: JSONObject, now: Long): Boolean {
        if (!valid(record)) return true
        val age = record.sequence("createdAt")?.let { now - it }
        return status(record) in terminal || age == null || age < 0 || age >= 30000
    }
    fun accepted(account: JSONObject, record: JSONObject): JSONObject {
        val receipt = record.getJSONObject("receipt"); require(status(record) == "accepted")
        val session = receipt.getJSONObject("result").getString("sessionId"); require(ServiceIdentity.validId(session))
        val operation = id(record); val next = JSONObject(account.toString())
        val applied = next.optJSONObject("applied") ?: JSONObject().also { next.put("applied", it) }
        val drafts = next.optJSONObject("drafts") ?: JSONObject().also { next.put("drafts", it) }
        if (!applied.optBoolean(operation)) {
            val key = "desktop:$session"; val text = record.text("text")
            drafts.put(key, listOf(drafts.text(key), text).filter { it.isNotBlank() }.joinToString("\n\n")); applied.put(operation, true)
        }
        next.remove("pendingCreate"); next.put("creationText", "").put("createdTask", session)
        return next
    }
}
