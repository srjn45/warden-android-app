package com.warden.android.ui.terminals

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.Connection
import com.warden.android.data.model.Session
import com.warden.android.ui.HostPickerTitle
import com.warden.android.ui.agents.StreamStatus

/**
 * The TERMINALS destination — a top-level section alongside Agents and Pipelines,
 * shown only when the active daemon models terminals as first-class sessions.
 * Lists the terminal panes (from the shared fleet stream), opens one into the PTY
 * viewport on tap, and offers "New terminal" via the FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(
    viewModel: TerminalsViewModel,
    onTerminalClick: (Session) -> Unit,
    onNewTerminal: () -> Unit,
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

    var pendingDelete by remember { mutableStateOf<Session?>(null) }

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
                        title = "Terminals",
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
            FloatingActionButton(onClick = onNewTerminal) {
                Icon(Icons.Filled.Add, contentDescription = "New terminal")
            }
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
                state.terminals.isEmpty() && state.stream == StreamStatus.Disconnected ->
                    CenteredMessage(
                        title = "Disconnected",
                        subtitle = "Lost the live stream.",
                        actionLabel = "Reconnect",
                        onAction = viewModel::reconnect,
                    )

                state.terminals.isEmpty() && state.stream == StreamStatus.Connecting ->
                    CenteredMessage(title = "Connecting…", subtitle = "Subscribing to the live stream.")

                state.terminals.isEmpty() ->
                    CenteredMessage(
                        title = "No terminals",
                        subtitle = "Tap + to open a shell on this host.",
                    )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.terminals, key = { it.id }) { terminal ->
                        TerminalRow(
                            terminal = terminal,
                            onClick = { onTerminalClick(terminal) },
                            onDelete = { pendingDelete = terminal },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        DeleteTerminalDialog(
            terminal = target,
            onConfirm = {
                viewModel.deleteTerminal(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun TerminalRow(
    terminal: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = terminal.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RowOverflowMenu(onDelete = onDelete)
        }

        val subtitle = terminal.workdir.ifBlank { terminal.subject }
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowOverflowMenu(onDelete: () -> Unit) {
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
                text = { Text("Close terminal", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DeleteTerminalDialog(
    terminal: Session,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ${terminal.displayName}?") },
        text = { Text("This terminates the shell and removes its record. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Close", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
