package com.warden.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.warden.android.WardenApplication
import com.warden.android.data.WardenRepository
import com.warden.android.ui.agents.AgentListScreen
import com.warden.android.ui.agents.AgentListViewModel
import com.warden.android.ui.connect.ConnectScreen
import com.warden.android.ui.connect.ConnectViewModel
import com.warden.android.ui.terminal.TerminalScreen
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val CONNECT = "connect"
    const val AGENTS = "agents"
    const val TERMINAL = "terminal"
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
            val settings = (LocalContext.current.applicationContext as WardenApplication).settings
            // Key the ViewModel to the active base URL so switching hosts later
            // gives a fresh stream rather than a stale one.
            val vm: AgentListViewModel = viewModel(
                key = "agents:${repository.active?.baseUrl}",
                factory = AgentListViewModel.Factory(repository, settings),
            )
            AgentListScreen(
                viewModel = vm,
                onAgentClick = { agent ->
                    val id = URLEncoder.encode(agent.id, "UTF-8")
                    val label = URLEncoder.encode(agent.displayName, "UTF-8")
                    navController.navigate("${Routes.TERMINAL}/$id/$label")
                },
            )
        }
        composable(
            route = "${Routes.TERMINAL}/{id}/{label}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("label") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val id = URLDecoder.decode(backStackEntry.arguments?.getString("id").orEmpty(), "UTF-8")
            val label = URLDecoder.decode(backStackEntry.arguments?.getString("label").orEmpty(), "UTF-8")
            TerminalScreen(
                repository = repository,
                sessionId = id,
                title = label,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
