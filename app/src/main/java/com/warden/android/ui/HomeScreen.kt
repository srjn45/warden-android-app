package com.warden.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.Session
import com.warden.android.ui.agents.AgentsHome
import com.warden.android.ui.pipelines.PipelineListViewModel
import com.warden.android.ui.pipelines.PipelinesScreen

/** The two top-level destinations reachable from the bottom navigation bar. */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    Agents("Agents", Icons.Filled.SmartToy),
    Pipelines("Pipelines", Icons.Filled.AccountTree),
}

/**
 * The signed-in landing shell. A bottom [NavigationBar] switches between the
 * Agents fleet (the default tab) and the Pipelines list; each destination keeps
 * its own top bar / FAB / drawer. The outer scaffold owns only the bottom bar, so
 * `contentWindowInsets` is cleared to let the inner scaffolds handle status-bar
 * insets without doubling them.
 */
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.Agents) }

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
                    onAddHost = onAddHost,
                    onNoConnections = onNoConnections,
                )

                HomeTab.Pipelines -> {
                    val vm: PipelineListViewModel = viewModel(
                        factory = PipelineListViewModel.Factory(repository),
                    )
                    PipelinesScreen(viewModel = vm, onPipelineClick = onPipelineClick)
                }
            }
        }
    }
}
