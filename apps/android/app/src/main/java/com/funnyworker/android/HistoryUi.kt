package com.funnyworker.android

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp

@Composable internal fun SearchText(text: String, query: String, modifier: Modifier = Modifier) {
    val mark = MaterialTheme.colorScheme.outlineVariant
    val annotated = remember(text, query, mark) { buildAnnotatedString {
        append(text)
        if (query.isNotBlank()) {
            var from = 0
            while (from < text.length) {
                val found = text.indexOf(query, from, ignoreCase = true); if (found < 0) break
                addStyle(SpanStyle(background = mark), found, found + query.length); from = found + query.length
            }
        }
    } }
    Text(annotated, modifier)
}
@Composable internal fun PhoneHistoryRow(row: Row, state: AppState, open: () -> Unit, rename: () -> Unit, archive: () -> Unit) {
    var menu by remember(row.id) { mutableStateOf(false) }
    val manageable = state.historySupported && row.json.sequence("history_revision") != null
    val enabled = manageable && state.historyFresh && !state.loading && !state.historyBusy && state.historyPending == null
    Box {
        Column(Modifier.fillMaxWidth().testTag("conversation-${row.id}").semantics { selected = state.chat == row.id }
            .combinedClickable(onClick = open, onLongClick = if (manageable) ({ menu = true }) else null)
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            SearchText(row.title, state.query)
            if (state.query.isNotBlank()) row.json.optJSONObject("search_match")?.let { match ->
                SearchText(match.text("excerpt"), state.query.trim())
                Text(stringResource(when (match.text("field")) { "user" -> R.string.history_user_hit; "reply" -> R.string.history_reply_hit; else -> R.string.history_title_hit }), style = MaterialTheme.typography.bodySmall)
            }
            state.phonePhases[row.id]?.let { Text(stringResource(if (it == TurnPhase.NEEDS_CHECK.name) R.string.needs_check else R.string.history_running), style = MaterialTheme.typography.labelSmall) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(modifier = Modifier.testTag("history-rename-menu-${row.id}"), text = { Text(stringResource(R.string.history_rename)) }, enabled = enabled, onClick = { menu = false; rename() })
            DropdownMenuItem(modifier = Modifier.testTag("history-archive-menu-${row.id}"), text = { Text(stringResource(if (row.json.optBoolean("archived")) R.string.history_restore else R.string.history_archive)) }, enabled = enabled, onClick = { menu = false; archive() })
        }
    }
}
@Composable internal fun AllowanceSection(state: AppState, refresh: () -> Unit, claim: () -> Unit) {
    Text(stringResource(R.string.allowance_title), style = MaterialTheme.typography.titleMedium)
    state.allowance?.let { value ->
        Text(stringResource(R.string.allowance_remaining, ModelAllowance.money(value.remaining)), Modifier.testTag("allowance-remaining"))
        Text(stringResource(R.string.allowance_held, ModelAllowance.money(value.held)))
        Text(stringResource(R.string.allowance_daily, ModelAllowance.money(value.dailyRemaining)))
        value.expires?.let { Text(stringResource(R.string.allowance_expires, java.text.DateFormat.getDateInstance().format(java.util.Date(it)))) }
        if (!value.active) Text(stringResource(R.string.allowance_inactive))
        else if (value.remaining == 0L) Text(stringResource(R.string.allowance_empty))
        if (value.credited > 0) Text(stringResource(R.string.allowance_credited, ModelAllowance.money(value.credited)))
        if (value.claimable > 0) TextButton(onClick = claim, enabled = !state.allowanceBusy, modifier = Modifier.testTag("allowance-claim")) { Text(stringResource(R.string.allowance_claim)) }
    }
    if (state.allowance == null && !state.allowanceBusy) Text(stringResource(R.string.allowance_unknown), Modifier.testTag("allowance-unknown"))
    if (state.allowanceBusy) Text(stringResource(R.string.allowance_loading))
    state.allowanceError?.let { Text(stringResource(it)) }
    TextButton(onClick = refresh, enabled = !state.allowanceBusy, modifier = Modifier.testTag("allowance-refresh")) { Text(stringResource(R.string.allowance_refresh)) }
    Text(stringResource(R.string.allowance_help), style = MaterialTheme.typography.bodySmall)
}
@Composable internal fun PhoneUsageDetails(value: PhoneUsage) {
    if (value.calls > 0) {
        Text(stringResource(R.string.usage_calls, value.calls))
        Text(stringResource(R.string.usage_input, value.input))
        Text(stringResource(R.string.usage_output, value.output))
        Text(stringResource(R.string.usage_cached, value.cached?.toString() ?: "—"))
    } else Text(stringResource(R.string.usage_unknown))
    if (value.unconfirmed > 0) Text(stringResource(R.string.usage_unconfirmed, value.unconfirmed))
    Text(stringResource(R.string.usage_help), style = MaterialTheme.typography.bodySmall)
}
