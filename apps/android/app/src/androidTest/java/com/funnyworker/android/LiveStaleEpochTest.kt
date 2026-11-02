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
import java.util.concurrent.atomic.AtomicInteger

/** A captured, already settled real approval after a real Mac restart; no new model call. */
class LiveStaleEpochTest {
    @Test fun settledOldEpochIsRejectedWithoutChangingDraft(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveStaleEpoch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = JSONObject(context.filesDir.resolve("android-stale-epoch-config.json").readText())
        val posts = AtomicInteger()
        val api = AccountApi(OkHttpClient.Builder().addNetworkInterceptor { chain ->
            if (chain.request().method == "POST" && chain.request().url.encodedPath.endsWith("/commands")) posts.incrementAndGet()
            chain.proceed(chain.request())
        }.build(), automaticRouting = false, fixedOrigin = cfg.text("origin"))
        val owner = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
        })[AppViewModel::class.java] }
        suspend fun until(predicate: () -> Boolean) { withTimeout(120000) { while (!predicate()) delay(100) } }
        val report = JSONObject().put("origin", cfg.text("origin")).put("pendingApprovalStraddledRestart", false)
        try {
            until { model.state.value.ready }
            val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
            withContext(Dispatchers.Main) { model.login(cfg.text("origin"), account.text("username"), account.text("password")) }
            until { model.state.value.credential != null && !model.state.value.loading }
            withContext(Dispatchers.Main) { model.source(true); model.select(Row(cfg.text("sessionId"), "Old epoch acceptance", JSONObject().put("id", cfg.text("sessionId")))) }
            until { model.state.value.remoteState != null && !model.state.value.loading && !model.state.value.commandBusy }
            val epoch = cfg.text("oldEpoch"); val event = cfg.text("eventId")
            assertNotEquals(epoch, model.state.value.remoteState!!.epoch)
            val before = model.state.value.commandRecords.map { it.text("id") }.toSet()
            val item = RemoteInteraction("$epoch/event/$event", event, epoch, "approval", "bash", "", "", emptyList())
            withContext(Dispatchers.Main) { model.draft("KEEP_STALE_EPOCH"); model.remoteDecide(item, true) }
            until { !model.state.value.commandBusy && model.state.value.error != null }
            assertEquals(0, posts.get())
            assertEquals(before, model.state.value.commandRecords.map { it.text("id") }.toSet())
            val request = JSONObject().put("id", UUID.randomUUID().toString()).put("epoch", epoch).put("kind", "decision").put("payload", RemoteCommands.decision(item, true).second)
            val credential = model.state.value.credential!!
            try { api.call(credential, "sync/sessions/${cfg.text("sessionId")}/commands", request); fail("Old epoch admitted") }
            catch (failure: ApiFailure) { assertEquals(409, failure.status); assertEquals("SYNC_DEVICE_OFFLINE", failure.code) }
            try { api.call(credential, "sync/sessions/${cfg.text("sessionId")}/commands/${request.text("id")}"); fail("Rejected admission stored") }
            catch (failure: ApiFailure) { assertEquals(404, failure.status) }
            assertEquals("KEEP_STALE_EPOCH", model.state.value.draft)
            report.put("passed", true).put("staleCallbackBlockedBeforePOST", true).put("noNewLocalCommand", true)
                .put("wireStatus", 409).put("wireCode", "SYNC_DEVICE_OFFLINE").put("notStored", true).put("draftPreserved", true)
                .put("oldEpoch", epoch).put("newEpoch", model.state.value.remoteState!!.epoch).put("request", request)
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { model.logout() }
                until { model.state.value.credential == null }
                withContext(Dispatchers.Main) { owner.clear() }
            }
            context.filesDir.resolve("android-uat-account.json").delete()
            report.put("sessionLoggedOut", true)
            context.filesDir.resolve("android-stale-epoch-report.json").writeText(report.toString(2))
        }
    }
}
