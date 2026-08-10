package com.warden.android.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The "New terminal" screen. A terminal is just a shell pane, so the form is
 * minimal: an optional working directory (with the shared folder browser) and an
 * optional name. On success it navigates straight into the terminal's PTY.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTerminalScreen(
    viewModel: CreateTerminalViewModel,
    onBack: () -> Unit,
    onCreated: (id: String, label: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Opened → jump into the new terminal's PTY.
    state.created?.let { session ->
        onCreated(session.id, session.displayName)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Opens a shell (\$SHELL) on the daemon host. No AI backend — " +
                    "just a live terminal you can attach to.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.cwd,
                onValueChange = viewModel::setCwd,
                label = { Text("Working directory") },
                placeholder = { Text("(home)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = viewModel::openBrowser) {
                        Icon(Icons.Filled.Folder, contentDescription = "Browse folders")
                    }
                },
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::submit,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.submitting) "Opening…" else "Open terminal")
            }
        }
    }

    state.browser?.let { browser ->
        DirBrowserSheet(
            browser = browser,
            onNavigate = viewModel::browse,
            onChoose = viewModel::chooseCurrentDir,
            onDismiss = viewModel::closeBrowser,
        )
    }

    state.confirm?.let { verdict ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("Open anyway?") },
            text = {
                Text(
                    verdict.reason.ifBlank {
                        "Memory pressure is elevated (${verdict.agent_count}/${verdict.max_agents} sessions)."
                    },
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmOpen) { Text("Open anyway") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text("Cancel") } },
        )
    }
}
