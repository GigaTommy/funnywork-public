package com.funnyworker.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun QueuePanel(state: AppState, model: AppViewModel) {
    var editing by remember(state.task) { mutableStateOf<RemoteQueueItem?>(null) }
    val queue = state.remoteState?.queue.orEmpty()
    var confirmCopy by remember { mutableStateOf(false) }
    if (RemoteQueue.canRecover(state.commandRecords)) TextButton(onClick = { confirmCopy = true }, enabled = !state.commandBusy && !state.loading) { Text(stringResource(R.string.keep_withdrawal_copy)) }
    if (confirmCopy) AlertDialog(onDismissRequest = { confirmCopy = false }, title = { Text(stringResource(R.string.keep_withdrawal_copy)) },
        text = { Text(stringResource(R.string.withdrawal_copy_warning)) },
        confirmButton = { TextButton(onClick = { model.keepWithdrawalCopy(); confirmCopy = false }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.cancel)) } })
    if (RemoteQueue.withdrawing(state.commandRecords)) Text(stringResource(R.string.withdraw_check))
    if (queue.isNotEmpty()) Text(stringResource(R.string.task_queue), style = MaterialTheme.typography.titleMedium)
    for (item in queue) OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(item.text)
        val record = state.commandRecords.lastOrNull { it.text("kind") == "queueEdit" && it.optJSONObject("payload")?.text("itemId") == item.id }
        record?.let { Text(receiptLabel(it.text("status")), style = MaterialTheme.typography.bodySmall) }
        val enabled = state.canControl() && !RemoteQueue.waiting(state.commandRecords, item.id)
        Row {
            TextButton(onClick = { editing = item }, enabled = enabled && item.editable) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = { model.updateQueue(item, "remove") }, enabled = enabled && item.editable && !RemoteQueue.withdrawing(state.commandRecords)) { Text(stringResource(R.string.withdraw_to_draft)) }
            TextButton(onClick = { model.updateQueue(item, "steer") }, enabled = enabled && state.remoteState?.running == true) { Text(stringResource(R.string.remote_steer)) }
        }
    } }
    for (key in state.queueEdits.keys()) {
        if (queue.any { it.id == key } || state.commandRecords.lastOrNull { it.text("kind") == "queueEdit" && it.optJSONObject("payload")?.text("itemId") == key && it.optJSONObject("payload")?.text("action") == "edit" }?.let { it.text("status") == "accepted" && it.getJSONObject("payload").text("text") == state.queueEdits.text(key) } == true) continue
        Text(state.queueEdits.text(key), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { model.recoverQueueEdit(key) }, enabled = !RemoteQueue.waiting(state.commandRecords, key)) { Text(stringResource(R.string.keep_edit_copy)) }
    }
    editing?.let { item ->
        val value = state.queueEdits.opt(item.id) as? String ?: item.text
        val waiting = RemoteQueue.waiting(state.commandRecords, item.id)
        Dialog(onDismissRequest = { editing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row { Text(stringResource(R.string.edit_queue), Modifier.weight(1f)); TextButton(onClick = { editing = null }) { Text(stringResource(R.string.done)) } }
                Text(stringResource(R.string.queue_copy_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value, { model.saveQueueEdit(item.id, it) }, enabled = !waiting, modifier = Modifier.fillMaxWidth().weight(1f))
                Button(onClick = { model.updateQueue(item, "edit") }, enabled = state.canControl() && !waiting && value.isNotBlank() && value.length <= 16000 && queue.any { it.id == item.id && it.text == item.text && it.editable }) { Text(stringResource(R.string.submit_edit)) }
            } }
        }
    }
}
