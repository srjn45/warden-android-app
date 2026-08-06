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
import com.warden.android.ui.connect.ConnectScreen
import com.warden.android.ui.connect.ConnectViewModel
import com.warden.android.ui.create.CreateAgentScreen
import com.warden.android.ui.create.CreateAgentViewModel
import com.warden.android.ui.pipelines.PipelineDetailScreen
import com.warden.android.ui.pipelines.PipelineDetailViewModel
import com.warden.android.ui.terminal.TerminalScreen
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val CONNECT = "connect"
    const val ADD_HOST = "add_host"
    const val HOME = "home"
    const val CREATE = "create"
    const val TERMINAL = "terminal"
    const val PIPELINE = "pipeline"
}

@Composable
fun WardenNavHost(repository: WardenRepository) {
    val navController = rememberNavController()
    // Skip straight to the home shell if we already have a saved, active connection.
    val start = if (repository.active != null) Routes.HOME else Routes.CONNECT

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.CONNECT) {
            val vm: ConnectViewModel = viewModel(factory = ConnectViewModel.Factory(repository))
            ConnectScreen(
                viewModel = vm,
                onConnected = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
            )
        }
        // "Add host" from the drawer: the same Connect form, started blank, layered
        // over the agent list. On success it just pops back — the repository has
        // already made the new host active, so the list + drawer update in place.
        composable(Routes.ADD_HOST) {
            val vm: ConnectViewModel = viewModel(
                factory = ConnectViewModel.Factory(repository, prefill = false),
            )
            ConnectScreen(
                viewModel = vm,
                onConnected = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME) {
            val settings = (LocalContext.current.applicationContext as WardenApplication).settings
            HomeScreen(
                repository = repository,
                settings = settings,
                onAgentClick = { agent ->
                    val id = URLEncoder.encode(agent.id, "UTF-8")
                    val label = URLEncoder.encode(agent.displayName, "UTF-8")
                    navController.navigate("${Routes.TERMINAL}/$id/$label")
                },
                onCreateClick = { navController.navigate(Routes.CREATE) },
                onAddHost = { navController.navigate(Routes.ADD_HOST) },
                onNoConnections = {
                    navController.navigate(Routes.CONNECT) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onPipelineClick = { pipeline ->
                    val id = URLEncoder.encode(pipeline.id, "UTF-8")
                    val label = URLEncoder.encode(pipeline.displayName, "UTF-8")
                    navController.navigate("${Routes.PIPELINE}/$id/$label")
                },
            )
        }
        composable(Routes.CREATE) {
            val vm: CreateAgentViewModel = viewModel(factory = CreateAgentViewModel.Factory(repository))
            CreateAgentScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
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
        composable(
            route = "${Routes.PIPELINE}/{id}/{label}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("label") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val id = URLDecoder.decode(backStackEntry.arguments?.getString("id").orEmpty(), "UTF-8")
            val label = URLDecoder.decode(backStackEntry.arguments?.getString("label").orEmpty(), "UTF-8")
            val vm: PipelineDetailViewModel = viewModel(
                factory = PipelineDetailViewModel.Factory(repository, id),
            )
            PipelineDetailScreen(
                viewModel = vm,
                title = label,
                onBack = { navController.popBackStack() },
                onJobClick = { sessionId, jobLabel ->
                    val sid = URLEncoder.encode(sessionId, "UTF-8")
                    val jl = URLEncoder.encode(jobLabel, "UTF-8")
                    navController.navigate("${Routes.TERMINAL}/$sid/$jl")
                },
            )
        }
    }
}
