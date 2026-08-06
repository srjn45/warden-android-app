package com.warden.android.ui.agents

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.android.data.Connection
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session

/**
 * The AGENTS destination. A single [AgentListViewModel] backs the list across host
 * switches (it re-opens its stream when the active host changes — see the ViewModel),
 * so switching a host from either the shared drawer or the title-bar picker just works.
 *
 * The navigation drawer and host management live in the parent [com.warden.android.ui.HomeScreen]
 * so the Agents and Pipelines tabs share one drawer; this passes the drawer + host-picker
 * hooks straight through to [AgentListScreen].
 */
@Composable
fun AgentsHome(
    repository: WardenRepository,
    settings: SettingsStore,
    onAgentClick: (Session) -> Unit,
    onCreateClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    hosts: List<Connection>,
    activeLabel: String?,
    onSwitchHost: (String) -> Unit,
) {
    val vm: AgentListViewModel = viewModel(
        factory = AgentListViewModel.Factory(repository, settings),
    )

    AgentListScreen(
        viewModel = vm,
        onAgentClick = onAgentClick,
        onCreateClick = onCreateClick,
        onOpenDrawer = onOpenDrawer,
        hosts = hosts,
        activeLabel = activeLabel,
        onSwitchHost = onSwitchHost,
    )
}
