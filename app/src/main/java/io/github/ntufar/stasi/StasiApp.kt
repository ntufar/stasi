package io.github.ntufar.stasi

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.ntufar.stasi.data.repository.SettingsRepository
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.ui.arrivals.ArrivalsScreen
import io.github.ntufar.stasi.ui.home.HomeScreen
import io.github.ntufar.stasi.ui.map.MapScreen
import io.github.ntufar.stasi.ui.search.SearchScreen
import kotlinx.coroutines.launch

@Composable
fun StasiApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current
    val localeTag by container.settingsRepository.localeTag.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.LANGUAGE_EL,
    )

    val openDrawer: () -> Unit = remember(scope, drawerState) {
        {
            scope.launch { drawerState.open() }
            Unit
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // Map panning uses horizontal drags; drawer edge-swipe would steal rightward pans.
    val drawerSwipeGesturesEnabled =
        currentRoute != "map_manual" && currentRoute?.startsWith("map/") != true

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerSwipeGesturesEnabled,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_home)) },
                    selected = currentRoute == "home",
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("home") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        Unit
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_search)) },
                    selected = currentRoute == "search",
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("search") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        Unit
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_map)) },
                    selected = currentRoute == "map_manual" ||
                        (currentRoute?.startsWith("map/") == true),
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("map_manual") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        Unit
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.language_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.language_english)) },
                    selected = localeTag == SettingsRepository.LANGUAGE_EN,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            container.settingsRepository.setLocaleTag(SettingsRepository.LANGUAGE_EN)
                            AppLocale.apply(SettingsRepository.LANGUAGE_EN)
                        }
                        Unit
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.language_greek)) },
                    selected = localeTag == SettingsRepository.LANGUAGE_EL,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            container.settingsRepository.setLocaleTag(SettingsRepository.LANGUAGE_EL)
                            AppLocale.apply(SettingsRepository.LANGUAGE_EL)
                        }
                        Unit
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        },
    ) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenMenu = openDrawer,
                    onSearch = {
                        navController.navigate("search") {
                            launchSingleTop = true
                        }
                    },
                    onArrivals = { stopCode ->
                        navController.navigate("arrivals/$stopCode")
                    },
                    onMapManual = { navController.navigate("map_manual") },
                )
            }
            composable("search") {
                SearchScreen(
                    onOpenMenu = openDrawer,
                    onBack = { navController.popBackStack() },
                    onStopSelected = { stopCode ->
                        navController.navigate("arrivals/$stopCode") {
                            launchSingleTop = true
                        }
                    },
                    onOpenLineOnMap = { routeCode ->
                        navController.navigate("map/$routeCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = "arrivals/{stopCode}",
                arguments = listOf(
                    navArgument("stopCode") { type = NavType.StringType },
                ),
            ) { entry ->
                val stopCode = entry.arguments?.getString("stopCode").orEmpty()
                ArrivalsScreen(
                    onOpenMenu = openDrawer,
                    stopCode = stopCode,
                    onBack = { navController.popBackStack() },
                    onOpenMap = { routeCode ->
                        navController.navigate("map/$routeCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("map_manual") {
                MapScreen(
                    presetRouteCode = null,
                    onOpenMenu = openDrawer,
                    onBack = { navController.popBackStack() },
                    onStopSelected = { stopCode ->
                        navController.navigate("arrivals/$stopCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = "map/{routeCode}",
                arguments = listOf(
                    navArgument("routeCode") { type = NavType.StringType },
                ),
            ) { entry ->
                val routeCode = entry.arguments?.getString("routeCode").orEmpty()
                MapScreen(
                    presetRouteCode = routeCode.ifBlank { null },
                    onOpenMenu = openDrawer,
                    onBack = { navController.popBackStack() },
                    onStopSelected = { stopCode ->
                        navController.navigate("arrivals/$stopCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
