package com.funnyworker.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable internal fun FilePreview(file: SyncedFile, local: File, source: ResultPreviewSource? = null, state: AppState? = null, model: AppViewModel? = null, close: () -> Unit) {
    var page by remember(file.hash) { mutableIntStateOf(0) }
    var count by remember(file.hash) { mutableIntStateOf(1) }
    var bitmap by remember(file.hash) { mutableStateOf<Bitmap?>(null) }
    var workbook by remember(file.hash) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(file.hash) { mutableStateOf(false) }
    var scale by remember(file.hash, page) { mutableFloatStateOf(1f) }
    var translation by remember(file.hash, page) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); translation += pan }
    LaunchedEffect(file.hash, page) {
        bitmap = null; failed = false
        try {
            val decoded = withContext(Dispatchers.IO) {
                when {
                    file.name.endsWith(".pdf", true) -> ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                        renderer.openPage(page).use { p ->
                            val ratio = minOf(1600f / p.width, 4096f / p.height, 1f)
                            val image = Bitmap.createBitmap(maxOf(1, (p.width * ratio).toInt()), maxOf(1, (p.height * ratio).toInt()), Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE); p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to renderer.pageCount
                        }
                    } }
                    file.name.endsWith(".json", true) -> { require(local.length() <= 2097152); null }
                    else -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(local)) { decoder, info, _ ->
                        var sample = 1
                        while (maxOf(info.size.width / sample, info.size.height / sample) > 4096 || info.size.width.toLong() * info.size.height / sample / sample > 16777216) sample *= 2
                        decoder.setTargetSampleSize(sample); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    } to 1
                }
            }
            if (decoded != null) { bitmap = decoded.first; count = decoded.second }
            else workbook = withContext(Dispatchers.IO) { JSONObject(local.readText()).also { data ->
                require(data.optInt("schema") == 1)
                require(data.has("sheets") || data.has("cells"))
                data.optJSONArray("sheets")?.let { require(it.length() <= 1000) }
                data.optJSONArray("cells")?.let { cells ->
                    require(cells.length() <= 250000)
                    require(cells.objects().all { it.optInt("row", -1) >= 0 && it.optInt("column", -1) >= 0 })
                }
            } }
        } catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Row { Text(file.name, Modifier.weight(1f), maxLines = 2); TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
            if (failed) Text(stringResource(R.string.file_open_failed))
            bitmap?.let { image ->
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(transform)) {
                    Image(image.asImageBitmap(), file.name, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = translation.x, translationY = translation.y))
                }
                if (count > 1) Row {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.previous_page)) }
                    Text("${page + 1} / $count")
                    TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text(stringResource(R.string.next_page)) }
                }
            }
            workbook?.let { data -> WorkbookPreview(data, source, state, model) }
        } }
    }
}
