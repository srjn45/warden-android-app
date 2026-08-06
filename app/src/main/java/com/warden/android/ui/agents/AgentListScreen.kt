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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.warden.android.data.displayHost
import com.warden.android.data.model.Backend
import com.warden.android.data.model.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    viewModel: AgentListViewModel,
    onAgentClick: (Session) -> Unit = {},
    onCreateClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    hosts: List<Connection> = emptyList(),
    activeLabel: String? = null,
    onSwitchHost: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface delete results / failures, then acknowledge so they don't repeat.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // The agent pending delete confirmation (null = dialog closed).
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    // The agent whose details sheet is open (null = closed).
    var pendingInfo by remember { mutableStateOf<Session?>(null) }

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
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateClick) {
                Icon(Icons.Filled.Add, contentDescription = "New agent")
            }
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
                        onInfoClick = { pendingInfo = it },
                        onDeleteClick = { pendingDelete = it },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        DeleteAgentDialog(
            agent = target,
            onConfirm = { removeWorktree ->
                viewModel.deleteAgent(target, removeWorktree)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingInfo?.let { target ->
        AgentInfoDialog(agent = target, onDismiss = { pendingInfo = null })
    }
}

@Composable
private fun DeleteAgentDialog(
    agent: Session,
    onConfirm: (removeWorktree: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var removeWorktree by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${agent.displayName}?") },
        text = {
            Column {
                Text("This terminates the agent and removes its record. This can't be undone.")
                if (agent.worktree.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { removeWorktree = !removeWorktree },
                    ) {
                        Checkbox(checked = removeWorktree, onCheckedChange = { removeWorktree = it })
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Also remove the git worktree & branch",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(removeWorktree) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Read-only details for one agent, opened from the row's overflow menu. Shows the
 * session fields the daemon reports (backend, model, role, repo/branch/worktree,
 * timestamps, …), skipping any that are blank. Values are selectable so ids and
 * paths can be copied.
 */
@Composable
private fun AgentInfoDialog(agent: Session, onDismiss: () -> Unit) {
    val rows = buildList {
        add("Name" to agent.displayName)
        add("ID" to agent.id)
        add("Backend" to Backend.labelFor(agent.backend))
        add("Status" to agent.status)
        add("Model" to agent.model)
        add("Role" to agent.role)
        add("Type" to agent.type)
        add("Repo" to agent.repo)
        add("Branch" to agent.branch)
        add("Worktree" to agent.worktree)
        add("Workdir" to agent.workdir)
        add("PR" to agent.pr)
        add("Tags" to agent.tags.joinToString(", "))
        add("Pipeline" to agent.pipelineId)
        add(
            "Context" to when {
                agent.contextState.isBlank() && agent.contextTokens == 0 -> ""
                agent.contextTokens > 0 ->
                    "${agent.contextState.ifBlank { "—" }} · ${agent.contextTokens} tok"
                else -> agent.contextState
            },
        )
        add("PID" to agent.pid.takeIf { it > 0 }?.toString().orEmpty())
        add("Exit code" to agent.exitCode?.toString().orEmpty())
        add("Created" to agent.createdAt)
        add("Updated" to agent.updatedAt)
    }.filter { it.second.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(agent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { (label, value) -> InfoRow(label, value) }
                    if (agent.subject.isNotBlank()) InfoRow("Subject", agent.subject)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
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
    onInfoClick: (Session) -> Unit,
    onDeleteClick: (Session) -> Unit,
) {
    val groups = remember(agents, groupMode) { groupSessions(agents, groupMode) }
    // Collapsed group keys; reset whenever the grouping dimension changes.
    val collapsed = remember(groupMode) { mutableStateMapOf<String, Boolean>() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (groupMode == GroupMode.None) {
            items(agents, key = { it.id }) { agent ->
                AgentRow(
                    agent,
                    onClick = { onAgentClick(agent) },
                    onInfo = { onInfoClick(agent) },
                    onDelete = { onDeleteClick(agent) },
                )
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
                        AgentRow(
                            agent,
                            onClick = { onAgentClick(agent) },
                            onInfo = { onInfoClick(agent) },
                            onDelete = { onDeleteClick(agent) },
                        )
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
private fun AgentRow(agent: Session, onClick: () -> Unit, onInfo: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
            RowOverflowMenu(onInfo = onInfo, onDelete = onDelete)
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
            Spacer(Modifier.width(10.dp))
            BackendBadge(agent.backend)
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
private fun RowOverflowMenu(onInfo: () -> Unit, onDelete: () -> Unit) {
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
                text = { Text("Agent info") },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    onInfo()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * The title doubles as a host picker: it shows "Agents" over the active host, and
 * — when more than one host is saved — a dropdown to switch between them. The
 * hamburger/drawer is the full manager (add/disconnect); this is the quick swap.
 */
@Composable
private fun HostPickerTitle(
    hostLabel: String,
    hosts: List<Connection>,
    activeLabel: String?,
    onSwitchHost: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val multi = hosts.size > 1
    val shownHost = remember(hostLabel) { hostLabel.substringAfter("://").trimEnd('/') }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = multi) { expanded = true },
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text("Agents")
                Text(
                    text = shownHost,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (multi) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Switch host",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host.displayHost(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (host.label == activeLabel) {
                            Icon(Icons.Filled.Check, contentDescription = "Active host")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSwitchHost(host.label)
                    },
                )
            }
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
