package com.kavach.app.ui.nav

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kavach.app.R
import com.kavach.app.ui.apps.AppDetailScreen
import com.kavach.app.ui.apps.AppsScreen
import com.kavach.app.ui.dashboard.DashboardScreen
import com.kavach.app.ui.isolation.IsolationScreen
import com.kavach.app.ui.log.LogScreen
import com.kavach.app.ui.settings.SettingsScreen

/** System-level operations only the Activity can perform. */
data class KavachActions(
    val startShield: () -> Unit,
    val stopShield: () -> Unit,
    val requestProvisioning: (Intent) -> Unit,
    val openAppSettings: (String) -> Unit,
)

sealed class Destination(val route: String) {
    data object Dashboard : Destination("dashboard")
    data object Apps : Destination("apps")
    data object Log : Destination("log")
    data object Isolation : Destination("isolation")
    data object Settings : Destination("settings")

    data object AppDetail : Destination("app/{packageName}") {
        const val ARG = "packageName"
        fun of(packageName: String) = "app/$packageName"
    }
}

private data class Tab(
    val destination: Destination,
    val icon: ImageVector,
    val labelRes: Int,
)

private val tabs = listOf(
    Tab(Destination.Dashboard, Icons.Filled.Shield, R.string.nav_dashboard),
    Tab(Destination.Apps, Icons.Filled.Apps, R.string.nav_apps),
    Tab(Destination.Log, Icons.Filled.Timeline, R.string.nav_logs),
    Tab(Destination.Isolation, Icons.Filled.Lock, R.string.nav_isolation),
    Tab(Destination.Settings, Icons.Filled.Settings, R.string.nav_settings),
)

@Composable
fun KavachRoot(actions: KavachActions) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // The detail screen is a full-page push, so the bar is hidden there.
            if (currentRoute != Destination.AppDetail.route) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.destination.route,
                            onClick = {
                                navController.navigate(tab.destination.route) {
                                    // Keep a single copy of each tab on the back stack and
                                    // preserve each tab's scroll position across switches.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier,
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    contentPadding = padding,
                    onStartShield = actions.startShield,
                    onStopShield = actions.stopShield,
                    onOpenApps = { navController.navigate(Destination.Apps.route) },
                    onOpenLog = { navController.navigate(Destination.Log.route) },
                )
            }

            composable(Destination.Apps.route) {
                AppsScreen(
                    contentPadding = padding,
                    onOpenApp = { navController.navigate(Destination.AppDetail.of(it)) },
                )
            }

            composable(Destination.Log.route) {
                LogScreen(
                    contentPadding = padding,
                    onOpenApp = { navController.navigate(Destination.AppDetail.of(it)) },
                )
            }

            composable(Destination.Isolation.route) {
                IsolationScreen(
                    contentPadding = padding,
                    onRequestProvisioning = actions.requestProvisioning,
                )
            }

            composable(Destination.Settings.route) {
                SettingsScreen(contentPadding = padding)
            }

            composable(
                route = Destination.AppDetail.route,
                arguments = listOf(
                    navArgument(Destination.AppDetail.ARG) { type = NavType.StringType }
                ),
            ) {
                AppDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSystemSettings = actions.openAppSettings,
                )
            }
        }
    }
}
