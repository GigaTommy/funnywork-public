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

/** Read-only acceptance after restarting the real Mac; never replays old creates. */
class LiveMacRecoveryTest {
    @Test fun originalWorkspaceAndExactRequestsSurviveMacRestart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMacRecovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = context.filesDir.resolve("android-uat-account.json")
        val baseline = JSONObject(context.filesDir.resolve("android-mac-recovery-baseline.json").readText())
        val output = context.filesDir.resolve("android-mac-recovery-report.json")
        val report = JSONObject().put("sourceKind", "actual restarted Mac Harness")
            .put("inferenceRequested", false).put("createReplayed", false).put("entries", JSONArray())
        fun checkpoint() = output.writeText(report.toString(2))
        try {
            val account = JSONObject(secret.readText())
            for (origin in ServiceIdentity.official) {
                val row = JSONObject().put("origin", origin); report.getJSONArray("entries").put(row); checkpoint()
                val api = AccountApi(automaticRouting = false, fixedOrigin = origin)
                val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
                })[AppViewModel::class.java] }
                try {
                    await { model.state.value.ready }
                    withContext(Dispatchers.Main) { model.login(origin, account.getString("username"), account.getString("password")) }
                    await { model.state.value.credential != null && !model.state.value.loading }
                    val credential = model.state.value.credential!!
                    val deviceId = baseline.getString("deviceId")
                    val device = JSONArray(api.call(credential, "sync/devices")).objects().single { it.text("id") == deviceId }
                    assertTrue(device.getBoolean("online")); assertFalse(device.getBoolean("revoked"))
                    assertEquals("0.2.1-rc.2", device.getJSONObject("choices").getJSONObject("clientBuild").text("version"))
                    assertTrue(device.getJSONArray("workspaces").objects().any { it.text("id") == baseline.getString("workspaceId") })
                    val sessions = JSONArray(api.call(credential, "sync/sessions?limit=100")).objects()
                    val originals = JSONArray()
                    for (expected in baseline.getJSONArray("originals").objects()) {
                        val session = sessions.single { it.text("native_id") == expected.text("nativeId") && it.text("device_id") == deviceId }
                        val id = session.getString("id")
                        var offset = 0L; var count = 0
                        do {
                            val page = JSONObject(api.call(credential, "sync/sessions/$id/events?offset=$offset&limit=100"))
                            val events = page.getJSONArray("events").objects(); count += events.size
                            val next = page.getLong("nextSeq")
                            if (next >= page.getLong("durableNextSeq")) break
                            check(next > offset); offset = next
                        } while (true)
                        assertEquals(expected.getInt("events"), count)
                        withContext(Dispatchers.Main) { model.source(true); model.select(Row(id, "Original Mac history", session)) }
                        await { model.state.value.task == id && model.state.value.remoteReady && !model.state.value.loading }
                        originals.put(JSONObject().put("nativeId", expected.text("nativeId")).put("sessionId", id)
                            .put("events", count).put("viewModelOpened", true))
                    }
                    row.put("originals", originals); checkpoint()
                    for (created in baseline.getJSONArray("created").objects()) {
                        val receipt = JSONObject(api.call(credential, "sync/devices/$deviceId/commands/${created.text("creationId")}"))
                        assertEquals("accepted", receipt.text("status"))
                        assertEquals(created.text("sessionId"), receipt.getJSONObject("result").text("sessionId"))
                        assertEquals(1, sessions.count { it.text("native_id") == "mobile-" + created.text("creationId") && it.text("id") == created.text("sessionId") })
                        val page = JSONObject(api.call(credential, "sync/sessions/${created.text("sessionId")}/events?offset=0&limit=100"))
                        assertTrue(page.getJSONArray("events").objects().none { it.text("type") in setOf("user/message", "turn/start") })
                    }
                    for (old in baseline.getJSONArray("oldCommands").objects()) {
                        val receipt = JSONObject(api.call(credential, "sync/devices/$deviceId/commands/${old.text("id")}"))
                        assertEquals(old.text("status"), receipt.text("status"))
                        assertEquals(old.getJSONObject("result").toString(), receipt.getJSONObject("result").toString())
                    }
                    row.put("createdReceiptsAndHistoryRecovered", true).put("oldRequestsUnchanged", true); checkpoint()
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.Main) { model.logout() }; await { model.state.value.credential == null }
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
