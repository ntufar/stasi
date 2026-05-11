package io.github.ntufar.stasi

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import io.github.ntufar.stasi.data.repository.QuietHoursSettings
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.ui.arrivals.ArrivalsScreen
import io.github.ntufar.stasi.ui.home.HomeScreen
import io.github.ntufar.stasi.ui.map.MapScreen
import io.github.ntufar.stasi.ui.search.SearchScreen
import kotlinx.coroutines.launch

@Composable
fun StasiApp(initialStopCode: String? = null) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current
    val localeTag by container.settingsRepository.localeTag.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.LANGUAGE_EL,
    )
    val alertThresholdMinutes by container.settingsRepository.arrivalAlertThresholdMinutes.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_ARRIVAL_ALERT_THRESHOLD_MINUTES,
    )
    val quietHours by container.settingsRepository.quietHours.collectAsStateWithLifecycle(
        initialValue = QuietHoursSettings(
            enabled = false,
            startMinutes = SettingsRepository.DEFAULT_QUIET_HOURS_START_MINUTES,
            endMinutes = SettingsRepository.DEFAULT_QUIET_HOURS_END_MINUTES,
        ),
    )

    LaunchedEffect(localeTag) {
        Log.d(LOCALE_LOG_TAG, "StasiApp: compose localeTag=$localeTag")
    }

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

    LaunchedEffect(initialStopCode) {
        val stop = initialStopCode?.trim().orEmpty()
        if (stop.isBlank()) return@LaunchedEffect
        navController.navigate("arrivals/$stop") {
            launchSingleTop = true
        }
    }

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
                    modifier = Modifier.testTag("drawer_search"),
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
                    modifier = Modifier.testTag("drawer_map"),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.settings_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
                var thresholdMenuExpanded by remember { mutableStateOf(false) }
                var quietHoursEnabledMenuExpanded by remember { mutableStateOf(false) }
                var quietStartMenuExpanded by remember { mutableStateOf(false) }
                var quietEndMenuExpanded by remember { mutableStateOf(false) }
                val quietHourChoices = remember { (0..23).toList() }
                Text(
                    stringResource(R.string.settings_arrival_alert_threshold_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(
                        onClick = { thresholdMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.minutes_short, alertThresholdMinutes))
                    }
                    DropdownMenu(
                        expanded = thresholdMenuExpanded,
                        onDismissRequest = { thresholdMenuExpanded = false },
                    ) {
                        SettingsRepository.arrivalAlertThresholdChoices.forEach { minutesOption ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.minutes_short, minutesOption)) },
                                onClick = {
                                    scope.launch {
                                        container.settingsRepository.setArrivalAlertThresholdMinutes(minutesOption)
                                        thresholdMenuExpanded = false
                                    }
                                },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.settings_quiet_hours_heading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = { quietHoursEnabledMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (quietHours.enabled) {
                            stringResource(R.string.settings_quiet_hours_on)
                        } else {
                            stringResource(R.string.settings_quiet_hours_off)
                        },
                    )
                }
                DropdownMenu(
                    expanded = quietHoursEnabledMenuExpanded,
                    onDismissRequest = { quietHoursEnabledMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_quiet_hours_on)) },
                        onClick = {
                            scope.launch {
                                container.settingsRepository.setQuietHoursEnabled(true)
                                quietHoursEnabledMenuExpanded = false
                            }
                            Unit
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_quiet_hours_off)) },
                        onClick = {
                            scope.launch {
                                container.settingsRepository.setQuietHoursEnabled(false)
                                quietHoursEnabledMenuExpanded = false
                            }
                            Unit
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(
                            onClick = { quietStartMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_quiet_hours_start_value, quietHours.startMinutes / 60))
                        }
                        DropdownMenu(
                            expanded = quietStartMenuExpanded,
                            onDismissRequest = { quietStartMenuExpanded = false },
                        ) {
                            quietHourChoices.forEach { hour ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_hour_value, hour)) },
                                    onClick = {
                                        scope.launch {
                                            container.settingsRepository.setQuietHoursStartMinutes(hour * 60)
                                            quietStartMenuExpanded = false
                                        }
                                        Unit
                                    },
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(
                            onClick = { quietEndMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_quiet_hours_end_value, quietHours.endMinutes / 60))
                        }
                        DropdownMenu(
                            expanded = quietEndMenuExpanded,
                            onDismissRequest = { quietEndMenuExpanded = false },
                        ) {
                            quietHourChoices.forEach { hour ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_hour_value, hour)) },
                                    onClick = {
                                        scope.launch {
                                            container.settingsRepository.setQuietHoursEndMinutes(hour * 60)
                                            quietEndMenuExpanded = false
                                        }
                                        Unit
                                    },
                                )
                            }
                        }
                    }
                }
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
                        Log.d(
                            LOCALE_LOG_TAG,
                            "language UI: English clicked (current localeTag=$localeTag)",
                        )
                        scope.launch {
                            Log.d(LOCALE_LOG_TAG, "language switch: coroutine started (English)")
                            drawerState.close()
                            container.settingsRepository.setLocaleTag(SettingsRepository.LANGUAGE_EN)
                            AppLocale.apply(SettingsRepository.LANGUAGE_EN)
                            Log.d(LOCALE_LOG_TAG, "language switch: coroutine finished (English)")
                        }
                        Unit
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.language_greek)) },
                    selected = localeTag == SettingsRepository.LANGUAGE_EL,
                    onClick = {
                        Log.d(
                            LOCALE_LOG_TAG,
                            "language UI: Greek clicked (current localeTag=$localeTag)",
                        )
                        scope.launch {
                            Log.d(LOCALE_LOG_TAG, "language switch: coroutine started (Greek)")
                            drawerState.close()
                            container.settingsRepository.setLocaleTag(SettingsRepository.LANGUAGE_EL)
                            AppLocale.apply(SettingsRepository.LANGUAGE_EL)
                            Log.d(LOCALE_LOG_TAG, "language switch: coroutine finished (Greek)")
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
                        scope.launch { container.recentActivityRepository.recordStopVisit(stopCode) }
                        navController.navigate("arrivals/$stopCode")
                    },
                    onMapManual = { navController.navigate("map_manual") },
                    onOpenRouteMap = { routeCode ->
                        scope.launch { container.recentActivityRepository.recordRouteVisit(routeCode) }
                        navController.navigate("map/$routeCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("search") {
                SearchScreen(
                    onOpenMenu = openDrawer,
                    onBack = { navController.popBackStack() },
                    onStopSelected = { stopCode ->
                        scope.launch { container.recentActivityRepository.recordStopVisit(stopCode) }
                        navController.navigate("arrivals/$stopCode") {
                            launchSingleTop = true
                        }
                    },
                    onOpenLineOnMap = { routeCode ->
                        scope.launch { container.recentActivityRepository.recordRouteVisit(routeCode) }
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
                        scope.launch { container.recentActivityRepository.recordRouteVisit(routeCode) }
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
                        scope.launch { container.recentActivityRepository.recordStopVisit(stopCode) }
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
                        scope.launch { container.recentActivityRepository.recordStopVisit(stopCode) }
                        navController.navigate("arrivals/$stopCode") {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
