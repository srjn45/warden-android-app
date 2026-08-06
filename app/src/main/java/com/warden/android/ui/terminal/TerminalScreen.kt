package com.warden.android.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.warden.android.data.WardenRepository
import com.warden.android.data.terminal.TerminalState
import com.warden.android.terminal.RemoteTerminalView
import com.warden.android.terminal.TerminalController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    repository: WardenRepository,
    sessionId: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(sessionId) { mutableStateOf<TerminalState>(TerminalState.Connecting) }
    var confirmDelete by remember(sessionId) { mutableStateOf(false) }

    val controller = remember(sessionId) { TerminalController(repository, sessionId) }
    val terminalView = remember(sessionId) {
        RemoteTerminalView(context).apply {
            onGridSizeChanged = controller::onGridSize
            onInput = controller::input
        }
    }

    DisposableEffect(sessionId) {
        controller.onSessionReady = { session -> terminalView.setSession(session) }
        controller.onRepaint = { terminalView.repaint() }
        controller.onStateChanged = { newState -> state = newState }
        onDispose { controller.dispose() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = statusLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TerminalOverflowMenu(
                        onTerminate = {
                            // Kills the tmux session; the socket then drops to Detached.
                            scope.launch { repository.terminateAgent(sessionId) }
                        },
                        onDelete = { confirmDelete = true },
                    )
                },
            )
        },
        bottomBar = { KeyBar(terminalView) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { terminalView },
                modifier = Modifier.fillMaxSize(),
            )

            when (val s = state) {
                is TerminalState.Connecting -> Overlay("Connecting…")
                is TerminalState.Detached -> Overlay(
                    message = "Detached — the session is still running.",
                    actionLabel = "Reconnect",
                    onAction = controller::reconnect,
                )
                is TerminalState.Failed -> Overlay(
                    message = s.message,
                    actionLabel = "Retry",
                    onAction = controller::reconnect,
                )
                TerminalState.Attached -> Unit // live terminal shows through
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $title?") },
            text = { Text("This terminates the agent and removes its record. This can't be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.deleteAgent(sessionId, removeWorktree = false)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TerminalOverflowMenu(onTerminate: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Terminate") },
            onClick = {
                expanded = false
                onTerminate()
            },
        )
        DropdownMenuItem(
            text = { Text("Delete agent", color = MaterialTheme.colorScheme.error) },
            onClick = {
                expanded = false
                onDelete()
            },
        )
    }
}

private fun statusLabel(state: TerminalState): String = when (state) {
    TerminalState.Connecting -> "connecting"
    TerminalState.Attached -> "attached"
    TerminalState.Detached -> "detached"
    is TerminalState.Failed -> "error"
}

/** Extra-keys row for keys a soft keyboard lacks (design.md §5.1). */
@Composable
private fun KeyBar(view: RemoteTerminalView) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Ride above the soft keyboard when it's open, and above the
                // navigation bar when it isn't. union() takes the max per side, so
                // the two never stack: IME height dominates when shown (it already
                // spans the nav-bar region), nav-bar height applies when hidden.
                // Because this is the Scaffold's bottomBar, its measured height
                // grows with the IME and Scaffold shrinks the terminal above it —
                // which fires RemoteTerminalView.onSizeChanged → the WS resize frame.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyChip("Esc") { view.sendBytes(byteArrayOf(0x1b)) }
            KeyChip("Tab") { view.sendKeyCode(KeyEvent.KEYCODE_TAB) }
            KeyChip("^C") { view.sendBytes(byteArrayOf(0x03)) }
            KeyChip("^D") { view.sendBytes(byteArrayOf(0x04)) }
            KeyChip("^Z") { view.sendBytes(byteArrayOf(0x1a)) }
            KeyChip("←") { view.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
            KeyChip("↓") { view.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
            KeyChip("↑") { view.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
            KeyChip("→") { view.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
            KeyChip("Home") { view.sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
            KeyChip("End") { view.sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
            KeyChip("PgUp") { view.sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
            KeyChip("PgDn") { view.sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Overlay(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xCC000000),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}
