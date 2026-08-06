package com.warden.android.ui.pipelines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.PipelineJob
import com.warden.android.data.model.PipelineStatus

/**
 * One pipeline's DAG plus its lifecycle controls. The available actions follow the
 * daemon's state machine: pending → Start; running → Pause / Cancel; paused →
 * Resume / Cancel; a settled pipeline → Delete. Cancel and Delete are confirmed
 * first — Delete additionally 409s server-side while any job is still live.
 *
 * A job row with a live agent session is tappable and opens that agent's terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineDetailScreen(
    viewModel: PipelineDetailViewModel,
    title: String,
    onBack: () -> Unit,
    onJobClick: (sessionId: String, label: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // A successful delete pops the screen.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            val pipeline = state.pipeline
            when {
                state.loading -> CenteredMessage("Loading…", "Fetching the pipeline.")

                pipeline == null -> CenteredMessage(
                    title = "Couldn't load pipeline",
                    subtitle = state.error ?: "",
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "header") {
                        PipelineHeader(
                            pipeline = pipeline,
                            acting = state.acting,
                            onStart = viewModel::start,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onCancel = { confirm = ConfirmAction.Cancel },
                            onDelete = { confirm = ConfirmAction.Delete },
                        )
                        HorizontalDivider()
                    }
                    if (pipeline.jobs.isEmpty()) {
                        item(key = "no-jobs") {
                            Text(
                                "This pipeline has no jobs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(pipeline.jobs, key = { it.id }) { job ->
                            JobRow(
                                job = job,
                                onClick = if (job.sessionId.isNotBlank()) {
                                    { onJobClick(job.sessionId, "${pipeline.displayName} · ${job.id}") }
                                } else null,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    confirm?.let { action ->
        ActionConfirmDialog(
            action = action,
            pipelineName = state.pipeline?.displayName ?: title,
            onConfirm = {
                when (action) {
                    ConfirmAction.Cancel -> viewModel.cancel()
                    ConfirmAction.Delete -> viewModel.delete()
                }
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}

private enum class ConfirmAction { Cancel, Delete }

@Composable
private fun PipelineHeader(
    pipeline: Pipeline,
    acting: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipelineStatusBadge(pipeline.status)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${pipeline.doneCount}/${pipeline.jobs.size} jobs done",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (acting) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
            }
        }
        if (pipeline.repo.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = pipeline.repo,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        // Actions follow the daemon's state machine; buttons are disabled while an
        // action is in flight to avoid double-submits.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            when (pipeline.status) {
                PipelineStatus.PENDING -> {
                    Button(onClick = onStart, enabled = !acting) { Text("Start") }
                    DeleteButton(onDelete, acting)
                }
                PipelineStatus.RUNNING -> {
                    Button(onClick = onPause, enabled = !acting) { Text("Pause") }
                    CancelButton(onCancel, acting)
                }
                PipelineStatus.PAUSED -> {
                    Button(onClick = onResume, enabled = !acting) { Text("Resume") }
                    CancelButton(onCancel, acting)
                }
                // done / failed / canceled — settled, so only delete remains.
                else -> DeleteButton(onDelete, acting)
            }
        }
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit, acting: Boolean) {
    OutlinedButton(onClick = onClick, enabled = !acting) {
        Text("Terminate", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit, acting: Boolean) {
    OutlinedButton(onClick = onClick, enabled = !acting) {
        Text("Delete", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun JobRow(job: PipelineJob, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = job.id,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            JobStatusBadge(job.status)
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Open agent terminal",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val meta = buildList {
            if (job.type.isNotBlank()) add(job.type)
            if (job.dependsOn.isNotEmpty()) add("after ${job.dependsOn.joinToString(", ")}")
            if (job.worktree.isNotBlank()) add(job.worktree)
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionConfirmDialog(
    action: ConfirmAction,
    pipelineName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, confirmLabel) = when (action) {
        ConfirmAction.Cancel -> Triple(
            "Terminate $pipelineName?",
            "This stops any running jobs and skips the rest. The pipeline record is kept.",
            "Terminate",
        )
        ConfirmAction.Delete -> Triple(
            "Delete $pipelineName?",
            "This removes the pipeline and reaps its agents. This can't be undone.",
            "Delete",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
