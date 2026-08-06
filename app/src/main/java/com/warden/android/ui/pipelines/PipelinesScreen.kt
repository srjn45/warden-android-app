package com.warden.android.ui.pipelines

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.Connection
import com.warden.android.data.model.Pipeline
import com.warden.android.ui.HostPickerTitle

/**
 * The Pipelines tab: a pull-to-refresh list of the active host's pipelines. Each
 * row shows the pipeline's status and job progress; tapping opens its detail DAG.
 *
 * The top bar mirrors the Agents tab — a hamburger that opens the shared host drawer
 * and a title that doubles as a quick host picker — so the two tabs read the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelinesScreen(
    viewModel: PipelineListViewModel,
    onPipelineClick: (Pipeline) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    hostLabel: String = "",
    hosts: List<Connection> = emptyList(),
    activeLabel: String? = null,
    onSwitchHost: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                        title = "Pipelines",
                        hostLabel = hostLabel,
                        hosts = hosts,
                        activeLabel = activeLabel,
                        onSwitchHost = onSwitchHost,
                    )
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> CenteredMessage("Loading…", "Fetching pipelines.")

                state.error != null -> CenteredMessage(
                    title = "Couldn't load pipelines",
                    subtitle = state.error ?: "",
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )

                state.unsupported -> CenteredMessage(
                    title = "No pipelines",
                    subtitle = "This daemon doesn't expose the pipelines API.",
                )

                state.pipelines.isEmpty() -> CenteredMessage(
                    title = "No pipelines",
                    subtitle = "This host has no pipelines. Pull to refresh.",
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.pipelines, key = { it.id }) { pipeline ->
                        PipelineRow(pipeline, onClick = { onPipelineClick(pipeline) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineRow(pipeline: Pipeline, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pipeline.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PipelineStatusBadge(pipeline.status)
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${pipeline.doneCount}/${pipeline.jobs.size} jobs",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pipeline.repo.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = pipeline.repo.substringAfterLast('/').ifBlank { pipeline.repo },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CenteredMessage(
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
