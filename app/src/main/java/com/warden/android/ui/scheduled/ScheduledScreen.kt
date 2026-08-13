package com.warden.android.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.Connection
import com.warden.android.data.model.Schedule
import com.warden.android.data.model.Session
import com.warden.android.data.model.Status
import com.warden.android.ui.HostPickerTitle
import com.warden.android.ui.agents.StreamStatus
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The SCHEDULED destination — a top-level section alongside Agents, Pipelines, and
 * Terminals, shown only when the active daemon fires agents on a schedule. Lists
 * each schedule's cadence and task, and — when a schedule's run is live (a fleet
 * session carrying its `schedule_id`) — opens that run's PTY on tap. Schedules can
 * be enabled/disabled from the row overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    viewModel: ScheduledViewModel,
    onRunClick: (Session) -> Unit,
    onOpenDrawer: () -> Unit,
    hosts: List<Connection> = emptyList(),
    activeLabel: String? = null,
    onSwitchHost: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Hosts menu")
                    }
                },
                title = {
                    HostPickerTitle(
                        title = "Scheduled",
                        hostLabel = state.hostLabel,
                        hosts = hosts,
                        activeLabel = activeLabel,
                        onSwitchHost = onSwitchHost,
                    )
                },
                actions = { StreamIndicator(state.stream) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.disabled ->
                    CenteredMessage(
                        title = "Scheduling disabled",
                        subtitle = "This host has the scheduler turned off. Enable it in the daemon config to run agents on a schedule.",
                    )

                state.error != null && state.schedules.isEmpty() ->
                    CenteredMessage(
                        title = "Couldn't load schedules",
                        subtitle = state.error ?: "",
                        actionLabel = "Retry",
                        onAction = viewModel::refresh,
                    )

                state.loading && state.schedules.isEmpty() ->
                    CenteredMessage(title = "Loading…", subtitle = "Fetching schedules on this host.")

                state.schedules.isEmpty() ->
                    CenteredMessage(
                        title = "No scheduled agents",
                        subtitle = "Schedules created on this host — cron or one-shot — appear here.",
                    )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.schedules, key = { it.id }) { schedule ->
                        ScheduleRow(
                            schedule = schedule,
                            liveRun = state.liveRuns[schedule.id],
                            onOpenRun = { onRunClick(it) },
                            onToggleEnabled = { viewModel.toggleEnabled(schedule) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    schedule: Schedule,
    liveRun: Session?,
    onOpenRun: (Session) -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val clickable = liveRun != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpenRun(liveRun!!) } else Modifier)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
    ) {
        // Title + running/paused state + overflow.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = schedule.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (liveRun != null) {
                Chip(text = "running", color = RunningGreen, filled = true)
                Spacer(Modifier.width(6.dp))
            } else if (!schedule.enabled) {
                Chip(text = "paused", color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
            }
            RowOverflowMenu(enabled = schedule.enabled, onToggleEnabled = onToggleEnabled)
        }

        // Cadence: cron expression, or one-shot time.
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (schedule.isRecurring) Icons.Filled.Repeat else Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = cadenceText(schedule),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (schedule.isRecurring) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // What it runs.
        val task = schedule.taskLabel
        if (task.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = task,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Meta: next run, last-run outcome, and an "open terminal" hint when live.
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (schedule.enabled && schedule.nextRun.isNotBlank()) {
                Text(
                    text = "next ${fmtTime(schedule.nextRun)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            if (schedule.lastRunStatus.isNotBlank()) {
                Chip(text = "last: ${schedule.lastRunStatus}", color = statusColor(schedule.lastRunStatus))
            }
            Spacer(Modifier.weight(1f))
            if (liveRun != null) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "Open terminal",
                    tint = RunningGreen,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "open",
                    style = MaterialTheme.typography.labelMedium,
                    color = RunningGreen,
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun RowOverflowMenu(enabled: Boolean, onToggleEnabled: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (enabled) "Disable schedule" else "Enable schedule") },
                onClick = {
                    expanded = false
                    onToggleEnabled()
                },
            )
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, filled: Boolean = false) {
    Surface(
        color = if (filled) color else color.copy(alpha = 0.14f),
        contentColor = if (filled) Color.White else color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StreamIndicator(status: StreamStatus) {
    val (color, label) = when (status) {
        StreamStatus.Live -> Color(0xFF2E7D5B) to "live"
        StreamStatus.Connecting -> Color(0xFFF59E0B) to "connecting"
        StreamStatus.Disconnected -> Color(0xFFDC2626) to "offline"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Circle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(10.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private val RunningGreen = Color(0xFF2E7D5B)

private fun statusColor(status: String): Color = when (status) {
    Status.WORKING, Status.SPAWNING, Status.DONE -> RunningGreen
    Status.ERRORED -> Color(0xFFDC2626)
    Status.WAITING_FOR_INPUT, Status.RATE_LIMITED -> Color(0xFFF59E0B)
    else -> Color(0xFF6B7280)
}

/** "cron 0 2 * * *" for recurring; "once · Aug 15, 09:00" for a one-shot. */
private fun cadenceText(schedule: Schedule): String = when {
    schedule.isRecurring -> schedule.cadenceLabel
    schedule.at.isNotBlank() -> "once · ${fmtTime(schedule.at)}"
    else -> schedule.cadenceLabel
}

private val TimeOut: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** Formats an RFC3339 timestamp to a short local label; returns the raw string if unparseable. */
private fun fmtTime(raw: String): String =
    if (raw.isBlank()) "" else runCatching { OffsetDateTime.parse(raw).format(TimeOut) }.getOrDefault(raw)
