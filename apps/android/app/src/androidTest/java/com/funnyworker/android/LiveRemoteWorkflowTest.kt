package com.funnyworker.android

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real account/backend/Android ViewModel, with a dedicated controlled source protocol fixture.
 * Does not pretend that a fixture acknowledgment is execution by a Mac/Windows Harness. */
class LiveRemoteWorkflowTest {
    @Test fun bothEntriesControlUploadDownloadAndStageReview(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRemote") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = context.filesDir.resolve("android-uat-account.json")
        val reportFile = context.filesDir.resolve("android-remote-report.json")
        val report = JSONObject().put("device", true).put("sourceKind", "controlled protocol fixture; no Harness execution").put("inferenceRequested", false)
        val rows = JSONArray(); report.put("entries", rows)
        try {
            val account = JSONObject(secret.readText())
            for (origin in ServiceIdentity.official) {
                val row = JSONObject().put("origin", origin); rows.put(row)
                val api = AccountApi(automaticRouting = false, fixedOrigin = origin)
                val login = JSONObject(api.json(origin, "", "login", JSONObject().put("username", account.getString("username"))
                    .put("password", account.getString("password")).put("client", "desktop").put("device", "Android UAT source fixture")))
                val token = login.getString("token"); val session = JSONObject(api.json(origin, token, "session"))
                val source = Credential(origin, token, session.getString("tenant"), session.getString("principalId"), "UAT")
                val device = "android-uat-" + UUID.randomUUID(); val native = "native-" + UUID.randomUUID(); val epoch = UUID.randomUUID().toString()
                val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
                })[AppViewModel::class.java] }
                var registered = false
                var heartbeat: Job? = null
                try {
                    row.put("phase", "register source fixture"); reportFile.writeText(report.toString(2))
                    api.call(source, "sync/devices", JSONObject().put("id", device).put("name", "Android protocol UAT fixture").put("platform", if (origin == ServiceIdentity.MAINLAND) "darwin" else "win32"))
                    registered = true
                    val registration = JSONObject(api.call(source, "sync/sessions", JSONObject().put("deviceId", device).put("inheritedEventCount", 0)
                        .put("header", JSONObject().put("id", native).put("version", 4).put("createdAt", System.currentTimeMillis()).put("isSeeded", false))))
                    val task = registration.getString("id"); row.put("sessionId", task).put("deviceId", device)
                    fun question(id: String) = JSONObject().put("id", id).put("question", "Choose UAT answer").put("options", JSONArray().put(JSONObject().put("label", "A")).put(JSONObject().put("label", "B"))).put("multiSelect", true)
                    val state = JSONObject().put("seq", 4).put("controllable", true).put("running", true).put("turnStart", 0)
                        .put("pending", JSONArray().put(JSONObject().put("eventId", "approval-one").put("event", "approval/request").put("request", JSONObject().put("toolName", "UAT no-op").put("reason", "Protocol test only")))
                            .put(JSONObject().put("eventId", "question-one").put("event", "user-questions/request").put("request", JSONObject().put("questions", JSONArray().put(question("q"))))))
                        .put("projections", JSONObject().put("userQuestions", JSONObject().put("active", JSONArray().put(JSONObject().put("state", "continued").put("callId", "continued-one").put("questions", JSONArray().put(question("c")))))))
                    fun publishBody() = JSONObject().put("epoch", epoch).put("sessions", JSONObject().put(native, state)).put("attachmentUploads", true)
                    api.call(source, "sync/devices/$device/heartbeat", publishBody())
                    heartbeat = launch { while (isActive) { delay(5000); api.call(source, "sync/devices/$device/heartbeat", publishBody()) } }
                    val patch = "```diff\n--- a/first.txt\n+++ b/first.txt\n-old\n+new\ndiff --git a/second.txt b/second.txt\n--- a/second.txt\n+++ b/second.txt\n+second\n```"
                    fun event(seq: Long, type: String, data: JSONObject) = JSONObject().put("seq", seq).put("type", type).put("time", System.currentTimeMillis()).put("data", data)
                    api.call(source, "sync/sessions/$task/events", JSONObject().put("deviceId", device).put("events", JSONArray()
                        .put(event(0, "turn/start", JSONObject()))
                        .put(event(1, "user/message", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "UAT fixture")))))
                        .put(event(2, "assistant/message", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", patch)))))
                        .put(event(3, "turn/end", JSONObject()))))
                    await { model.state.value.ready }
                    withContext(Dispatchers.Main) { model.login(origin, account.getString("username"), account.getString("password")) }
                    await { model.state.value.credential != null && !model.state.value.loading }
                    withContext(Dispatchers.Main) { model.source(true); model.select(Row(task, "UAT fixture", JSONObject().put("id", task))) }
                    await { model.state.value.remoteReady && model.state.value.desktopEvents.size == 4 }
                    suspend fun accept(kind: String, action: () -> Unit): JSONObject {
                        row.put("phase", "command: $kind"); reportFile.writeText(report.toString(2))
                        val previous = model.state.value.commandRecords.map { it.text("id") }.toSet()
                        withContext(Dispatchers.Main) { action() }
                        await { model.state.value.commandRecords.any { it.text("id") !in previous && it.text("status") == "queued" } }
                        val record = model.state.value.commandRecords.first { it.text("id") !in previous }
                        val claimed = JSONArray(api.call(source, "sync/devices/$device/state", publishBody())).objects().single { it.text("id") == record.text("id") }
                        assertEquals(kind, claimed.text("kind"))
                        api.call(source, "sync/devices/$device/commands/${record.text("id")}", JSONObject().put("epoch", epoch).put("status", "accepted").put("result", JSONObject()))
                        withContext(Dispatchers.Main) { model.checkRemote() }
                        await { model.state.value.commandRecords.any { it.text("id") == record.text("id") && it.text("status") == "accepted" } && !model.state.value.commandBusy }
                        return claimed.getJSONObject("payload")
                    }
                    withContext(Dispatchers.Main) { model.draft("Android queue UAT") }
                    assertEquals("queue", accept("prompt") { model.remotePrompt() }.text("mode"))
                    assertEquals("", model.state.value.draft)
                    withContext(Dispatchers.Main) { model.draft("Android steer UAT") }
                    assertEquals(0L, accept("prompt") { model.remotePrompt(true) }.getLong("expectedTurn"))
                    assertEquals(0L, accept("cancel") { model.remoteStop() }.getLong("expectedTurn"))
                    val approval = model.state.value.remoteState!!.interactions.first { it.kind == "approval" }
                    assertEquals("allowed-once", accept("decision") { model.remoteDecide(approval, true) }.text("outcome"))
                    for (kind in listOf("questions", "continued")) {
                        val item = model.state.value.remoteState!!.interactions.first { it.kind == kind }
                        withContext(Dispatchers.Main) { model.answerDraft(item, item.questions.single(), listOf("A", "B"), "UAT custom") }
                        val answer = accept(if (kind == "continued") "answer" else "decision") { model.remoteAnswer(item) }
                        assertEquals(2, answer.getJSONObject("answer").getJSONArray("answers").getJSONObject(0).getJSONArray("selected").length())
                    }
                    row.put("controlPassed", true)
                    row.put("phase", "file and review"); reportFile.writeText(report.toString(2))
                    val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                    val png = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
                    val input = File(context.filesDir, "transfers/uat-input.png"); input.parentFile!!.mkdirs(); input.writeBytes(png)
                    try {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", input)
                        withContext(Dispatchers.Main) { model.importFile(uri) }
                        await { model.state.value.uploads.size == 1 }
                        val file = SyncedFile.from(model.state.value.uploads.single())
                        withContext(Dispatchers.Main) { model.upload(file) }
                        await { model.state.value.uploads.single().optBoolean("complete") }
                        withContext(Dispatchers.Main) { model.draft("") }
                        assertEquals(file.hash, accept("prompt") { model.remotePrompt() }.getJSONArray("attachments").getJSONObject(0).text("hash"))
                        assertTrue(model.state.value.uploads.isEmpty())
                        val content = JSONObject().put("type", "file").put("attachment", JSONObject().put("attachmentId", "sha256:${file.hash}").put("name", file.name).put("bytes", file.bytes))
                        val result = JSONObject().put("schema", 1).put("id", "uat-result").put("version", 1).put("turn", 0)
                            .put("original", content).put("variants", JSONArray()).put("warnings", JSONArray()).put("previewState", "ready")
                        api.call(source, "sync/sessions/$task/events", JSONObject().put("deviceId", device).put("events", JSONArray()
                            .put(event(4, "deliverables/presented", JSONObject().put("files", JSONArray().put(JSONObject().put("path", "uat-input.png").put("result", result)))))))
                        val resultPath = "sync/sessions/$task/results/uat-result/versions/1/files/original"
                        assertArrayEquals(png, api.fileRange(model.state.value.credential!!, resultPath, file, 0))
                        val cache = ClientFiles(context)
                        cache.partial(source.key, file).writeBytes(png.copyOfRange(0, 10)); check(cache.local(source.key, file).delete())
                        withContext(Dispatchers.Main) { model.download(file, resultPath) }
                        await { cache.local(source.key, file).exists() }
                        assertArrayEquals(png, cache.verified(source.key, file)!!.readBytes())
                        // A process can stop after the last byte, before verification/rename.
                        cache.partial(source.key, file).writeBytes(png); check(cache.local(source.key, file).delete())
                        withContext(Dispatchers.Main) { model.download(file, resultPath) }
                        await { cache.local(source.key, file).exists() }
                        assertArrayEquals(png, cache.verified(source.key, file)!!.readBytes())
                        row.put("fullPartialRecoveryPassed", true)
                        withContext(Dispatchers.Main) { model.loadResults(); model.freezeReview() }
                        await { model.state.value.resultFiles.isNotEmpty() && model.state.value.review != null }
                        withContext(Dispatchers.Main) { model.reviewComment("UAT overall note"); model.reviewComment("UAT file note", "#2 · first.txt"); assertTrue(model.stageReview()) }
                        assertTrue(model.state.value.draft.contains("#2")); assertTrue(model.state.value.draft.contains("first.txt"))
                        withContext(Dispatchers.Main) { assertFalse(model.stageReview()) }
                        row.put("uploadAndRangeResumePassed", true).put("fixedReviewStagedOnce", true)
                    } finally { input.delete() }
                    reportFile.writeText(report.toString(2))
                } finally {
                    withContext(NonCancellable) {
                        heartbeat?.cancelAndJoin()
                        var failures = 0
                        if (registered) runCatching { api.call(source, "sync/devices/$device/revoke", JSONObject().put("revision", 0)) }.onFailure { failures++ }
                        runCatching { withContext(Dispatchers.Main) { model.logout() }; await { model.state.value.credential == null } }.onFailure { failures++ }
                        withContext(Dispatchers.Main) { store.clear() }
                        runCatching { api.call(source, "logout", JSONObject()) }.onFailure { failures++ }
                        row.put("cleanupPassed", failures == 0)
                        check(failures == 0) { "Fixture cleanup needs reconciliation" }
                    }
                }
            }
            report.put("passed", true)
        } finally { secret.delete(); reportFile.writeText(report.toString(2)) }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(60000) { while (!condition()) delay(100) } }
}
