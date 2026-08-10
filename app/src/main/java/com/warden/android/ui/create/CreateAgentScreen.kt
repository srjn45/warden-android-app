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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.android.data.model.Backend
import com.warden.android.data.model.BackendInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentScreen(
    viewModel: CreateAgentViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Spawn succeeded → leave the sheet; the new agent shows up via the live list.
    if (state.created != null) {
        onCreated()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New agent") },
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
            BackendDropdown(
                selected = state.backend,
                backends = state.backends,
                onSelect = viewModel::setBackend,
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

            RoleDropdown(
                selected = state.role,
                roles = state.roles.map { it.name },
                onSelect = viewModel::setRole,
            )

            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::setModel,
                label = { Text("Model (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                label = { Text("Initial prompt (optional)") },
                minLines = 3,
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
                Text(if (state.submitting) "Spawning…" else "Create agent")
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
        SpawnGateDialog(
            reason = verdict.reason.ifBlank {
                "Memory pressure is elevated (${verdict.agent_count}/${verdict.max_agents} agents)."
            },
            onConfirm = viewModel::confirmSpawn,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendDropdown(
    selected: Backend,
    backends: List<BackendInfo>,
    onSelect: (Backend) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            backends.forEach { backend ->
                // Un-installed backends (per the daemon's LookPath check) are
                // shown but disabled, so the list still documents what exists.
                DropdownMenuItem(
                    text = {
                        Text(if (backend.available) backend.label else "${backend.label} (not installed)")
                    },
                    enabled = backend.available,
                    onClick = {
                        onSelect(backend.toBackend())
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleDropdown(selected: String, roles: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "None" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Role") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            roles.forEach { role ->
                DropdownMenuItem(
                    text = { Text(role) },
                    onClick = {
                        onSelect(role)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SpawnGateDialog(reason: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spawn anyway?") },
        text = { Text(reason) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Spawn anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
