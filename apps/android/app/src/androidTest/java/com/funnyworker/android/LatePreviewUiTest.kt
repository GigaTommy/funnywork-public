package com.funnyworker.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Delayed directory updates, actual cached image preview, no credentials or service writes. */
class LatePreviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lateWorkbookAndNewerVersionNeverReplaceChosenOriginalOrVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = context.noBackupFilesDir.resolve("late-preview-ui-fixture").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getNoBackupFilesDir() = isolated
            override fun getFilesDir() = isolated.resolve("files").apply { mkdirs() }
        }
        val c = Credential(ServiceIdentity.INTERNATIONAL, "unused-ui-fixture", "late-preview-ui", "isolated-fixture", "UI")
        val cache = ClientFiles(context); cache.directory(c.key).mkdirs()
        fun reference(name: String, bytes: ByteArray): JSONObject {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = SyncedFile(hash, name, bytes.size.toLong(), if (name.endsWith("png")) "image/png" else "application/json")
            cache.local(c.key, file).writeBytes(bytes)
            return JSONObject().put("type", "file").put("attachment", JSONObject().put("attachmentId", "sha256:$hash").put("name", name).put("bytes", bytes.size))
        }
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        val original = reference("late.png", bytes)
        val workbook = reference("preview.json", JSONObject().put("schema", 1).put("sheets", JSONArray()).toString().toByteArray())
        fun row(version: Int, ready: Boolean) = JSONObject().put("manifest", JSONObject().put("id", "late-result").put("version", version)
            .put("original", original).put("previewState", if (ready) "ready" else "pending")
            .put("variants", if (ready) JSONArray().put(JSONObject().put("key", "workbook").put("role", "workbook").put("content", workbook)) else JSONArray()))
        var state by mutableStateOf(AppState(credential = c, task = "fixture-task", resultFiles = listOf(row(1, false), row(2, false))))
        val model = AppViewModel(app)
        try {
            compose.setContent { FunnyWorkTheme { ResultDirectory(state, model) {} } }
            compose.onNodeWithTag("result-version-late-result").performClick()
            compose.onNodeWithText(context.getString(R.string.result_version, 1)).performClick()
            compose.onNodeWithTag("result-open-${SyncedFile.reference(original).hash}").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("late.png").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { state = state.copy(resultFiles = listOf(row(1, true), row(2, true), row(3, true))) }
            compose.onNodeWithContentDescription("late.png").assertIsDisplayed()
            compose.onAllNodesWithText(context.getString(R.string.done)).onLast().performClick()
            compose.onNodeWithText("late.png · v1").assertExists()
            compose.onNodeWithTag("result-open-${SyncedFile.reference(workbook).hash}").assertExists()
            compose.onNodeWithText(context.getString(R.string.result_preview_ready)).assertExists()
            compose.onNodeWithTag("result-version-late-result").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.result_version, 2)).performClick()
            compose.runOnIdle { state = state.copy(resultFiles = listOf(row(1, true), row(2, true), row(4, true))) }
            compose.onNodeWithText("late.png · v2").assertExists()
            compose.onNodeWithText("late.png · v4").assertDoesNotExist()
        } finally { cache.clear(c.key); isolated.deleteRecursively() }
    }
}
