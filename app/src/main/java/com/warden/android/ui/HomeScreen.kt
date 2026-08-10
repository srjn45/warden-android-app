package com.warden.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.android.data.Connection
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.Session
import com.warden.android.ui.agents.AgentsHome
import com.warden.android.ui.agents.DisconnectHostDialog
import com.warden.android.ui.agents.HostDrawer
import com.warden.android.ui.pipelines.PipelineListViewModel
import com.warden.android.ui.pipelines.PipelinesScreen
import com.warden.android.ui.terminals.TerminalsScreen
import com.warden.android.ui.terminals.TerminalsViewModel
import kotlinx.coroutines.launch

/** The top-level destinations, reached from the side navigation drawer. */
private enum class HomeTab { Agents, Pipelines, Terminals }

/**
 * The signed-in landing shell. A single side [HostDrawer] is the primary navigation:
 * it holds the collapsible host list plus the Agents and Pipelines destinations, and a
 * host switch from either place applies everywhere. Each tab's own top bar carries the
 * hamburger that opens the drawer and a title that doubles as a quick host picker.
 *
 * When the last host is disconnected there is nothing to show, so we bounce back to
 * Connect via [onNoConnections].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: WardenRepository,
    settings: SettingsStore,
    onAgentClick: (Session) -> Unit,
    onCreateClick: () -> Unit,
    onCreateTerminal: () -> Unit,
    onAddHost: () -> Unit,
    onNoConnections: () -> Unit,
    onPipelineClick: (Pipeline) -> Unit,
) {
    val hosts by repository.hosts.collectAsStateWithLifecycle()
    val terminalsSupported by repository.terminalSessions.collectAsStateWithLifecycle()

    LaunchedEffect(hosts.connections.isEmpty()) {
        if (hosts.connections.isEmpty()) onNoConnections()
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingDisconnect by remember { mutableStateOf<Connection?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Agents) }

    // If the Terminals tab is showing and the active host stops supporting
    // terminal-sessions (e.g. switching to an older daemon), fall back to Agents.
    LaunchedEffect(terminalsSupported) {
        if (!terminalsSupported && tab == HomeTab.Terminals) tab = HomeTab.Agents
    }

    // The active host's base URL, for the Pipelines picker subtitle (the Agents tab
    // sources the same string from its own stream state).
    val activeHostLabel = remember(hosts) {
        hosts.connections.firstOrNull { it.label == hosts.activeLabel }?.baseUrl ?: ""
    }

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
                agentsSelected = tab == HomeTab.Agents,
                pipelinesSelected = tab == HomeTab.Pipelines,
                onSelectAgents = {
                    tab = HomeTab.Agents
                    scope.launch { drawerState.close() }
                },
                onSelectPipelines = {
                    tab = HomeTab.Pipelines
                    scope.launch { drawerState.close() }
                },
                terminalsVisible = terminalsSupported,
                terminalsSelected = tab == HomeTab.Terminals,
                onSelectTerminals = {
                    tab = HomeTab.Terminals
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                HomeTab.Agents -> AgentsHome(
                    repository = repository,
                    settings = settings,
                    onAgentClick = onAgentClick,
                    onCreateClick = onCreateClick,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    hosts = hosts.connections,
                    activeLabel = hosts.activeLabel,
                    onSwitchHost = { repository.switchTo(it) },
                )

                HomeTab.Pipelines -> {
                    val vm: PipelineListViewModel = viewModel(
                        factory = PipelineListViewModel.Factory(repository),
                    )
                    PipelinesScreen(
                        viewModel = vm,
                        onPipelineClick = onPipelineClick,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        hostLabel = activeHostLabel,
                        hosts = hosts.connections,
                        activeLabel = hosts.activeLabel,
                        onSwitchHost = { repository.switchTo(it) },
                    )
                }

                HomeTab.Terminals -> {
                    val vm: TerminalsViewModel = viewModel(
                        factory = TerminalsViewModel.Factory(repository),
                    )
                    TerminalsScreen(
                        viewModel = vm,
                        onTerminalClick = onAgentClick,
                        onNewTerminal = onCreateTerminal,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        hosts = hosts.connections,
                        activeLabel = hosts.activeLabel,
                        onSwitchHost = { repository.switchTo(it) },
                    )
                }
            }
        }
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
