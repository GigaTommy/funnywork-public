package com.funnyworker.android

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test

class ReviewWorkbookUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun numberedDiffLinesAreSelectableAndHeadersCannotCreateReferences() {
        val change = ReviewChange("#8 · report.txt", "report.txt", "--- a/report.txt\n+++ b/report.txt\n@@ -1 +1 @@\n-old\n+new")
        val model = AppViewModel(context.applicationContext as Application)
        compose.setContent { FunnyWorkTheme { ReviewDiffPanel(listOf(8L to change), 0, AppState(review = JSONObject().put("comment", "").put("notes", JSONObject())), model) {} } }
        compose.onNodeWithText("+new").assertHasClickAction()
        compose.onNodeWithText("--- a/report.txt").assertIsNotEnabled()
        screenshot("android-review-lines-ui.png")
    }
    @Test fun worksheetShowsCachedValuesMergedCellsAndOnlySelectedColumnWindow() {
        val sheet = WorkbookSheet("s1", "Monthly report", 3, 30, emptyList(), listOf(20.0))
        val tile = WorkbookTile(0, listOf(
            WorkbookCell(0, 0, "Monthly report", false, true, false, "center", 1, 3),
            WorkbookCell(1, 0, "Revenue", false, false, false, "left", 1, 1),
            WorkbookCell(1, 1, "100.00", false, true, false, "right", 1, 1),
            WorkbookCell(2, 0, "Missing cached result", true, false, true, "left", 1, 2),
            WorkbookCell(0, 15, "OUTSIDE_WINDOW", false, false, false, "left", 1, 1)))
        compose.setContent { FunnyWorkTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) { WorkbookGrid(sheet, tile, 0, Modifier.weight(1f)) } } } }
        compose.onNodeWithText("Monthly report").assertIsDisplayed()
        compose.onNodeWithText("100.00").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.value_unknown)).assertIsDisplayed()
        compose.onNodeWithText("OUTSIDE_WINDOW").assertDoesNotExist()
        screenshot("android-workbook-ui.png")
    }
    @Test fun cachedWorkbookSwitchesSheetsWithoutUsingAnotherVersionOrNetwork() {
        val credential = Credential(ServiceIdentity.INTERNATIONAL, "unused-ui-fixture", "workbook-ui", "isolated-fixture", "UI")
        val cache = ClientFiles(context); cache.directory(credential.key).mkdirs()
        val variants = JSONArray(); val sheets = JSONArray()
        for (n in 1..2) {
            val tile = JSONObject().put("schema", 1).put("sheetId", "s$n").put("firstRow", 0).put("cells", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", "SAVED_VALUE_$n")))
            val bytes = tile.toString().toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = SyncedFile(hash, "sheet-$n.json", bytes.size.toLong(), "application/json")
            cache.local(credential.key, file).writeBytes(bytes)
            variants.put(JSONObject().put("key", "sheet-$n").put("role", "sheet").put("content", JSONObject().put("type", "file").put("attachment", JSONObject().put("attachmentId", "sha256:$hash").put("name", file.name).put("bytes", file.bytes))))
            sheets.put(JSONObject().put("id", "s$n").put("name", "Sheet $n").put("rows", 1).put("columns", 1).put("tiles", JSONArray().put("sheet-$n")).put("widths", JSONArray()))
        }
        val index = JSONObject().put("schema", 1).put("sheets", sheets)
        val manifest = JSONObject().put("id", "ui-workbook").put("version", 7).put("variants", variants)
        val model = AppViewModel(context.applicationContext as Application)
        try {
            compose.setContent { FunnyWorkTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                WorkbookPreview(index, ResultPreviewSource(credential.key, "fixture-task", manifest), AppState(credential = credential), model)
            } } } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("SAVED_VALUE_1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sheet 1 ▾").performClick()
            compose.onNodeWithText("Sheet 2").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("SAVED_VALUE_2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SAVED_VALUE_1").assertDoesNotExist()
        } finally { cache.clear(credential.key) }
    }
    @Test fun complexWorkbookMovesAcrossRowBlocksColumnsAndAnEmptySheet() {
        val credential = Credential(ServiceIdentity.INTERNATIONAL, "unused-ui-fixture", "complex-workbook", "isolated-fixture", "UI")
        val cache = ClientFiles(context); cache.directory(credential.key).mkdirs()
        val variants = JSONArray(); val keys = JSONArray()
        for (part in 0..2) {
            val cells = JSONArray().put(JSONObject().put("row", part * 100).put("column", 0).put("text", "BLOCK_$part")
                .put("mergeRows", 2).put("mergeColumns", 3).put("bold", true).put("align", "center"))
                .put(JSONObject().put("row", part * 100 + 1).put("column", 15).put("text", "COL_P_$part"))
                .put(JSONObject().put("row", part * 100 + 2).put("column", 0).put("text", "uncomputed").put("missingResult", true))
            val bytes = JSONObject().put("schema", 1).put("sheetId", "wide").put("firstRow", part * 100).put("cells", cells).toString().toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = SyncedFile(hash, "tile-$part.json", bytes.size.toLong(), "application/json"); cache.local(credential.key, file).writeBytes(bytes)
            val key = "wide-$part"; keys.put(key)
            variants.put(JSONObject().put("key", key).put("role", "sheet").put("content", JSONObject().put("type", "file").put("attachment",
                JSONObject().put("attachmentId", "sha256:$hash").put("name", file.name).put("bytes", file.bytes))))
        }
        val sheets = JSONArray().put(JSONObject().put("id", "wide").put("name", "Wide").put("rows", 225).put("columns", 30).put("tiles", keys).put("widths", JSONArray().put(20).put(0).put(255)))
            .put(JSONObject().put("id", "empty").put("name", "Empty").put("rows", 0).put("columns", 0).put("tiles", JSONArray()).put("widths", JSONArray()))
        val index = JSONObject().put("schema", 1).put("sheets", sheets)
        val model = AppViewModel(context.applicationContext as Application)
        try {
            compose.setContent { FunnyWorkTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                WorkbookPreview(index, ResultPreviewSource(credential.key, "fixture-task", JSONObject().put("id", "complex").put("version", 3).put("variants", variants)), AppState(credential = credential), model)
            } } } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("BLOCK_0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.value_unknown)).assertExists()
            compose.onNodeWithTag("workbook-next-columns").performClick()
            compose.onNodeWithText("COL_P_0").assertExists()
            compose.onAllNodesWithText("›").onFirst().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("COL_P_1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("COL_P_0").assertDoesNotExist()
            compose.onAllNodesWithText("›").onFirst().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("COL_P_2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Wide ▾").performClick(); compose.onNodeWithText("Empty").performClick()
            compose.onNodeWithText(context.getString(R.string.sheet_empty)).assertExists()
            compose.onNodeWithText("COL_P_2").assertDoesNotExist()
        } finally { cache.clear(credential.key) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            context.filesDir.resolve(name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
}
