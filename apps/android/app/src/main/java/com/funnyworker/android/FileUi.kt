package com.funnyworker.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

@Composable internal fun UploadActions(state: AppState, model: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFile) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::importFile) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.task != null && state.uploads.size < 4 && !state.commandBusy && state.commandRecords.none { it.optBoolean("pending") }) {
                Text(stringResource(R.string.add_file))
            }
            TextButton(onClick = { photos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = state.task != null && state.uploads.size < 4 && !state.commandBusy && state.commandRecords.none { it.optBoolean("pending") }) {
                Text(stringResource(R.string.add_photo))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.uploads, key = { it.text("hash") }) { row ->
                val file = runCatching { SyncedFile.from(row) }.getOrNull()
                if (file != null) OutlinedCard(Modifier.width(230.dp)) { Column(Modifier.padding(8.dp)) {
                    Text(file.name, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { if (file.hash in state.transferProgress) model.pauseTransfer(file.hash) else model.upload(file) }, enabled = !row.optBoolean("complete")) {
                            Text(if (row.optBoolean("complete")) stringResource(R.string.file_ready) else if (file.hash in state.transferProgress) stringResource(R.string.pause) else stringResource(R.string.upload))
                        }
                        TextButton(onClick = { model.removeUpload(file.hash) }, enabled = !state.commandBusy && state.commandRecords.none { it.optBoolean("pending") }) { Text(stringResource(R.string.remove)) }
                    }
                    state.transferProgress[file.hash]?.let { Text("$it / ${file.bytes}", style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }
    }
}

@Composable internal fun ResultDirectory(state: AppState, model: AppViewModel, close: () -> Unit) {
    key(state.credential?.key, state.task) { ResultDirectoryContent(state, model, close) }
}

@Composable private fun ResultDirectoryContent(state: AppState, model: AppViewModel, close: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var openError by remember { mutableStateOf(false) }
    var retrySaveFailed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Triple<SyncedFile, java.io.File, ResultPreviewSource>?>(null) }
    var export by remember { mutableStateOf<java.io.File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val local = export; export = null
        if (uri != null && local != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output -> local.inputStream().use { it.copyTo(output) } }
                        ?: error("Cannot save document")
                }
                openError = false
            } catch (_: Exception) { openError = true }
        }
    }
    LaunchedEffect(state.credential?.key, state.task) {
        while (isActive) {
            model.loadResults()
            delay(5000)
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text(stringResource(R.string.result_files), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
            TextButton(onClick = model::loadResults, modifier = Modifier.testTag("result-directory-refresh")) { Text(stringResource(R.string.refresh)) }
            if (openError) Text(stringResource(R.string.file_open_failed))
            if (retrySaveFailed) Text(stringResource(R.string.result_retry_save_failed))
            for (latest in ResultVersions.latest(state.resultFiles)) {
                val result = latest.getJSONObject("manifest").text("id")
                key(result) {
                val versions = ResultVersions.versions(state.resultFiles, result)
                var selected by remember(result) { mutableStateOf(JSONObject(latest.toString())) }
                var versionMenu by remember(result) { mutableStateOf(false) }
                val row = versions.firstOrNull { it.getJSONObject("manifest").sequence("version") == selected.getJSONObject("manifest").sequence("version") } ?: selected
                val manifest = row.getJSONObject("manifest")
                val version = manifest.optInt("version")
                Text((manifest.optJSONObject("original")?.optJSONObject("attachment")?.text("name") ?: result) + " · v$version", style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { versionMenu = true }, modifier = Modifier.testTag("result-version-$result")) { Text(stringResource(R.string.result_version, version) + " ▾") }
                    DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                        for (candidate in versions) DropdownMenuItem(text = { Text(stringResource(R.string.result_version, candidate.getJSONObject("manifest").getInt("version"))) },
                            onClick = { selected = JSONObject(candidate.toString()); preview = null; versionMenu = false })
                    }
                }
                Text(stringResource(when (manifest.text("previewState")) {
                    "ready" -> R.string.result_preview_ready
                    "failed" -> R.string.result_preview_failed
                    else -> R.string.result_preview_pending
                }), style = MaterialTheme.typography.bodySmall)
                if (manifest.text("previewState") == "failed") {
                    TextButton(onClick = {
                        val account = state.credential?.key ?: return@TextButton
                        val task = state.task ?: return@TextButton
                        val saved = model.stagePreviewRetry(ResultPreviewSource(account, task, JSONObject(manifest.toString())))
                        retrySaveFailed = !saved
                        if (saved) close()
                    }, modifier = Modifier.testTag("result-retry-preview-$result-$version")) {
                        Text(stringResource(R.string.result_retry_preview))
                    }
                }
                val variants = listOf("original" to manifest.optJSONObject("original")) + manifest.optJSONArray("variants")?.objects().orEmpty().map { it.text("key") to it.optJSONObject("content") }
                for ((key, reference) in variants) {
                    if (reference == null || !key.matches(Regex("[A-Za-z0-9_-]{1,80}"))) continue
                    val file = runCatching { SyncedFile.reference(reference) }.getOrNull() ?: continue
                    val task = state.task ?: continue; val credential = state.credential ?: continue
                    val path = "sync/sessions/$task/results/$result/versions/$version/files/${URLEncoder.encode(key, "UTF-8")}"
                    Text(if (key == "original") stringResource(R.string.result_original) + " · " + file.name else file.name)
                    val active = file.hash in state.transferProgress
                    Row {
                        TextButton(onClick = { if (active) model.pauseTransfer(file.hash) else model.download(file, path) }, modifier = Modifier.testTag("result-download-$result-$version-$key")) { Text(stringResource(if (active) R.string.pause else R.string.download)) }
                        TextButton(modifier = Modifier.testTag("result-open-${file.hash}"), onClick = { scope.launch {
                            try {
                                val local = withContext(Dispatchers.IO) { ClientFiles(context).verified(credential.key, file) } ?: error("Not downloaded")
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", local, file.name)
                                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: file.mediaType
                                if (mime.startsWith("image/") || mime == "application/pdf" || file.name.endsWith(".json", true)) preview = Triple(file, local, ResultPreviewSource(credential.key, task, JSONObject(manifest.toString())))
                                else context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                openError = false
                            } catch (_: Exception) { openError = true }
                        } }) { Text(stringResource(R.string.open_file)) }
                        TextButton(onClick = { scope.launch {
                            try {
                                export = withContext(Dispatchers.IO) { ClientFiles(context).verified(credential.key, file) } ?: error("Not downloaded")
                                save.launch(file.name); openError = false
                            } catch (_: Exception) { openError = true }
                        } }) { Text(stringResource(R.string.save_file)) }
                        TextButton(onClick = { scope.launch {
                            try {
                                val local = withContext(Dispatchers.IO) { ClientFiles(context).verified(credential.key, file) } ?: error("Not downloaded")
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", local, file.name)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(file.mediaType).putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name)); openError = false
                            } catch (_: Exception) { openError = true }
                        } }) { Text(stringResource(R.string.share)) }
                    }
                    state.transferProgress[file.hash]?.let { Text("$it / ${file.bytes}") }
                }
                }
            }
        } }
    }
    preview?.let { FilePreview(it.first, it.second, it.third, state, model) { preview = null } }
}

