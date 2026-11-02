package com.funnyworker.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun ReviewDiffPanel(changes: List<Pair<Long, ReviewChange>>, initial: Int, state: AppState, model: AppViewModel, close: () -> Unit) {
    var selected by remember { mutableIntStateOf(initial) }
    val (sequence, change) = changes[selected]
    val lines = remember(change.key) { ReviewDiff.parse(change.patch) }
    val old = stringResource(R.string.old_line); val new = stringResource(R.string.new_line)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), contentColor = MaterialTheme.colorScheme.onSurface) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Row { Text(change.path, Modifier.weight(1f), maxLines = 2); TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
            Row {
                TextButton(onClick = { selected-- }, enabled = selected > 0) { Text(stringResource(R.string.previous_file)) }
                Text("${selected + 1} / ${changes.size} · #$sequence")
                TextButton(onClick = { selected++ }, enabled = selected + 1 < changes.size) { Text(stringResource(R.string.next_file)) }
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { LazyColumn(Modifier.widthIn(min = 700.dp).testTag("review-diff-lines")) {
                itemsIndexed(lines) { index, line ->
                    val reference = line.reference(sequence, change.path, old, new)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (line.added) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surface)
                        .clickable(enabled = reference != null) {
                            if (reference != null) model.reviewLine(sequence, change.key, index)
                        }.padding(8.dp)) {
                        Text(line.old?.toString().orEmpty(), Modifier.width(48.dp), fontFamily = FontFamily.Monospace)
                        Text(line.new?.toString().orEmpty(), Modifier.width(48.dp), fontFamily = FontFamily.Monospace)
                        Text(line.text, fontFamily = FontFamily.Monospace)
                    }
                }
            } }
            Text(stringResource(R.string.reference_line), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(state.review?.optJSONObject("notes")?.text(change.key).orEmpty(), { model.reviewComment(it, change.key) }, label = { Text(stringResource(R.string.review_file_note)) }, maxLines = 5, modifier = Modifier.fillMaxWidth())
        } }
    }
}
