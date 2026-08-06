package com.warden.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.launch

/** The two top-level destinations reachable from the bottom navigation bar. */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    Agents("Agents", Icons.Filled.SmartToy),
    Pipelines("Pipelines", Icons.Filled.AccountTree),
}

/**
 * The signed-in landing shell. A bottom [NavigationBar] switches between the Agents
 * fleet (the default tab) and the Pipelines list. A single side [HostDrawer] wraps
 * both tabs so their top bars read the same — hamburger + host picker — and a host
 * switch from either place applies everywhere.
 *
 * The outer scaffold owns only the bottom bar, so `contentWindowInsets` is cleared to
 * let the inner scaffolds handle status-bar insets without doubling them. When the last
 * host is disconnected there is nothing to show, so we bounce back to Connect via
 * [onNoConnections].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: WardenRepository,
    settings: SettingsStore,
    onAgentClick: (Session) -> Unit,
    onCreateClick: () -> Unit,
    onAddHost: () -> Unit,
    onNoConnections: () -> Unit,
    onPipelineClick: (Pipeline) -> Unit,
) {
    val hosts by repository.hosts.collectAsStateWithLifecycle()

    LaunchedEffect(hosts.connections.isEmpty()) {
        if (hosts.connections.isEmpty()) onNoConnections()
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingDisconnect by remember { mutableStateOf<Connection?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Agents) }

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
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    HomeTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
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
