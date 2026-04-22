package com.cdp.remote.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cdp.remote.presentation.screen.chat.ChatScreen
import com.cdp.remote.presentation.screen.hosts.HostListScreen
import java.net.URLDecoder

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOST_LIST) {
        composable(Routes.HOST_LIST) {
            HostListScreen(
                onNavigateToChat = { hostIp, hostPort, wsUrl, appName ->
                    navController.navigate(Routes.chatRoute(hostIp, hostPort, wsUrl, appName))
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("hostIp") { type = NavType.StringType },
                navArgument("hostPort") { type = NavType.IntType },
                navArgument("wsUrl") { type = NavType.StringType },
                navArgument("appName") { type = NavType.StringType }
            )
        ) { backStack ->
            val hostIp = backStack.arguments?.getString("hostIp") ?: ""
            val hostPort = backStack.arguments?.getInt("hostPort") ?: 19336
            val wsUrl = URLDecoder.decode(backStack.arguments?.getString("wsUrl") ?: "", "UTF-8")
            val appName = URLDecoder.decode(backStack.arguments?.getString("appName") ?: "", "UTF-8")

            ChatScreen(
                hostIp = hostIp,
                hostPort = hostPort,
                wsUrl = wsUrl,
                appName = appName,
                onNavigateBack = { navController.popBackStack() },
                onSwitchApp = { newIp, newPort, newWs, newApp ->
                    navController.popBackStack()
                    navController.navigate(Routes.chatRoute(newIp, newPort, newWs, newApp))
                }
            )
        }
    }
}
