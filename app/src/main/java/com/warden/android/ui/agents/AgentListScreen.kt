package com.warden.android.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.model.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    viewModel: AgentListViewModel,
    onAgentClick: (Session) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agents")
                        Text(
                            text = state.hostLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { StreamIndicator(state.stream) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Grouping control is only meaningful once agents exist.
            if (state.agents.isNotEmpty()) {
                GroupByBar(selected = state.groupMode, onSelect = viewModel::setGroupMode)
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.agents.isEmpty() && state.stream == StreamStatus.Disconnected ->
                        CenteredMessage(
                            title = "Disconnected",
                            subtitle = state.error ?: "Lost the live stream.",
                            actionLabel = "Reconnect",
                            onAction = viewModel::reconnect,
                        )

                    state.agents.isEmpty() && state.stream == StreamStatus.Connecting ->
                        CenteredMessage(title = "Connecting…", subtitle = "Subscribing to the live stream.")

                    state.agents.isEmpty() ->
                        CenteredMessage(
                            title = "No agents",
                            subtitle = "This fleet has no live agents. Pull to refresh.",
                        )

                    else -> AgentList(
                        agents = state.agents,
                        groupMode = state.groupMode,
                        onAgentClick = onAgentClick,
                    )
                }
            }
        }
    }
}

/**
 * Renders the agents flat (GroupMode.None) or under collapsible group headers.
 * Order is the daemon's own — nothing here re-sorts. In tag mode an agent can
 * appear under several groups, so item keys are namespaced by the group key.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentList(
    agents: List<Session>,
    groupMode: GroupMode,
    onAgentClick: (Session) -> Unit,
) {
    val groups = remember(agents, groupMode) { groupSessions(agents, groupMode) }
    // Collapsed group keys; reset whenever the grouping dimension changes.
    val collapsed = remember(groupMode) { mutableStateMapOf<String, Boolean>() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (groupMode == GroupMode.None) {
            items(agents, key = { it.id }) { agent ->
                AgentRow(agent, onClick = { onAgentClick(agent) })
                HorizontalDivider()
            }
        } else {
            groups.forEach { group ->
                val isCollapsed = collapsed[group.key] == true
                stickyHeader(key = "header:${group.key}") {
                    GroupHeader(
                        label = group.label,
                        count = group.sessions.size,
                        collapsed = isCollapsed,
                        onToggle = { collapsed[group.key] = !isCollapsed },
                    )
                }
                if (!isCollapsed) {
                    items(group.sessions, key = { "${group.key}:${it.id}" }) { agent ->
                        AgentRow(agent, onClick = { onAgentClick(agent) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupByBar(selected: GroupMode, onSelect: (GroupMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Group by",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GroupMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentRow(agent: Session, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = agent.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusBadge(agent.status)
        }

        if (agent.subject.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = agent.subject,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = agent.id,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (agent.model.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = agent.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            ContextBadge(agent.contextState)
        }
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
