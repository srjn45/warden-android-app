package com.warden.android.ui.agents

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.android.data.Connection
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session
import kotlinx.coroutines.launch

/**
 * The AGENTS destination, wrapped in a side navigation drawer for multi-host
 * management. A single [AgentListViewModel] backs the list across host switches
 * (it re-opens its stream when the active host changes — see the ViewModel), so
 * switching a host from either the drawer or the title-bar picker just works.
 *
 * When the last host is disconnected there is nothing to show, so we bounce back
 * to the Connect screen via [onNoConnections].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsHome(
    repository: WardenRepository,
    settings: SettingsStore,
    onAgentClick: (Session) -> Unit,
    onCreateClick: () -> Unit,
    onAddHost: () -> Unit,
    onNoConnections: () -> Unit,
) {
    val hosts by repository.hosts.collectAsStateWithLifecycle()

    LaunchedEffect(hosts.connections.isEmpty()) {
        if (hosts.connections.isEmpty()) onNoConnections()
    }

    val vm: AgentListViewModel = viewModel(
        factory = AgentListViewModel.Factory(repository, settings),
    )

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingDisconnect by remember { mutableStateOf<Connection?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HostDrawer(
                connections = hosts.connections,
                activeLabel = hosts.activeLabel,
                onSwitch = { label ->
                    repository.switchTo(label)
                    scope.launch { drawerState.close() }
                },
                onAddHost = {
                    scope.launch { drawerState.close() }
                    onAddHost()
                },
                onDisconnect = { pendingDisconnect = it },
            )
        },
    ) {
        AgentListScreen(
            viewModel = vm,
            onAgentClick = onAgentClick,
            onCreateClick = onCreateClick,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            hosts = hosts.connections,
            activeLabel = hosts.activeLabel,
            onSwitchHost = { repository.switchTo(it) },
        )
    }

    pendingDisconnect?.let { target ->
        DisconnectHostDialog(
            connection = target,
            onConfirm = {
                repository.disconnect(target.label)
                pendingDisconnect = null
            },
            onDismiss = { pendingDisconnect = null },
        )
    }
}
