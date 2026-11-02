package com.funnyworker.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

data class ResultPreviewSource(val account: String, val task: String, val manifest: JSONObject) {
    val identity: String get() = "$account/$task/${manifest.text("id")}/${manifest.optInt("version")}"
}

@Composable internal fun ColumnScope.WorkbookPreview(data: JSONObject, source: ResultPreviewSource?, state: AppState?, model: AppViewModel?) {
    val context = LocalContext.current
    val sheets = remember(data, source?.identity) { runCatching { WorkbookData.index(data) }.getOrNull() }
    if (sheets == null || source == null || state?.credential?.key != source.account || model == null) { Text(stringResource(R.string.file_open_failed)); return }
    var sheetIndex by remember(data, source?.identity) { mutableIntStateOf(0) }
    var tileIndex by remember(data, source?.identity, sheetIndex) { mutableIntStateOf(0) }
    var columnStart by remember(data, source?.identity, sheetIndex) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var tile by remember(data, source?.identity, sheetIndex, tileIndex) { mutableStateOf<WorkbookTile?>(null) }
    var failed by remember(data, source?.identity, sheetIndex, tileIndex) { mutableStateOf(false) }
    var retry by remember(data, source?.identity, sheetIndex, tileIndex) { mutableIntStateOf(0) }
    val sheet = sheets.getOrNull(sheetIndex) ?: run { Text(stringResource(R.string.sheet_empty)); return }
    val key = sheet.tiles.getOrNull(tileIndex)
    LaunchedEffect(data, sheet.id, key, source.identity, retry) {
        if (key == null) return@LaunchedEffect
        try {
            val variant = source.manifest.getJSONArray("variants").objects().single { it.text("key") == key }
            val file = SyncedFile.reference(variant.getJSONObject("content"))
            val path = "sync/sessions/${source.task}/results/${source.manifest.getString("id")}/versions/${source.manifest.getInt("version")}/files/$key"
            val cache = ClientFiles(context)
            var local = withContext(Dispatchers.IO) { cache.verified(source.account, file) }
            if (local == null) {
                model.download(file, path)
                withTimeout(60000) {
                    while (local == null) {
                        delay(200); local = withContext(Dispatchers.IO) { cache.verified(source.account, file) }
                        if (local == null && !model.transferActive(file.hash)) throw java.io.IOException("Workbook tile is unavailable")
                    }
                }
            }
            tile = withContext(Dispatchers.IO) { require(local!!.length() <= 2097152); WorkbookData.tile(JSONObject(local!!.readText()), sheet) }
        } catch (_: TimeoutCancellationException) { failed = true }
        catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    Text(stringResource(R.string.saved_values_only), style = MaterialTheme.typography.bodySmall)
    Box {
        TextButton(onClick = { menu = true }) { Text(sheet.name + " ▾") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            sheets.forEachIndexed { index, item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { sheetIndex = index; menu = false }) }
        }
    }
    Row {
        TextButton(onClick = { tileIndex-- }, enabled = tileIndex > 0) { Text("‹") }
        Text("${stringResource(R.string.sheet_part)} ${if (key == null) 0 else tileIndex + 1} / ${sheet.tiles.size}")
        TextButton(onClick = { tileIndex++ }, enabled = tileIndex + 1 < sheet.tiles.size) { Text("›") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { columnStart = maxOf(0, columnStart - 12) }, enabled = columnStart > 0) { Text("‹") }
        Text(if (sheet.columns == 0) "0" else "${WorkbookData.columnName(columnStart)}–${WorkbookData.columnName(minOf(sheet.columns - 1, columnStart + 11))}")
        TextButton(onClick = { columnStart += 12 }, enabled = columnStart + 12 < sheet.columns, modifier = Modifier.testTag("workbook-next-columns")) { Text("›") }
    }
    when {
        failed -> {
            Text(stringResource(R.string.file_open_failed))
            TextButton(onClick = { failed = false; tile = null; retry++ }) { Text(stringResource(R.string.retry)) }
        }
        key == null -> Text(stringResource(R.string.sheet_empty))
        tile == null -> Text(stringResource(R.string.sheet_loading))
        else -> WorkbookGrid(sheet, tile!!, columnStart, Modifier.weight(1f))
    }
}

@Composable internal fun WorkbookGrid(sheet: WorkbookSheet, tile: WorkbookTile, start: Int, modifier: Modifier) {
    val columns = start until minOf(sheet.columns, start + 12)
    val rows = tile.firstRow until minOf(sheet.rows, tile.firstRow + 100)
    val x = mutableMapOf<Int, Float>(); var width = 40f
    for (c in columns) { x[c] = width; width += sheet.width(c) + 1 }
    val background = MaterialTheme.colorScheme.surfaceVariant
    val normal = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
        Box(Modifier.size(width.dp, (30 + rows.count() * 37).dp)) {
            for (c in columns) Text(WorkbookData.columnName(c), Modifier.offset(x[c]!!.dp, 0.dp).size(sheet.width(c).dp, 30.dp).background(background), style = MaterialTheme.typography.bodySmall)
            for (r in rows) Text("${r + 1}", Modifier.offset(0.dp, (30 + (r - tile.firstRow) * 37).dp).size(40.dp, 36.dp).background(background), style = MaterialTheme.typography.bodySmall)
            for (cell in tile.cells.filter { it.row in rows && it.column < start + 12 && it.column + it.columns > start }.sortedBy { if (it.rows > 1 || it.columns > 1) 1 else 0 }) {
                val a = maxOf(start, cell.column); val b = minOf(sheet.columns, start + 12, cell.column + cell.columns)
                val cellWidth = (a until b).sumOf { sheet.width(it).toDouble() + 1 }.toFloat() - 1
                val cellHeight = minOf(cell.rows, rows.last - cell.row + 1) * 37 - 1
                Box(Modifier.offset(x[a]!!.dp, (30 + (cell.row - tile.firstRow) * 37).dp).size(cellWidth.dp, cellHeight.dp).background(normal).padding(horizontal = 4.dp),
                    contentAlignment = when (cell.align) { "right" -> Alignment.CenterEnd; "center" -> Alignment.Center; else -> Alignment.CenterStart }) {
                    Text(if (cell.missing) stringResource(R.string.value_unknown) else cell.text, maxLines = 1,
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
