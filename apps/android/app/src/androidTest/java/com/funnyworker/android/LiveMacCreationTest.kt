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

/** Real Mac creation in an explicitly selected canonical test workspace; no inference. */
class LiveMacCreationTest {
    @Test fun creationMovesDraftOnceWithoutSendingInBothEntries(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); assumeTrue(args.getString("liveMac") == "true")
        val device = requireNotNull(args.getString("realMacDevice")); val workspace = requireNotNull(args.getString("realWorkspace"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = context.filesDir.resolve("android-uat-account.json"); val output = context.filesDir.resolve("android-mac-creation-report.json")
        val report = JSONObject().put("sourceKind", "actual Mac Harness").put("inferenceRequested", false).put("entries", JSONArray())
        fun checkpoint() = output.writeText(report.toString(2))
        try {
            val account = JSONObject(secret.readText())
            for (origin in ServiceIdentity.official) {
                val row = JSONObject().put("origin", origin).put("deviceId", device).put("workspaceId", workspace); report.getJSONArray("entries").put(row); checkpoint()
                val store = ViewModelStore(); val api = AccountApi(automaticRouting = false, fixedOrigin = origin)
                val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
                })[AppViewModel::class.java] }
                try {
                    await { model.state.value.ready }
                    withContext(Dispatchers.Main) { model.login(origin, account.getString("username"), account.getString("password")) }
                    await { model.state.value.credential != null && !model.state.value.loading }
                    withContext(Dispatchers.Main) { model.source(true); model.loadDevices() }
                    await { model.state.value.devices.any { it.id == device && it.json.optBoolean("online") && it.json.getJSONArray("workspaces").objects().any { w -> w.text("id") == workspace } } }
                    val draft = "CREATED_BUT_NOT_SENT_" + java.util.UUID.randomUUID()
                    withContext(Dispatchers.Main) { model.creation(text = draft, device = device, workspace = workspace); model.create() }
                    await { model.state.value.pendingCreate != null }
                    row.put("creationId", model.state.value.pendingCreate!!.getJSONObject("request").text("id")); checkpoint()
                    withTimeout(90000) { while (model.state.value.pendingCreate != null) { withContext(Dispatchers.Main) { model.checkCreation() }; delay(1500) } }
                    val task = model.state.value.task!!; row.put("sessionId", task); checkpoint()
                    assertEquals(draft, model.state.value.draft)
                    withContext(Dispatchers.Main) { model.checkCreation() }; delay(1000)
                    assertEquals(draft, model.state.value.draft)
                    val c = model.state.value.credential!!
                    val page = JSONObject(api.call(c, "sync/sessions/$task/events?offset=0&limit=100"))
                    assertTrue(page.getJSONArray("events").objects().none { it.text("type") in setOf("user/message", "turn/start") })
                    row.put("creationAccepted", true).put("draftMovedExactlyOnce", true).put("notAutomaticallySent", true); checkpoint()
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
