package com.warden.android.ui.pipelines

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
import com.warden.android.data.model.JobStatus
import com.warden.android.data.model.PipelineStatus

private data class BadgeStyle(val label: String, val color: Color)

private fun pipelineStyle(status: String): BadgeStyle = when (status) {
    PipelineStatus.RUNNING -> BadgeStyle("running", Color(0xFF2E7D5B))
    PipelineStatus.PENDING -> BadgeStyle("pending", Color(0xFF6366F1))
    PipelineStatus.PAUSED -> BadgeStyle("paused", Color(0xFFF59E0B))
    PipelineStatus.DONE -> BadgeStyle("done", Color(0xFF475569))
    PipelineStatus.FAILED -> BadgeStyle("failed", Color(0xFFDC2626))
    PipelineStatus.CANCELED -> BadgeStyle("canceled", Color(0xFF9333EA))
    else -> BadgeStyle(status.ifBlank { "unknown" }, Color(0xFF64748B))
}

private fun jobStyle(status: String): BadgeStyle = when (status) {
    JobStatus.RUNNING -> BadgeStyle("running", Color(0xFF2E7D5B))
    JobStatus.PENDING -> BadgeStyle("pending", Color(0xFF64748B))
    JobStatus.DONE -> BadgeStyle("done", Color(0xFF475569))
    JobStatus.FAILED -> BadgeStyle("failed", Color(0xFFDC2626))
    JobStatus.SKIPPED -> BadgeStyle("skipped", Color(0xFF94A3B8))
    JobStatus.NEEDS_ATTENTION -> BadgeStyle("needs input", Color(0xFFF59E0B))
    else -> BadgeStyle(status.ifBlank { "unknown" }, Color(0xFF64748B))
}

@Composable
fun PipelineStatusBadge(status: String, modifier: Modifier = Modifier) =
    Badge(pipelineStyle(status), modifier)

@Composable
fun JobStatusBadge(status: String, modifier: Modifier = Modifier) =
    Badge(jobStyle(status), modifier)

@Composable
private fun Badge(style: BadgeStyle, modifier: Modifier) {
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
