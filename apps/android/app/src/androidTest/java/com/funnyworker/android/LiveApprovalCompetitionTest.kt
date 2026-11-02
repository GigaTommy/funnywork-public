package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Real Mac pending approvals; a host coordinates the independent iOS simulator. */
class LiveApprovalCompetitionTest {
    @Test fun exactApprovalAcrossEpochOrCompetingIOSDecision(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveApprovalCompetition") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = JSONObject(context.filesDir.resolve("android-competition-config.json").readText())
        val report = JSONObject().put("origin", cfg.text("origin")).put("case", cfg.text("case"))
        val output = context.filesDir.resolve("android-competition-report.json")
        fun checkpoint(stage: String) { report.put("stage", stage); output.writeText(report.toString(2)) }
        val engine = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (cfg.text("case") != "epoch" && request.method == "POST" && request.url.encodedPath.endsWith("/commands")) {
                val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                if (body.text("kind") == "decision") {
                    context.filesDir.resolve("android-competition-request.json").writeText(body.toString(2))
                    val end = System.nanoTime() + 20_000_000_000L
                    while (!context.filesDir.resolve("android-competition-release").exists() && System.nanoTime() < end) Thread.sleep(25)
                    check(context.filesDir.resolve("android-competition-release").exists())
                }
            }
            val response = chain.proceed(request)
            if (request.method == "POST" && request.url.encodedPath.endsWith("/commands")) {
                context.filesDir.resolve("android-competition-transport.json").writeText(JSONObject()
                    .put("status", response.code).put("code", if (response.isSuccessful) "" else runCatching { JSONObject(response.peekBody(4096).string()).text("code") }.getOrDefault("")).toString())
            }
            response
        }.build()
        val api = AccountApi(engine, automaticRouting = false, fixedOrigin = cfg.text("origin"))
        val owner = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
        })[AppViewModel::class.java] }
        suspend fun until(predicate: () -> Boolean) { withTimeout(150000) { while (!predicate()) { delay(150); if (!predicate() && !model.state.value.loading && !model.state.value.commandBusy) withContext(Dispatchers.Main) { model.refresh() } } } }
        try {
            until { model.state.value.ready }
            val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
            withContext(Dispatchers.Main) { model.login(cfg.text("origin"), account.text("username"), account.text("password")) }
            until { model.state.value.credential != null && !model.state.value.loading }
            withContext(Dispatchers.Main) { model.source(true); model.select(Row(cfg.text("sessionId"), "Approval competition acceptance", JSONObject().put("id", cfg.text("sessionId")))) }
            until { model.state.value.remoteState?.interactions?.any { it.correlation == cfg.text("eventId") } == true && !model.state.value.commandBusy && !model.state.value.loading }
            val item = model.state.value.remoteState!!.interactions.single { it.correlation == cfg.text("eventId") }
            val before = model.state.value.commandRecords.map { it.text("id") }.toSet()
            withContext(Dispatchers.Main) { model.draft("KEEP_${cfg.text("marker")}") }
            report.put("eventId", item.correlation).put("oldEpoch", item.epoch); checkpoint("ready")
            withTimeout(240000) { while (!context.filesDir.resolve("android-competition-go").exists()) delay(100) }
            withContext(Dispatchers.Main) { model.remoteDecide(item, cfg.getBoolean("androidAllow")) }
            if (cfg.text("case") == "epoch") {
                until { !model.state.value.commandBusy && model.state.value.remoteState?.epoch != item.epoch }
                assertEquals(before, model.state.value.commandRecords.map { it.text("id") }.toSet())
                val stale = JSONObject().put("id", UUID.randomUUID().toString()).put("epoch", item.epoch).put("kind", "decision")
                    .put("payload", RemoteCommands.decision(item, true).second)
                val c = model.state.value.credential!!
                try { api.call(c, "sync/sessions/${cfg.text("sessionId")}/commands", stale); fail("Stale epoch accepted") }
                catch (failure: ApiFailure) { assertEquals(409, failure.status); assertEquals("SYNC_DEVICE_OFFLINE", failure.code) }
                try { api.call(c, "sync/sessions/${cfg.text("sessionId")}/commands/${stale.text("id")}"); fail("Stale command stored") }
                catch (failure: ApiFailure) { assertEquals(404, failure.status) }
                report.put("newEpoch", model.state.value.remoteState!!.epoch).put("staleUIBlockedBeforeSubmission", true).put("staleWireRejected", true).put("commandId", stale.text("id"))
            } else {
                until { model.state.value.commandRecords.any { it.text("id") !in before && it.text("status") in RemoteCommands.terminal } && !model.state.value.commandBusy }
                val record = model.state.value.commandRecords.single { it.text("id") !in before }
                assertFalse(record.optBoolean("pending"))
                report.put("command", record).put("noPendingBlock", true)
            }
            assertEquals("KEEP_${cfg.text("marker")}", model.state.value.draft)
            report.put("draftPreserved", true); checkpoint("passed")
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { model.logout() }
                withTimeout(60000) { while (model.state.value.credential != null) delay(100) }
                withContext(Dispatchers.Main) { owner.clear() }
            }
            context.filesDir.resolve("android-uat-account.json").delete()
            report.put("sessionLoggedOut", true); output.writeText(report.toString(2))
        }
    }
}
