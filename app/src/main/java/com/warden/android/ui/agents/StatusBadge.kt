package com.warden.android.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.warden.android.data.model.ContextState
import com.warden.android.data.model.Status

/** Maps a status string to a display label + color for the badge. */
private data class StatusStyle(val label: String, val color: Color)

private fun styleFor(status: String): StatusStyle = when (status) {
    Status.WAITING_FOR_INPUT -> StatusStyle("waiting", Color(0xFFF59E0B))
    Status.WORKING -> StatusStyle("working", Color(0xFF2E7D5B))
    Status.SPAWNING -> StatusStyle("spawning", Color(0xFF6366F1))
    Status.IDLE -> StatusStyle("idle", Color(0xFF64748B))
    Status.DONE -> StatusStyle("done", Color(0xFF475569))
    Status.ERRORED -> StatusStyle("errored", Color(0xFFDC2626))
    Status.RATE_LIMITED -> StatusStyle("rate-limited", Color(0xFFB45309))
    Status.ORPHANED -> StatusStyle("orphaned", Color(0xFF9333EA))
    else -> StatusStyle(status.ifBlank { "unknown" }, Color(0xFF64748B))
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    val style = styleFor(status)
    Text(
        text = style.label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(style.color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Small colored chip for the context window state, hidden when empty/ok-blank. */
@Composable
fun ContextBadge(contextState: String, modifier: Modifier = Modifier) {
    val color = when (contextState) {
        ContextState.OK -> Color(0xFF2E7D5B)
        ContextState.WARNING -> Color(0xFFF59E0B)
        ContextState.CRITICAL -> Color(0xFFDC2626)
        else -> return
    }
    Text(
        text = "ctx: $contextState",
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
