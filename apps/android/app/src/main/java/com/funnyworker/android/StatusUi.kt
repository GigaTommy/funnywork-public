package com.funnyworker.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal data class StatusColors(val success: Color, val warning: Color, val failure: Color)
internal fun statusColors(dark: Boolean) = if (dark) StatusColors(Color(0xFF70CE91), Color(0xFFF4BE62), Color(0xFFFF978D))
    else StatusColors(Color(0xFF237A43), Color(0xFF985C0A), Color(0xFFBD3A32))
internal val LocalStatusColors = staticCompositionLocalOf { statusColors(false) }
internal enum class StatusKind { ONLINE, OFFLINE, CHECKING, UNKNOWN, UNLINKED, REVOKED, FAILED, WAITING, RUNNING, COMPLETED, STOPPED }

/** A glyph and readable text convey the state together; color never carries the meaning alone. */
@Composable internal fun SemanticStatus(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val palette = LocalStatusColors.current
    val color = when (kind) {
        StatusKind.ONLINE, StatusKind.COMPLETED -> palette.success
        StatusKind.CHECKING, StatusKind.WAITING -> palette.warning
        StatusKind.FAILED -> palette.failure
        StatusKind.RUNNING -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind == StatusKind.CHECKING || kind == StatusKind.RUNNING) {
            CircularProgressIndicator(Modifier.size(12.dp).clearAndSetSemantics {}, color = color, strokeWidth = 1.5.dp)
        } else Canvas(Modifier.size(12.dp).testTag("status-" + kind.name.lowercase()).clearAndSetSemantics {}) {
            val c = center; val r = size.minDimension * .38f; val stroke = 1.3.dp.toPx()
            if (kind == StatusKind.ONLINE) drawCircle(color, 4.dp.toPx())
            else if (kind == StatusKind.COMPLETED) {
                drawLine(color, Offset(size.width * .15f, c.y), Offset(size.width * .42f, size.height * .75f), stroke)
                drawLine(color, Offset(size.width * .42f, size.height * .75f), Offset(size.width * .9f, size.height * .2f), stroke)
            } else {
                drawCircle(color, r, style = Stroke(stroke))
                when (kind) {
                    StatusKind.FAILED -> { drawLine(color, Offset(c.x, c.y-r*.65f), Offset(c.x, c.y+r*.1f), stroke); drawCircle(color, stroke*.55f, Offset(c.x,c.y+r*.55f)) }
                    StatusKind.WAITING -> { drawLine(color, c, Offset(c.x,c.y-r*.65f), stroke); drawLine(color,c,Offset(c.x+r*.6f,c.y),stroke) }
                    StatusKind.REVOKED, StatusKind.UNLINKED -> drawLine(color,Offset(c.x-r*.7f,c.y+r*.7f),Offset(c.x+r*.7f,c.y-r*.7f),stroke)
                    StatusKind.UNKNOWN -> { drawArc(color,190f,250f,false,topLeft=Offset(c.x-r*.45f,c.y-r*.65f),size=androidx.compose.ui.geometry.Size(r*.9f,r*.85f),style=Stroke(stroke)); drawCircle(color,stroke*.5f,Offset(c.x,c.y+r*.55f)) }
                    else -> Unit
                }
            }
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun SearchGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(22.dp).clearAndSetSemantics {}) {
        drawCircle(color, size.width*.29f, Offset(size.width*.4f,size.height*.4f), style = Stroke(2.dp.toPx()))
        drawLine(color,Offset(size.width*.62f,size.height*.62f),Offset(size.width*.91f,size.height*.91f),2.dp.toPx())
    }
}

@Composable internal fun SidebarHeader(searching: Boolean, search: () -> Unit, cancel: () -> Unit) {
    val density = LocalDensity.current
    // Navigation chrome stays legible on one line; body and search text retain the user's scale.
    CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FunnyWork", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (searching) TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp).testTag("sidebar-search-cancel")) {
                Text(stringResource(R.string.cancel), maxLines = 1)
            } else {
                val label = stringResource(R.string.search)
                IconButton(onClick = search, modifier = Modifier.testTag("sidebar-search-toggle").semantics { contentDescription = label }) { SearchGlyph() }
            }
        }
    }
}
