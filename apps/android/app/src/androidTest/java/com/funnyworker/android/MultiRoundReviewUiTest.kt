package com.funnyworker.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real encrypted persistence and controller recreation; isolated storage, no account/network writes. */
class MultiRoundReviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun separateRoundNotesSurviveControllerRestartAndStageOnlyTheChosenRound(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("reviewProcessPhase") ?: "single"
        require(phase in setOf("single", "prepare", "resume"))
        val directory = context.noBackupFilesDir.resolve("round-review-ui-fixture").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getNoBackupFilesDir() = directory
            override fun getFilesDir() = directory.resolve("files").apply { mkdirs() }
        }
        val c = Credential(ServiceIdentity.INTERNATIONAL, "unused-isolated-fixture", "round-review-ui", "isolated-fixture", "UI")
        val store = SecureStore(app)
        fun round(end: Long) = JSONObject().put("end", end).put("messages", JSONArray()).put("sequences", JSONArray().put(end - 1))
        if (phase != "resume") {
        var data = ReviewDrafts.select(JSONObject().put("desktop", true).put("task", "fixture-task").put("drafts", JSONObject().put("desktop:fixture-task", "existing typing")), "fixture-task", round(9))
        data.getJSONObject("reviews").getJSONObject("fixture-task").put("comment", "first opinion")
        data = ReviewDrafts.select(data, "fixture-task", round(19))
        data.getJSONObject("reviews").getJSONObject("fixture-task").put("comment", "second opinion")
        data = ReviewDrafts.select(data, "fixture-task", round(9))
        store.write(c.key, data); store.write("session", c.json())
        } else check(store.read(c.key) != null)
        val api = AccountApi(OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Isolated offline recovery check") }.build(), false, c.server)
        var owner = ViewModelStore()
        suspend fun create() = withContext(Dispatchers.Main) { ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app, api) as T
        })[AppViewModel::class.java] }
        suspend fun await(model: AppViewModel) { withTimeout(10000) { while (!model.state.value.ready) delay(50) } }
        var model = create(); await(model)
        val displayed = mutableStateOf(model)
        try {
            compose.setContent { FunnyWorkTheme { val state by displayed.value.state.collectAsState(); ReviewPanel(state, displayed.value) {} } }
            compose.onNodeWithText("first opinion").assertExists()
            if (phase != "resume") {
            compose.onNodeWithTag("review-round-menu").performClick(); compose.onNodeWithTag("review-round-19").performClick()
            compose.onNodeWithText("second opinion").performTextReplacement("second edited")
            compose.onNodeWithTag("review-round-menu").performClick(); compose.onNodeWithTag("review-round-9").performClick()
            compose.onNodeWithText("first opinion").assertExists()
            }
            if (phase == "prepare") {
                context.filesDir.resolve("round-review-process-report.json").writeText(JSONObject().put("stage", "ready-for-force-stop")
                    .put("pid", android.os.Process.myPid()).put("firstRound", 9).put("secondRound", 19).toString())
                awaitCancellation()
            }
            withContext(Dispatchers.Main) { owner.clear() }; owner = ViewModelStore(); model = create(); await(model)
            compose.runOnIdle { displayed.value = model }
            compose.onNodeWithText("first opinion").assertExists()
            compose.onNodeWithTag("review-round-menu").performClick(); compose.onNodeWithTag("review-round-19").performClick()
            compose.onNodeWithText("second edited").assertExists()
            compose.onNodeWithText(context.getString(R.string.review_stage)).performClick()
            val saved = requireNotNull(store.read(c.key)); val draft = saved.getJSONObject("drafts").text("desktop:fixture-task")
            assertTrue(draft.startsWith("existing typing\n\n")); assertEquals(1, Regex("second edited").findAll(draft).count())
            assertFalse(draft.contains("first opinion")); assertEquals("first opinion", ReviewDrafts.rounds(saved, "fixture-task").single().text("comment"))
            assertFalse(withContext(Dispatchers.Main) { model.stageReview() })
            assertEquals(draft, store.read(c.key)!!.getJSONObject("drafts").text("desktop:fixture-task"))
            if (phase == "resume") context.filesDir.resolve("round-review-process-report.json").writeText(JSONObject()
                .put("stage", "passed").put("pid", android.os.Process.myPid()).put("bothRoundsRestored", true)
                .put("onlyChosenRoundStagedOnce", true).put("otherRoundRetained", true).toString())
        } finally { withContext(Dispatchers.Main) { owner.clear() }; directory.deleteRecursively() }
    }
}
