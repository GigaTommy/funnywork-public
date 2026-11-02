package com.funnyworker.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date

@Composable internal fun ConversationSources(state: AppState, select: (Boolean) -> Unit) {
    @Composable fun chat(modifier: Modifier) {
        FilterChip(!state.desktop, { select(false) }, { Text(stringResource(R.string.chat_source)) }, modifier = modifier.heightIn(min = 48.dp).testTag("source-chat"))
    }
    @Composable fun remote(modifier: Modifier) {
        FilterChip(state.desktop, { select(true) }, { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.remote)); ConnectionLabel(state.deviceConnection())
        } }, modifier = modifier.heightIn(min = 48.dp).testTag("source-remote"))
    }
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth()) {
        chat(Modifier.fillMaxWidth()); remote(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chat(Modifier.weight(1f)); remote(Modifier.weight(1f))
    }
}

@Composable internal fun ConnectionLabel(connection: DeviceConnection) {
    val label = stringResource(when (connection) {
        DeviceConnection.FAILED -> R.string.device_connection_failed
        DeviceConnection.CHECKING -> R.string.device_checking
        DeviceConnection.ONLINE -> R.string.online
        DeviceConnection.OFFLINE -> R.string.offline
        DeviceConnection.UNLINKED -> R.string.device_unlinked
        DeviceConnection.UNKNOWN -> R.string.device_unknown
        DeviceConnection.REVOKED -> R.string.device_revoked
    })
    SemanticStatus(label, when(connection) {
        DeviceConnection.ONLINE -> StatusKind.ONLINE
        DeviceConnection.OFFLINE -> StatusKind.OFFLINE
        DeviceConnection.CHECKING -> StatusKind.CHECKING
        DeviceConnection.FAILED -> StatusKind.FAILED
        DeviceConnection.UNKNOWN -> StatusKind.UNKNOWN
        DeviceConnection.UNLINKED -> StatusKind.UNLINKED
        DeviceConnection.REVOKED -> StatusKind.REVOKED
    })
}

@Composable internal fun DeviceSummary(device: DeviceInfo, fresh: Boolean, checking: Boolean = false, failed: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(device.name, style = MaterialTheme.typography.titleSmall)
        ConnectionLabel(if (device.revoked) DeviceConnection.REVOKED else if (!fresh) { if (failed) DeviceConnection.FAILED else if (checking) DeviceConnection.CHECKING else DeviceConnection.UNKNOWN }
            else if (device.online) DeviceConnection.ONLINE else DeviceConnection.OFFLINE)
        if (device.platform.isNotBlank()) Text(device.platform, style = MaterialTheme.typography.bodySmall)
        device.lastSeen?.let { Text(stringResource(R.string.device_last_seen,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable internal fun DeviceDirectory(state: AppState, refresh: () -> Unit, create: (String) -> Unit, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.devices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = refresh, enabled = !state.devicesLoading) { Text(stringResource(if (state.devicesError) R.string.retry else R.string.refresh)) }
                    TextButton(onClick = close, modifier = Modifier.testTag("device-directory-close")) { Text(stringResource(R.string.done)) }
                }
                if (state.devices.isEmpty()) ConnectionLabel(state.deviceConnection())
                if (state.devicesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.devicesError) Text(stringResource(R.string.device_refresh_failed))
                    if (state.devices.isEmpty() && state.devicesFresh) Text(stringResource(R.string.device_empty))
                    state.devices.forEach { row ->
                        val device = DeviceInfo.parse(row.json)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                DeviceSummary(device, state.devicesFresh, state.devicesLoading, state.devicesError)
                                device.workspaces.forEach { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
                                if (state.devicesFresh && device.online && device.workspaces.isEmpty()) Text(stringResource(R.string.device_no_workspace))
                                Button(onClick = { create(device.id) }, enabled = state.devicesFresh && device.canCreate,
                                    modifier = Modifier.testTag("device-create-${device.id}")) { Text(stringResource(R.string.new_chat)) }
                            }
                        }
                    }
                    Text(stringResource(R.string.device_setup_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable internal fun RemoteTaskIdentity(state: AppState) {
    val task = state.tasks.firstOrNull { it.id == state.task } ?: return
    val id = task.json.text("device_id")
    val device = state.devices.firstOrNull { it.id == id }?.let { DeviceInfo.parse(it.json) }
    val name = device?.name ?: remoteHostName(task.json.text("device_name"), id)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("remote-task-identity"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.remote), style = MaterialTheme.typography.labelMedium)
        ConnectionLabel(state.taskConnection())
        Text(listOfNotNull(name.takeIf { it.isNotBlank() }, device?.platform?.takeIf { it.isNotBlank() }).joinToString(" · "),
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
