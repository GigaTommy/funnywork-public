package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in real Mac execution. No fixture registrations, claims, acknowledgments or synthetic events. */
class LiveMacHarnessTest {
    @Test fun bothEntriesExecuteQuestionQueueWithdrawalAndStop(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMac") == "true")
        val deviceId = requireNotNull(args.getString("realMacDevice"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = context.filesDir.resolve("android-uat-account.json")
        val output = context.filesDir.resolve("android-mac-report.json")
        val report = JSONObject().put("sourceKind", "actual Mac Harness").put("entries", JSONArray())
        fun checkpoint() = output.writeText(report.toString(2))
        try {
            val account = JSONObject(secret.readText())
            for (origin in ServiceIdentity.official.filter { args.getString("onlyOrigin").let { selected -> selected == null || selected == it } }) {
                val row = JSONObject().put("origin", origin); report.getJSONArray("entries").put(row); checkpoint()
                val api = AccountApi(automaticRouting = false, fixedOrigin = origin)
                val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
                })[AppViewModel::class.java] }
                var task: String? = null
                try {
                    await { model.state.value.ready }
                    withContext(Dispatchers.Main) { model.login(origin, account.getString("username"), account.getString("password")) }
                    await { model.state.value.credential != null && !model.state.value.loading }
                    val c = model.state.value.credential!!
                    val device = JSONArray(api.call(c, "sync/devices")).objects().single { it.text("id") == deviceId }
                    check(device.text("platform") == "darwin" && device.getBoolean("online") && !device.getBoolean("revoked"))
                    row.put("deviceId", deviceId).put("clientBuild", device.getJSONObject("choices").getJSONObject("clientBuild"))
                    val workspace = device.getJSONArray("workspaces").objects().first { args.getString("realWorkspace").let { id -> if (id == null) it.text("title") == "default-workspace" else it.text("id") == id } }
                    val resumed = args.getString("resumeTask") ?: if (origin == ServiceIdentity.MAINLAND) args.getString("resumeMainlandTask") else null
                    if (resumed != null) {
                        val entry = JSONArray(api.call(c, "sync/sessions?limit=100")).objects().single { it.text("id") == resumed }
                        check(entry.text("device_id") == deviceId && entry.text("native_id") == "mobile-" + args.getString("resumeCreationId"))
                        task = resumed; row.put("creationReceipt", "previous uncertain; no replay").put("creationId", args.getString("resumeCreationId"))
                    } else {
                        val request = JSONObject().put("id", UUID.randomUUID().toString()).put("epoch", device.getString("epoch")).put("kind", "create")
                            .put("payload", JSONObject().put("workspaceId", workspace.getString("id")).put("agentPreset", "standard"))
                        row.put("creationId", request.getString("id")).put("phase", "create"); checkpoint()
                        api.call(c, "sync/devices/$deviceId/commands", request)
                        var receipt = JSONObject()
                        withTimeout(90000) {
                            do { delay(1000); receipt = JSONObject(api.call(c, "sync/devices/$deviceId/commands/${request.getString("id")}")); row.put("creationReceipt", receipt.text("status")); checkpoint(); check(receipt.text("status") !in setOf("rejected", "expired", "uncertain")) } while (receipt.text("status") != "accepted")
                        }
                        task = receipt.getJSONObject("result").getString("sessionId")
                    }
                    row.put("sessionId", task); checkpoint()
                    withContext(Dispatchers.Main) { model.source(true); model.select(Row(task, "Mac Android UAT", JSONObject().put("id", task))) }
                    await { model.state.value.remoteReady && !model.state.value.loading }
                    suspend fun refreshUntil(predicate: () -> Boolean) {
                        withTimeout(180000) {
                            while (!predicate()) {
                                withContext(Dispatchers.Main) { model.refresh() }
                                delay(1500)
                                await { !model.state.value.loading && !model.state.value.commandBusy }
                            }
                        }
                    }
                    suspend fun accepted(kind: String, action: () -> Unit) {
                        val old = model.state.value.commandRecords.map { it.text("id") }.toSet()
                        withContext(Dispatchers.Main) { action() }
                        refreshUntil { model.state.value.commandRecords.any { it.text("id") !in old && it.text("kind") == kind && it.text("status") == "accepted" } }
                        val record = model.state.value.commandRecords.first { it.text("id") !in old }
                        row.put("lastCommandId", record.text("id")); checkpoint()
                    }
                    val marker = "ANDROID_MAC_" + UUID.randomUUID().toString().replace("-", "")
                    row.put("phase", "ask actual user question"); checkpoint()
                    withContext(Dispatchers.Main) { model.draft("Android真实联调。立即调用 ask_user_question 工具，只问一个问题：id=gate，question=联调是否继续，选项仅 Continue 和 Cancel。必须等待用户回答，收到 Continue 后只回复 $marker。不要读写文件，不要调用其他工具。") }
                    accepted("prompt") { model.remotePrompt() }
                    refreshUntil { model.state.value.remoteState?.interactions?.any { it.kind in setOf("questions", "continued") } == true }
                    val question = model.state.value.remoteState!!.interactions.first { it.questions.isNotEmpty() }
                    row.put("questionKind", question.kind).put("phase", "queue edit and withdraw"); checkpoint()
                    withContext(Dispatchers.Main) { model.draft("UNSENT_QUEUE_$marker") }
                    accepted("prompt") { model.remotePrompt() }
                    refreshUntil { model.state.value.remoteState!!.queue.any { it.text == "UNSENT_QUEUE_$marker" } }
                    val queued = model.state.value.remoteState!!.queue.first { it.text == "UNSENT_QUEUE_$marker" }
                    withContext(Dispatchers.Main) { model.saveQueueEdit(queued.id, "EDITED_QUEUE_$marker") }
                    accepted("queueEdit") { model.updateQueue(queued, "edit") }
                    refreshUntil { model.state.value.remoteState!!.queue.any { it.id == queued.id && it.text == "EDITED_QUEUE_$marker" } }
                    val edited = model.state.value.remoteState!!.queue.first { it.id == queued.id }
                    withContext(Dispatchers.Main) { model.draft("KEEP_NEW_DRAFT") }
                    accepted("queueEdit") { model.updateQueue(edited, "remove") }
                    refreshUntil { model.state.value.remoteState!!.queue.none { it.id == queued.id } }
                    assertEquals("KEEP_NEW_DRAFT\n\nEDITED_QUEUE_$marker", model.state.value.draft)
                    withContext(Dispatchers.Main) { model.checkRemote() }; delay(2000)
                    assertEquals(1, Regex("EDITED_QUEUE_$marker").findAll(model.state.value.draft).count())
                    row.put("queueEditWithdrawPreserveNewDraftOnce", true)
                    for (q in question.questions) withContext(Dispatchers.Main) { model.answerDraft(question, q, listOf(q.options.first { it.first == "Continue" }.first), "") }
                    row.put("phase", "answer actual question"); checkpoint()
                    accepted(if (question.kind == "continued") "answer" else "decision") { model.remoteAnswer(question) }
                    refreshUntil { model.state.value.desktopEvents.any { it.type == "assistant/message" && it.text.contains(marker) } && model.state.value.remoteState?.running == false }
                    row.put("actualReplySynced", true)
                    row.put("phase", "stop actual running turn"); checkpoint()
                    withContext(Dispatchers.Main) { model.draft("Android停止联调。调用 ask_user_question，只问一个问题 id=stopgate，问题=等待停止，选项 Wait 和 Cancel。一直等待，不读写文件，不调用其他工具。") }
                    accepted("prompt") { model.remotePrompt() }
                    refreshUntil { model.state.value.remoteState?.running == true && model.state.value.remoteState!!.interactions.isNotEmpty() }
                    val turn = model.state.value.remoteState!!.turn!!
                    accepted("cancel") { model.remoteStop() }
                    refreshUntil { model.state.value.remoteState?.running == false && model.state.value.desktopEvents.any { it.seq > turn && it.type == "turn/end" } }
                    row.put("actualStopSynced", true).put("phase", "passed"); checkpoint()
                } finally {
                    withContext(NonCancellable) {
                        // Only the newly created UAT conversation is eligible for a scoped stop on failure.
                        if (task != null && model.state.value.remoteState?.running == true) {
                            withContext(Dispatchers.Main) { model.remoteStop() }; delay(3000)
                        }
                        withContext(Dispatchers.Main) { model.logout() }
                        await { model.state.value.credential == null }
                        withContext(Dispatchers.Main) { store.clear() }
                        row.put("sessionLogoutPassed", true); checkpoint()
                    }
                }
            }
            report.put("passed", true)
        } finally { secret.delete(); checkpoint() }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(60000) { while (!condition()) delay(100) } }
}
