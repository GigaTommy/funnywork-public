package com.funnyworker.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.json.JSONObject

@Composable internal fun receiptLabel(status: String): String = stringResource(when (status) {
    "queued" -> R.string.command_queued; "claimed" -> R.string.command_claimed
    "accepted" -> R.string.command_accepted; "rejected" -> R.string.command_rejected
    "expired" -> R.string.command_expired; else -> R.string.needs_check
})

internal fun AppState.canControl() = remoteReady && remoteState?.online == true && !commandBusy && commandRecords.none { it.optBoolean("pending") }

@Composable internal fun RemoteComposer(state: AppState, model: AppViewModel, expand: () -> Unit) {
    if (state.task == null) return
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(if (state.commandRecords.any { it.optBoolean("pending") }) R.string.remote_check_first
            else if (!state.remoteReady && state.error != null) R.string.remote_status_failed
            else if (!state.remoteReady) R.string.remote_connecting else if (state.remoteState?.online != true) R.string.remote_offline else R.string.remote_online), style = MaterialTheme.typography.bodySmall)
        UploadActions(state, model)
        OutlinedTextField(state.draft, model::draft, placeholder = { Text(stringResource(R.string.placeholder)) }, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("conversation-composer"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = model::checkRemote, enabled = !state.commandBusy) { Text(stringResource(if (!state.remoteReady && state.error != null) R.string.retry else R.string.check)) }
            TextButton(onClick = expand) { Text(stringResource(R.string.expand)) }
            Spacer(Modifier.weight(1f))
            if (state.remoteState?.running == true) TextButton(onClick = model::remoteStop, enabled = state.canControl() && state.remoteState.turn != null) { Text(stringResource(R.string.stop)) }
            Button(onClick = { model.remotePrompt() }, enabled = state.canControl() && (state.draft.isNotBlank() || state.uploads.isNotEmpty()) && state.uploads.all { it.optBoolean("complete") } && state.draft.length <= 16000 && !RemoteQueue.withdrawing(state.commandRecords)) {
                Text(stringResource(if (state.remoteState?.running == true) R.string.remote_queue else R.string.send))
            }
        }
        if (state.remoteState?.running == true) TextButton(onClick = { model.remotePrompt(true) }, enabled = state.canControl() && state.remoteState.turn != null && (state.draft.isNotBlank() || state.uploads.isNotEmpty()) && state.uploads.all { it.optBoolean("complete") } && state.draft.length <= 16000 && !RemoteQueue.withdrawing(state.commandRecords)) {
            Text(stringResource(R.string.remote_steer))
        }
        if (state.draft.length > 16000) Text(stringResource(R.string.overlong))
    }
}

@Composable internal fun InteractionCard(item: RemoteInteraction, state: AppState, model: AppViewModel) {
    val blocked = state.commandRecords.any { RemoteCommands.blocksInteraction(it, item.id) }
    val enabled = state.canControl() && !blocked
    val values = state.answers.optJSONObject(item.id) ?: JSONObject()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SemanticStatus(stringResource(if (item.kind == "approval") R.string.remote_approval else R.string.remote_question), StatusKind.WAITING)
            Text(item.title.ifBlank { stringResource(if (item.kind == "approval") R.string.remote_approval else R.string.remote_question) }, style = MaterialTheme.typography.titleMedium)
            if (item.reason.isNotBlank()) Text(item.reason)
            if (item.detail.isNotBlank()) {
                if (item.kind == "approval") {
                    var detailsExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
                    val description = stringResource(if (detailsExpanded) R.string.details_expanded else R.string.details_collapsed)
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth()
                        .testTag("approval-details-${item.correlation}").semantics { stateDescription = description }) {
                        Text(stringResource(R.string.approval_details)); Spacer(Modifier.weight(1f))
                        Text(if (detailsExpanded) "⌄" else "›")
                    }
                    if (detailsExpanded) SelectionContainer { Text(item.detail, style = MaterialTheme.typography.bodySmall) }
                } else SelectionContainer { Text(item.detail, style = MaterialTheme.typography.bodySmall) }
            }
            if (item.kind == "approval") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.remoteDecide(item, false) }, enabled = enabled, modifier = Modifier.testTag("approval-deny-${item.correlation}")) { Text(stringResource(R.string.remote_deny)) }
                Button(onClick = { model.remoteDecide(item, true) }, enabled = enabled, modifier = Modifier.testTag("approval-allow-${item.correlation}")) { Text(stringResource(R.string.remote_allow_once)) }
            } else {
                for (question in item.questions) {
                    val answer = values.optJSONObject(question.id) ?: JSONObject()
                    val selected = answer.optJSONArray("selected")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                    Text(question.title, style = MaterialTheme.typography.titleSmall)
                    if (question.detail.isNotBlank()) Text(question.detail, style = MaterialTheme.typography.bodySmall)
                    for ((label, detail) in question.options) {
                        FilterChip(selected = label in selected, enabled = enabled,
                            onClick = {
                                val next = if (label in selected) selected - label else if (question.multiple) selected + label else listOf(label)
                                model.answerDraft(item, question, next, answer.text("custom"))
                            }, label = { Text(if (detail.isBlank()) label else "$label · $detail") })
                    }
                    OutlinedTextField(answer.text("custom"), { model.answerDraft(item, question, selected, it) }, enabled = enabled,
                        label = { Text(stringResource(R.string.remote_custom_answer)) }, modifier = Modifier.fillMaxWidth(), maxLines = 6)
                }
                Button(onClick = { model.remoteAnswer(item) }, enabled = enabled && item.questions.isNotEmpty() && item.questions.all { RemoteCommands.validAnswer(it, values.optJSONObject(it.id) ?: JSONObject()) }) {
                    Text(stringResource(R.string.remote_submit_answer))
                }
            }
            if (blocked) Text(stringResource(R.string.command_awaiting_receipt), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun CommandReceipts(state: AppState) {
    if (state.commandRecords.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_operations), style = MaterialTheme.typography.titleSmall)
        for (record in state.commandRecords.takeLast(6).reversed()) {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(receiptLabel(record.text("status")))
                val text = record.optJSONObject("payload")?.text("text").orEmpty().ifBlank { record.text("withdrawalText") }
                if (text.isNotBlank()) SelectionContainer { Text(text, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
                Text(record.text("id"), style = MaterialTheme.typography.labelSmall)
            } }
        }
    }
}