@Composable internal fun ReviewPanel(state: AppState, model: AppViewModel, close: () -> Unit) {
    val review = state.review ?: return
    var diff by remember(review.sequence("end")) { mutableStateOf<Int?>(null) }
    var feedback by remember(review.sequence("end")) { mutableStateOf(false) }
    var roundsMenu by remember { mutableStateOf(false) }
    val rounds = (ReviewSnapshot.rounds(state.desktopEvents).map { it.json() } + state.reviewDrafts + review)
        .associateBy { it.getLong("end") }.values.sortedByDescending { it.getLong("end") }
    val feedbackTitle = stringResource(R.string.review_feedback)
    val changes = review.getJSONArray("messages").objects().flatMap { message -> ReviewChanges.parse(message.getLong("seq"), message.text("text")).map { message.getLong("seq") to it } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text(stringResource(R.string.review), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
            Box {
                TextButton(onClick = { roundsMenu = true }, modifier = Modifier.testTag("review-round-menu")) {
                    Text(stringResource(R.string.review_round, review.getLong("end")) + " ▾")
                }
                DropdownMenu(expanded = roundsMenu, onDismissRequest = { roundsMenu = false }) {
                    for (round in rounds) DropdownMenuItem(text = { Text(stringResource(R.string.review_round, round.getLong("end"))) },
                        onClick = { model.selectReview(round.getLong("end")); roundsMenu = false }, modifier = Modifier.testTag("review-round-${round.getLong("end")}"))
                }
            }
            for (message in review.getJSONArray("messages").objects()) {
                val key = "#${message.getLong("seq")}"; Text(key)
                SelectionContainer { Text(message.text("text")) }
                OutlinedTextField(review.getJSONObject("notes").text(key), { model.reviewComment(it, key) }, label = { Text(stringResource(R.string.review_message_note)) }, modifier = Modifier.fillMaxWidth())
                for (change in ReviewChanges.parse(message.getLong("seq"), message.text("text"))) {
                    val changeIndex = changes.indexOfFirst { it.second.key == change.key }
                    TextButton(onClick = { diff = changeIndex.takeIf { it >= 0 } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-change-${message.getLong("seq")}-$changeIndex")) {
                        Text(change.path, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.reference_line), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(review.getJSONObject("notes").text(change.key), { model.reviewComment(it, change.key) }, label = { Text(stringResource(R.string.review_file_note)) }, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(review.text("comment"), { model.reviewComment(it) }, label = { Text(stringResource(R.string.review_comment)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { feedback = !feedback }) { Text(stringResource(R.string.review_feedback_preview)) }
            if (feedback) SelectionContainer { Text(runCatching { ReviewFeedback.text(review, feedbackTitle) }.getOrDefault("")) }
            Text(stringResource(R.string.review_stage_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (model.stageReview()) close() }, enabled = review.text("comment").isNotBlank() || review.getJSONObject("notes").let { n -> n.keys().asSequence().any { n.text(it).isNotBlank() } }) { Text(stringResource(R.string.review_stage)) }
        } }
    }
    diff?.let { ReviewDiffPanel(changes, it, state, model) { diff = null } }
}
