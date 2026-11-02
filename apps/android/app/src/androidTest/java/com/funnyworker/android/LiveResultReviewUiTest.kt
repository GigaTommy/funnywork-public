package com.funnyworker.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** UI consumes actual native v1/v2 manifests, workbook tiles and a durable reply diff. */
class LiveResultReviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeVersionsWorkbookAndLineReviewStayBoundToOneAccountAndTask(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveResultReviewUI") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = JSONObject(context.filesDir.resolve("android-review-ui-config.json").readText())
        val account = JSONObject(context.filesDir.resolve("android-uat-account.json").readText())
        val api = AccountApi(automaticRouting = false, fixedOrigin = cfg.text("origin"))
        var owner = ViewModelStore()
        suspend fun create(): AppViewModel = withContext(Dispatchers.Main) { ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, api) as T
        })[AppViewModel::class.java] }
        var model = create()
        suspend fun await(predicate: () -> Boolean) { withTimeout(120000) { while (!predicate()) delay(150) } }
        val report = JSONObject().put("origin", cfg.text("origin")).put("sessionId", cfg.text("sessionId")).put("resultId", cfg.text("resultId"))
        val screen = mutableStateOf("directory")
        try {
            await { model.state.value.ready }
            withContext(Dispatchers.Main) { model.login(cfg.text("origin"), account.text("username"), account.text("password")) }
            await { model.state.value.credential != null && !model.state.value.loading }
            withContext(Dispatchers.Main) { model.source(true); model.select(Row(cfg.text("sessionId"), "Native result UI acceptance", JSONObject().put("id", cfg.text("sessionId")))) }
            await { model.state.value.remoteReady && !model.state.value.loading && !model.state.value.commandBusy }
            withContext(Dispatchers.Main) { model.loadResults() }
            await { ResultVersions.versions(model.state.value.resultFiles, cfg.text("resultId")).size >= 2 }
            val c = model.state.value.credential!!
            val versions = ResultVersions.versions(model.state.value.resultFiles, cfg.text("resultId"))
            val v2 = versions.single { it.getJSONObject("manifest").getInt("version") == 2 }.getJSONObject("manifest")
            val workbook = v2.getJSONArray("variants").objects().single { it.text("role") == "workbook" }
            val key = workbook.text("key"); val file = SyncedFile.reference(workbook.getJSONObject("content"))
            withContext(Dispatchers.Main) { model.download(file, "sync/sessions/${cfg.text("sessionId")}/results/${cfg.text("resultId")}/versions/2/files/$key") }
            await { ClientFiles(context).verified(c.key, file) != null }
            val displayed = model
            compose.setContent { FunnyWorkTheme {
                val state by displayed.state.collectAsState()
                when (screen.value) {
                    "directory" -> ResultDirectory(state, displayed) { screen.value = "none" }
                    "review" -> ReviewPanel(state, displayed) { screen.value = "none" }
                }
            } }
            val versionTag = "result-version-${cfg.text("resultId")}"
            compose.onNodeWithTag(versionTag).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.result_version, 1)).performClick()
            compose.onNodeWithText("recovery.xlsx · v1").assertExists()
            withContext(Dispatchers.Main) { model.loadResults() }; delay(1500)
            compose.onNodeWithText("recovery.xlsx · v1").assertExists()
            report.put("explicitV1SurvivedRefresh", true)
            compose.onNodeWithTag(versionTag).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.result_version, 2)).performClick()
            compose.onNodeWithTag("result-open-${file.hash}").performScrollTo().performClick()
            delay(2500); screenshot("native-workbook-opening-${cfg.text("marker")}.png")
            context.filesDir.resolve("native-ui-tree-${cfg.text("marker")}.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n") { compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString() })
            compose.waitUntil(60000) { compose.onAllNodesWithText("V2_${cfg.text("marker")}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("V2_${cfg.text("marker")}").assertExists()
            compose.onNodeWithText(context.getString(R.string.value_unknown)).assertExists()
            screenshot("native-workbook-summary-${cfg.text("marker")}.png")
            compose.onNodeWithText("Summary ▾").performClick(); compose.onNodeWithText("Details").performClick()
            compose.waitUntil(60000) { compose.onAllNodesWithText("SECOND_${cfg.text("marker")}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("COL_P_${cfg.text("marker")}").assertDoesNotExist()
            compose.onNodeWithTag("workbook-next-columns").performClick()
            compose.onNodeWithText("COL_P_${cfg.text("marker")}").assertExists()
            screenshot("native-workbook-columns-${cfg.text("marker")}.png")
            report.put("actualNativeWorkbookRendered", true).put("actualSheetSwitchAndColumnWindow", true).put("missingFormulaCacheNotEvaluated", true)
            compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
            withContext(Dispatchers.Main) { screen.value = "none"; model.freezeReview() }
            val review = model.state.value.review!!
            val changes = review.getJSONArray("messages").objects().flatMap { m -> ReviewChanges.parse(m.getLong("seq"), m.text("text")).map { m.getLong("seq") to it } }
            assertTrue(changes.any { it.second.patch.contains("+V2_${cfg.text("marker")}") })
            val (sequence, change) = changes.single { it.second.patch.contains("+V2_${cfg.text("marker")}") }
            val beforeCommands = api.call(c, "sync/sessions/${cfg.text("sessionId")}/commands")
            withContext(Dispatchers.Main) { model.draft("KEEP_REVIEW_${cfg.text("marker")}"); screen.value = "review" }
            val changeIndex = changes.indexOfFirst { it.second.key == change.key }
            compose.onNodeWithTag("review-change-$sequence-$changeIndex").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("review-diff-lines").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("review-diff-lines").performScrollToNode(hasText("+V2_${cfg.text("marker")}"))
            compose.onNodeWithText("+V2_${cfg.text("marker")}").performClick(); compose.onNodeWithText("+V2_${cfg.text("marker")}").performClick()
            val line = ReviewDiff.parse(change.patch).single { it.text == "+V2_${cfg.text("marker")}" }
            assertEquals(2, line.new)
            val reference = line.reference(sequence, change.path, context.getString(R.string.old_line), context.getString(R.string.new_line))!!
            assertEquals(1, model.state.value.review!!.text("comment").windowed(reference.length).count { it == reference })
            screenshot("native-line-review-${cfg.text("marker")}.png")
            compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
            compose.onNodeWithText(context.getString(R.string.review_stage)).performScrollTo().performClick()
            await { model.state.value.review == null }
            val draft = model.state.value.draft
            assertTrue(draft.startsWith("KEEP_REVIEW_")); assertTrue(draft.contains(reference))
            assertFalse(withContext(Dispatchers.Main) { model.stageReview() })
            withContext(Dispatchers.Main) { screen.value = "none"; owner.clear() };owner = ViewModelStore();model = create()
            await { model.state.value.ready && !model.state.value.loading }
            assertEquals(draft, model.state.value.draft); assertEquals(cfg.text("sessionId"), model.state.value.task)
            assertEquals(beforeCommands, api.call(model.state.value.credential!!, "sync/sessions/${cfg.text("sessionId")}/commands"))
            report.put("realDiffSequence", sequence).put("lineNewNumber", 2).put("duplicateLineTapAppendedOnce", true)
                .put("reviewStagedOnce", true).put("draftRestoredAfterViewModelRecreation", true).put("noCommandAutomaticallySent", true).put("passed", true)
        } catch (failure: Throwable) {
            runCatching { screenshot("native-ui-failure-${cfg.text("marker")}.png") }
            report.put("errorResource", model.state.value.error ?: 0)
            throw failure
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { model.logout() };await { model.state.value.credential == null }
                withContext(Dispatchers.Main) { owner.clear() }
            }
            context.filesDir.resolve("android-uat-account.json").delete()
            context.filesDir.resolve("android-review-ui-report.json").writeText(report.toString(2))
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve(name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) };image.recycle()
        }
    }
}
