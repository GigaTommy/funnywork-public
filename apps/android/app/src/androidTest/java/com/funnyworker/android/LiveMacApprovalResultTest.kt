package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Actual native approval and native publish_result. No fixture acknowledgments. */
class LiveMacApprovalResultTest {
    @Test fun approveDenyAndReadFixedNativeResultsInBothEntries(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); assumeTrue(args.getString("liveMacApprovalResult") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountFile = context.filesDir.resolve("android-uat-account.json")
        val account = JSONObject(accountFile.readText())
        val config = JSONObject(context.filesDir.resolve("android-native-result-config.json").readText())
        val output = context.filesDir.resolve("android-native-result-report.json")
        val report = if (args.getString("resume") == "true") JSONObject(output.readText()) else JSONObject().put("sourceKind", "actual Mac Harness").put("entries", JSONArray())
        fun checkpoint() = output.writeText(report.toString(2))
        try {
            for (entry in config.getJSONArray("entries").objects()) {
                val origin = entry.text("origin"); val task = entry.text("sessionId"); val marker = entry.text("marker")
                val existing = report.getJSONArray("entries").objects().firstOrNull { it.text("marker") == marker }
                val row = existing ?: JSONObject().put("origin", origin).put("sessionId", task).put("marker", marker).also { report.getJSONArray("entries").put(it) }
                val resumedPhase = row.text("phase")
                checkpoint()
                if (row.optBoolean("allFixedFilesHashVerified")) continue
                val engine = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    try {
                        val response = chain.proceed(request)
                        val trace = JSONObject().put("method", request.method).put("path", request.url.encodedPath).put("status", response.code)
                        if (!response.isSuccessful) trace.put("errorCode", runCatching { JSONObject(response.peekBody(4096).string()).text("code") }.getOrDefault(""))
                        if (request.method == "POST" && request.url.encodedPath.endsWith("/commands")) {
                            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                            trace.put("commandId", body.text("id")).put("epoch", body.text("epoch")).put("kind", body.text("kind"))
                                .put("eventId", body.optJSONObject("payload")?.text("eventId")).put("outcome", body.optJSONObject("payload")?.text("outcome"))
                        }
                        context.filesDir.resolve("android-native-transport.jsonl").appendText(trace.toString() + "\n")
                        response
                    } catch (failure: java.io.IOException) {
                        context.filesDir.resolve("android-native-transport.jsonl").appendText(JSONObject().put("method", request.method).put("path", request.url.encodedPath).put("failure", failure.javaClass.simpleName).toString() + "\n")
                        throw failure
                    }
                }.build()
                val api = AccountApi(engine, automaticRouting = false, fixedOrigin = origin); val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
                })[AppViewModel::class.java] }
                suspend fun until(predicate: () -> Boolean) {
                    withTimeout(300000) { while (!predicate()) {
                        withContext(Dispatchers.Main) { model.refresh() }; delay(1500)
                        withTimeout(60000) { while (model.state.value.loading || model.state.value.commandBusy) delay(100) }
                    } }
                }
                suspend fun accepted(kind: String, action: () -> Unit): JSONObject {
                    val old = model.state.value.commandRecords.map { it.text("id") }.toSet()
                    withContext(Dispatchers.Main) { action() }
                    until {
                        val state = model.state.value
                        check(state.error == null || state.commandBusy) { "Command $kind failed: error=${state.error}, records=" + state.commandRecords.map { it.text("kind") + ":" + it.text("status") } }
                        state.commandRecords.any { it.text("id") !in old && it.text("kind") == kind && it.text("status") == "accepted" }
                    }
                    return model.state.value.commandRecords.single { it.text("id") !in old && it.text("kind") == kind }
                }
                try {
                    withTimeout(60000) { while (!model.state.value.ready) delay(100) }
                    if (args.getString("resume") != "true" || model.state.value.credential == null) {
                        withContext(Dispatchers.Main) { model.login(origin, account.text("username"), account.text("password")) }
                    }
                    withTimeout(60000) { while (model.state.value.credential == null || model.state.value.loading) delay(100) }
                    withContext(Dispatchers.Main) { model.source(true); model.select(Row(task, "Native approval and result acceptance", JSONObject().put("id", task))) }
                    until { model.state.value.remoteReady }
                    for (allow in listOf(true, false)) {
                        val key = if (allow) "allow" else "deny"
                        if (row.optBoolean("${key}SettledEventRejected")) continue
                        val reusePending = resumedPhase == "await-$key-physical-check" && row.has("${key}EventId") && model.state.value.remoteState?.interactions?.any { it.correlation == row.text("${key}EventId") } == true
                        val path = entry.getJSONObject("paths").text(key)
                        val command = "printf '$marker' > '$path'"
                        if (!reusePending) {
                        context.filesDir.resolve("native-$key-checked-$marker").delete()
                        row.put("phase", "request-$key"); checkpoint()
                        withContext(Dispatchers.Main) { model.draft("真实审批验收。只使用bash执行精确命令：$command 。首次使用当前workspace-write模式；如果沙箱拒绝，只可对同一命令设置sandbox_permissions=danger-full-access和justification=安卓一次审批验收重试，从而请求真实用户审批。等待审批，拒绝后立即结束，不用任何其他方法或工具绕过，不重试其他命令。完成仅回复 ${key.uppercase()}_$marker。") }
                        accepted("prompt") { model.remotePrompt() }
                        }
                        until { model.state.value.remoteState?.interactions?.any { it.kind == "approval" && (!reusePending || it.correlation == row.text("${key}EventId")) } == true }
                        val item = model.state.value.remoteState!!.interactions.single { it.kind == "approval" }
                        row.put("${key}EventId", item.correlation).put("phase", "await-$key-physical-check"); checkpoint()
                        // Host independently checks the target does not exist before any decision.
                        withTimeout(120000) { while (!context.filesDir.resolve("native-$key-checked-$marker").exists()) delay(100) }
                        row.put("phase", "post-$key-decision"); checkpoint()
                        val decision = accepted("decision") { model.remoteDecide(item, allow) }
                        row.put("${key}DecisionId", decision.text("id")); checkpoint()
                        until { model.state.value.remoteState?.running == false }
                        assertTrue(model.state.value.desktopEvents.any { it.type == "approval/decided" })
                        row.put("${key}NativeApprovalSettled", true)
                        // Repeating the original command ID must not execute a second decision.
                        val c = model.state.value.credential!!
                        val replay = JSONObject(api.call(c, "sync/sessions/$task/commands", RemoteCommands.body(decision)))
                        assertEquals(decision.text("id"), replay.text("id")); assertEquals("accepted", replay.text("status"))
                        row.put("${key}SameIdReplayIdempotent", true)
                        // A NEW command for the settled event must not be accepted by the source.
                        val stale = JSONObject().put("id", UUID.randomUUID().toString()).put("epoch", item.epoch).put("kind", "decision")
                            .put("payload", JSONObject().put("eventId", item.correlation).put("outcome", "allowed-once"))
                        var rejectedAtAdmission = false
                        try { api.call(c, "sync/sessions/$task/commands", stale) }
                        catch (failure: ApiFailure) { assertEquals(409, failure.status); rejectedAtAdmission = true }
                        if (!rejectedAtAdmission) {
                            var receipt = JSONObject()
                            withTimeout(60000) { do {
                                delay(1000); receipt = JSONObject(api.call(c, "sync/sessions/$task/commands/${stale.text("id")}"))
                            } while (receipt.text("status") in setOf("queued", "claimed")) }
                            assertEquals("rejected", receipt.text("status"))
                        }
                        row.put("${key}SettledEventRejectedAtAdmission", rejectedAtAdmission)
                        row.put("${key}SettledEventRejected", true); checkpoint()
                    }
                    val resumePublished = resumedPhase == "native-generate-and-publish"
                    row.put("phase", "native-generate-and-publish"); checkpoint()
                    val dir = entry.text("resultDirectory")
                    if (!resumePublished) {
                    withContext(Dispatchers.Main) { model.draft("文件跨端验收：在当前工作区已有专用目录 $dir 中，用客户端内置Python和openpyxl生成 recovery.xlsx（表=验收，A1=项目、B1=金额，A2=$marker、B2=42，A3=合计、B3公式SUM(B2:B2)）。用内置Pillow生成 recovery.png（160×100，纯奶白底、中性灰矩形）。不要安装或下载，不读取其他用户文件。务必分别调用 publish_result 发布两个原文件，等待预览生成；最后仅回复 PUBLISHED_$marker。") }
                    accepted("prompt") { model.remotePrompt() }
                    }
                    until { model.state.value.remoteState?.running == false && model.state.value.desktopEvents.any { it.type == "deliverables/presented" } }
                    val c = model.state.value.credential!!
                    var results = JSONObject()
                    withTimeout(240000) { while (true) {
                        results = JSONObject(api.call(c, "sync/sessions/$task/results?after=-1"))
                        val records = results.getJSONArray("items").objects()
                        if (records.size >= 2 && records.all { result ->
                            val manifest = result.getJSONObject("manifest"); val transfers = result.getJSONObject("transfers")
                            val keys = listOf("original") + manifest.getJSONArray("variants").objects().map { it.text("key") }
                            manifest.text("previewState") == "ready" && keys.all { transfers.optJSONObject(it)?.optBoolean("complete") == true }
                        }) break
                        delay(2000)
                    } }
                    val fixed = JSONArray()
                    for (result in results.getJSONArray("items").objects()) {
                        val manifest = result.getJSONObject("manifest")
                        for ((key, content) in listOf("original" to manifest.getJSONObject("original")) + manifest.getJSONArray("variants").objects().map { it.text("key") to it.getJSONObject("content") }) {
                            val attachment = content.getJSONObject("attachment")
                            val file = SyncedFile.reference(attachment)
                            val path = "sync/sessions/$task/results/${manifest.text("id")}/versions/${manifest.getInt("version")}/files/$key"
                            val bytes = java.io.ByteArrayOutputStream().also { output ->
                                var offset = 0L
                                do { val chunk = api.fileRange(c, path, file, offset); output.write(chunk); offset += chunk.size } while (offset < file.bytes)
                            }.toByteArray()
                            assertEquals(file.bytes, bytes.size.toLong())
                            assertEquals(file.hash, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        }
                        fixed.put(manifest)
                    }
                    row.put("results", fixed).put("allFixedFilesHashVerified", true).put("phase", "passed"); checkpoint()
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.Main) { model.logout() }
                        withTimeout(60000) { while (model.state.value.credential != null) delay(100) }
                        withContext(Dispatchers.Main) { store.clear() }
                        row.put("sessionLogoutPassed", true); checkpoint()
                    }
                }
            }
            report.put("passed", true)
        } finally { accountFile.delete(); checkpoint() }
    }
}
