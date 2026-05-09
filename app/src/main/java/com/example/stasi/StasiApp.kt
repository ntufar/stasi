package com.example.stasi

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.stasi.ui.arrivals.ArrivalsScreen
import com.example.stasi.ui.home.HomeScreen
import com.example.stasi.ui.map.MapScreen
import com.example.stasi.ui.search.SearchScreen

@Composable
fun StasiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onSearch = { navController.navigate("search") },
                onArrivals = { stopCode ->
                    navController.navigate("arrivals/$stopCode")
                },
                onMapManual = { navController.navigate("map_manual") },
            )
        }
        composable("search") {
            SearchScreen(
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
                onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}
