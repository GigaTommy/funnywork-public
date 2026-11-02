package com.funnyworker.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real API29 decoders and corrupt-file recovery; not a cloud late-upload acceptance. */
class FileFormatsRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pngJpegWebpMultipagePdfAndCorruptVersionRecoverWithoutStalePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.noBackupFilesDir.resolve("formats-" + UUID.randomUUID()).apply { mkdirs() }
        fun synced(file: File, mime: String) = SyncedFile(ClientFiles.digest(file), file.name, file.length(), mime)
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val images = listOf("png" to Bitmap.CompressFormat.PNG, "jpg" to Bitmap.CompressFormat.JPEG, "webp" to Bitmap.CompressFormat.WEBP).map { (extension, format) ->
            val local = directory.resolve("image.$extension")
            local.outputStream().use { check(bitmap.compress(format, 90, it)) }
            synced(local, if (extension == "jpg") "image/jpeg" else "image/$extension") to local
        }
        bitmap.recycle()
        val pdf = directory.resolve("two-pages.pdf")
        val document = PdfDocument()
        try {
            for (i in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, i).create())
                page.canvas.drawText("Page $i - retained version", 20f, 60f, Paint().apply { color = Color.BLACK; textSize = 16f })
                document.finishPage(page)
            }
            pdf.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val broken = directory.resolve("broken.pdf").apply { writeText("Incomplete PDF transfer") }
        var selected by mutableStateOf(images.first())
        try {
            compose.setContent { FunnyWorkTheme { FilePreview(selected.first, selected.second) {} } }
            for (image in images) {
                compose.runOnIdle { selected = image }
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(image.first.name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(image.first.name).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.file_open_failed)).assertDoesNotExist()
            }
            compose.runOnIdle { selected = synced(broken, "application/pdf") to broken }
            compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.file_open_failed)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(images.last().first.name).assertDoesNotExist()
            compose.runOnIdle { selected = synced(pdf, "application/pdf") to pdf }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(pdf.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.next_page)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.next_page)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.previous_page)).performClick()
            compose.waitUntil(5000) { compose.onNodeWithText("1 / 2").isDisplayed() }
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.file_open_failed)).assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                context.filesDir.resolve("ux-formats-ui.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            compose.runOnIdle { selected = images.first() }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(images.first().first.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 / 2").assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }
}
