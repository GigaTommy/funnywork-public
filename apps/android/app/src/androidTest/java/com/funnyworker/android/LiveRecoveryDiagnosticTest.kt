package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in diagnostics over the retained real crash journal; never sends commands. */
class LiveRecoveryDiagnosticTest {
    @Test fun restoredJournalReadOnlyDiagnostic(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRecoveryDiagnostic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = context.filesDir.resolve("android-recovery-diagnostic.json")
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application) as T
        })[AppViewModel::class.java] }
        repeat(8) {
            delay(3000)
            withContext(Dispatchers.Main) {
                val s = model.state.value
                output.writeText(JSONObject().put("ready", s.ready).put("loading", s.loading)
                    .put("credentialPresent", s.credential != null).put("task", s.task).put("desktop", s.desktop)
                    .put("remoteReady", s.remoteReady).put("commandBusy", s.commandBusy)
                    .put("error", s.error?.let { context.getString(it) }).put("events", s.desktopEvents.size)
                    .put("records", JSONArray(s.commandRecords.map { r -> JSONObject().put("id", r.text("id")).put("kind", r.text("kind")).put("status", r.text("status")).put("interaction", r.text("interaction")) })).toString(2))
            }
        }
        withContext(Dispatchers.Main) { store.clear() }
    }
}
