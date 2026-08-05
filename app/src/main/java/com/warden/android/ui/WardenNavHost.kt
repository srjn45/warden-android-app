package com.warden.android.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.warden.android.data.WardenRepository
import com.warden.android.ui.agents.AgentListScreen
import com.warden.android.ui.agents.AgentListViewModel
import com.warden.android.ui.connect.ConnectScreen
import com.warden.android.ui.connect.ConnectViewModel

private object Routes {
    const val CONNECT = "connect"
    const val AGENTS = "agents"
}

@Composable
fun WardenNavHost(repository: WardenRepository) {
    val navController = rememberNavController()
    // Skip straight to the list if we already have a saved, active connection.
    val start = if (repository.active != null) Routes.AGENTS else Routes.CONNECT

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.CONNECT) {
            val vm: ConnectViewModel = viewModel(factory = ConnectViewModel.Factory(repository))
            ConnectScreen(
                viewModel = vm,
                onConnected = {
                    navController.navigate(Routes.AGENTS) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.AGENTS) {
            // Key the ViewModel to the active base URL so switching hosts later
            // gives a fresh stream rather than a stale one.
            val vm: AgentListViewModel = viewModel(
                key = "agents:${repository.active?.baseUrl}",
                factory = AgentListViewModel.Factory(repository),
            )
            AgentListScreen(viewModel = vm)
        }
    }
}
