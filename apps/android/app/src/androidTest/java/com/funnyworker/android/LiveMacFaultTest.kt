package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Host orchestrator cuts actual connectivity and force-stops this process.
 * The interceptor holds a REAL successful response at the crash boundary; it
 * does not register sources, synthesize receipts or write native histories. */
class LiveMacFaultTest {
    @Test fun recoverExactWithdrawalAfterNetworkLossAndProcessDeath(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMacFault") == "true")
        val phase = requireNotNull(args.getString("phase"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = JSONObject(context.filesDir.resolve("android-fault-config.json").readText())
        val output = context.filesDir.resolve("android-fault-report.json")
        val report = if (phase == "prepare") JSONObject().put("origin", config.text("origin"))
            .put("task", config.text("task")).put("sourceKind", "actual Mac Harness") else JSONObject(output.readText())
        fun checkpoint(stage: String) { report.put("stage", stage); output.writeText(report.toString(2)) }
        val hold = AtomicBoolean(false)
        val engine = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (hold.get() && request.method == "POST" && request.url.encodedPath.endsWith("/commands")) {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                if (body.optJSONObject("payload")?.text("action") == "remove") {
                    check(response.isSuccessful)
                    context.filesDir.resolve("android-fault-held.json").writeText(JSONObject()
                        .put("commandId", body.text("id")).put("actualHttpStatus", response.code).toString())
                    // Owned by the test process. Only force-stop or a diagnostic
                    // release file ends this bounded crash-boundary wait.
                    val deadline = System.nanoTime() + 180_000_000_000L
                    while (!context.filesDir.resolve("android-fault-release").exists() && System.nanoTime() < deadline) Thread.sleep(50)
                    if (!context.filesDir.resolve("android-fault-release").exists()) throw IOException("Crash orchestrator deadline")
                }
            }
            response
        }.build()
        val api = AccountApi(engine, automaticRouting = false, fixedOrigin = config.text("origin"))
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
        })[AppViewModel::class.java] }
        suspend fun refreshUntil(predicate: () -> Boolean) {
            withTimeout(180000) {
                while (!predicate() || model.state.value.commandBusy || model.state.value.loading) {
                    if (!model.state.value.commandBusy && !model.state.value.loading) withContext(Dispatchers.Main) { model.checkRemote(); model.refresh() }
                    delay(1500)
                }
            }
        }
        suspend fun accepted(kind: String, action: () -> Unit) {
            val ids = model.state.value.commandRecords.map { it.text("id") }.toSet()
            withContext(Dispatchers.Main) { action() }
            refreshUntil { model.state.value.commandRecords.any { it.text("id") !in ids && it.text("kind") == kind && it.text("status") == "accepted" } && !model.state.value.commandBusy }
        }
        await { model.state.value.ready }
        val marker = config.text("marker")
        when (phase) {
            "prepare" -> {
                val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
                withContext(Dispatchers.Main) { model.login(config.text("origin"), account.text("username"), account.text("password")) }
                await { model.state.value.credential != null && !model.state.value.loading }
                withContext(Dispatchers.Main) { model.source(true); model.select(Row(config.text("task"), "Recovery acceptance", JSONObject().put("id", config.text("task")))) }
                refreshUntil { model.state.value.remoteReady && !model.state.value.loading }
                if (args.getString("resumePreparedQueue") != "true") {
                withContext(Dispatchers.Main) { model.draft("恢复验收：立即使用 ask_user_question 问一个问题 id=recoverygate，问题=是否继续，选项 Continue 和 Cancel。一直等待回答。不要调用其他工具，不读写文件。") }
                accepted("prompt") { model.remotePrompt() }
                refreshUntil { model.state.value.remoteState?.interactions?.any { it.questions.isNotEmpty() } == true }
                withContext(Dispatchers.Main) { model.draft("QUEUE_$marker") }
                accepted("prompt") { model.remotePrompt() }
                refreshUntil { model.state.value.remoteState?.queue?.any { it.text == "QUEUE_$marker" } == true }
                val original = model.state.value.remoteState!!.queue.single { it.text == "QUEUE_$marker" }
                withContext(Dispatchers.Main) { model.saveQueueEdit(original.id, "EDITED_$marker") }
                accepted("queueEdit") { model.updateQueue(original, "edit") }
                refreshUntil { model.state.value.remoteState!!.queue.any { it.id == original.id && it.text == "EDITED_$marker" } }
                }
                val edited = model.state.value.remoteState!!.queue.single { it.text == "EDITED_$marker" }
                report.put("queueId", edited.id).put("epoch", model.state.value.remoteState!!.epoch)
                hold.set(true)
                withContext(Dispatchers.Main) { model.draft("BEFORE_$marker"); model.updateQueue(edited, "remove") }
                await { context.filesDir.resolve("android-fault-held.json").exists() }
                val held = JSONObject(context.filesDir.resolve("android-fault-held.json").readText())
                report.put("commandId", held.text("commandId")).put("realResponseHeld", true)
                withContext(Dispatchers.Main) { model.draft("NEWER_$marker") }
                assertTrue(model.state.value.commandRecords.any { it.text("id") == held.text("commandId") && it.text("status") == "unknown" })
                checkpoint("concurrent-draft-durable-before-kill")
                awaitCancellation() // host confirms actual Mac accepted, disconnects, then kills
            }
            "offline" -> {
                assertNotNull(model.state.value.credential)
                assertEquals(config.text("task"), model.state.value.task)
                assertEquals("NEWER_$marker", model.state.value.draft)
                assertTrue(model.state.value.commandRecords.any { it.text("id") == report.text("commandId") && it.text("status") == "unknown" })
                var failedOffline = false
                try { api.call(model.state.value.credential!!, "session") } catch (failure: IOException) {
                    check(failure !is ApiFailure); failedOffline = true
                }
                assertTrue("Actual disconnected request must fail", failedOffline)
                withContext(Dispatchers.Main) { model.draft("OFFLINE_NEWER_$marker") }
                report.put("actualOfflineRequestFailed", true).put("offlineCredentialAndNavigationRestored", true)
                checkpoint("offline-draft-durable-before-second-kill")
                withContext(Dispatchers.Main) { store.clear() }
            }
            "recover" -> {
                assertNotNull(model.state.value.credential)
                assertEquals(config.text("task"), model.state.value.task)
                val expected = "OFFLINE_NEWER_$marker\n\nEDITED_$marker"
                refreshUntil { model.state.value.commandRecords.any { it.text("id") == report.text("commandId") && it.text("status") == "accepted" } && !model.state.value.commandBusy }
                assertEquals(expected, model.state.value.draft)
                assertTrue(model.state.value.remoteState!!.queue.none { it.id == report.text("queueId") })
                repeat(2) {
                    withContext(Dispatchers.Main) { model.checkRemote() }
                    await { !model.state.value.commandBusy }
                    assertEquals(expected, model.state.value.draft)
                }
                report.put("sameCommandRecovered", true).put("newerDraftPreserved", true).put("withdrawalAppendedExactlyOnce", true)
                accepted("cancel") { model.remoteStop() }
                refreshUntil { model.state.value.remoteState?.running == false }
                report.put("onlyUatTurnStopped", true); checkpoint("passed")
                withContext(Dispatchers.Main) { model.logout() }
                await { model.state.value.credential == null }
                context.filesDir.resolve("android-uat-account.json").delete()
                report.put("sessionLogoutPassed", true); checkpoint("passed")
                withContext(Dispatchers.Main) { store.clear() }
            }
            else -> error("Unknown phase")
        }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(60000) { while (!condition()) delay(100) } }
}
