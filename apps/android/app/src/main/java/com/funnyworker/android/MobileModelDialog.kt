package com.funnyworker.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Full names and thinking choices live in the scrollable dialog, not the composer. */
@Composable internal fun MobileModelDialog(models: List<Row>, model: String, effort: String, busy: Boolean,
    choose: (String) -> Unit, chooseEffort: (String) -> Unit, close: () -> Unit) {
    val current = models.firstOrNull { it.id == model }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.model)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("phone-model-list")) {
            Text(stringResource(R.string.current_model), style = MaterialTheme.typography.labelLarge)
            Text(current?.title ?: stringResource(R.string.no_model), Modifier.fillMaxWidth().testTag("phone-model-current"))
            val levels = mobileThinkingLevels(current)
            if (levels.isNotEmpty()) {
                Text(stringResource(R.string.thinking_level), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
                levels.forEach { level ->
                    TextButton(onClick = { chooseEffort(level) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("phone-model-effort-$level").semantics { selected = effort == level }) {
                        Text((if (effort == level) "✓ " else "") + level.replaceFirstChar { it.uppercase() })
                    }
                }
            }
            val other = stringResource(R.string.other_models)
            models.groupBy { it.json.text("provider_name").ifBlank { other } }.forEach { (provider, rows) ->
                Text(provider, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
                rows.forEach { row ->
                    TextButton(onClick = { choose(row.id) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("phone-model-option-${row.id}").semantics { selected = model == row.id }) {
                        Text((if (model == row.id) "✓ " else "") + row.title, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.done)) } })
}
