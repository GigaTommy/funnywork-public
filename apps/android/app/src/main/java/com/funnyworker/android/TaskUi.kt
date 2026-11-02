package com.funnyworker.android

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun TaskRowSummary(row: Row, state: AppState) {
    val now by produceState(SystemClock.elapsedRealtime(), state.tasksReceivedAt) {
        while (true) { value = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val unread = TaskDirectory.unread(row, state.taskReads[row.id] ?: 0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.title.ifBlank { stringResource(R.string.untitled) }, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
        if (unread) Text(stringResource(R.string.task_unread), style = MaterialTheme.typography.labelSmall)
        val name = remoteHostName(row.json.text("device_name"), row.json.text("device_id"))
        if (name.isNotBlank()) Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val device = state.devices.firstOrNull { it.id == row.json.text("device_id") }
        val stage = TaskDirectory.stage(row, state.tasksFresh && state.devicesFresh && device != null, row.json.sequence("_receivedAt") ?: state.tasksReceivedAt, now,
            device?.json?.optBoolean("online") == true && !device.json.optBoolean("revoked"))
        when (stage) {
            TaskStage.ONLINE -> ConnectionLabel(DeviceConnection.ONLINE)
            TaskStage.OFFLINE -> ConnectionLabel(DeviceConnection.OFFLINE)
            TaskStage.UNKNOWN -> ConnectionLabel(DeviceConnection.UNKNOWN)
            else -> SemanticStatus(stringResource(when (stage) {
                TaskStage.APPROVAL -> R.string.task_approval
                TaskStage.QUESTION -> R.string.task_question
                TaskStage.WAITING -> R.string.task_capacity
                else -> R.string.task_running
            }), if (stage == TaskStage.RUNNING) StatusKind.RUNNING else StatusKind.WAITING)
        }
    }
}

@Composable internal fun CreationProgress(state: AppState, check: () -> Unit, retry: () -> Unit, clear: () -> Unit, open: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    state.createdTask?.let {
        SemanticStatus(stringResource(R.string.creation_ready), StatusKind.COMPLETED)
        Button(onClick = open) { Text(stringResource(R.string.creation_open)) }
    }
    state.pendingCreate?.let { record ->
        val valid = CreationRecovery.valid(record)
        val status = if (valid) CreationRecovery.status(record) else "unknown"
        SemanticStatus(stringResource(when (status) {
            "sending" -> R.string.creation_sending
            "queued" -> R.string.command_queued
            "claimed" -> R.string.command_claimed
            "rejected" -> R.string.creation_rejected
            "expired" -> R.string.creation_expired
            "missing" -> R.string.creation_missing
            else -> R.string.creation_unknown
        }), when(status) {
            "sending", "queued", "claimed" -> StatusKind.RUNNING
            "rejected", "expired" -> StatusKind.FAILED
            else -> StatusKind.WAITING
        })
        if (state.creationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = check, enabled = valid && !state.creationBusy) { Text(stringResource(R.string.check)) }
        if (state.devicesFresh && CreationRecovery.canRetry(record, state.availableDevice(record.text("device")), now)) {
            TextButton(onClick = retry, enabled = !state.creationBusy) { Text(stringResource(R.string.creation_retry_original)) }
        }
        if (CreationRecovery.canClear(record, now)) TextButton(onClick = { confirm = true }, enabled = !state.creationBusy) { Text(stringResource(R.string.creation_end_check)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.creation_end_check)) }, text = { Text(stringResource(R.string.creation_end_detail)) },
        confirmButton = { TextButton(onClick = { clear(); confirm = false }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
