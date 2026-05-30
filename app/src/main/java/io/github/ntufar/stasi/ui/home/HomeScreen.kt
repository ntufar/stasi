package io.github.ntufar.stasi.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.ntufar.stasi.R
import io.github.ntufar.stasi.ui.ClockText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.util.freshnessUpdatedLabel
import com.google.android.gms.location.LocationServices

private fun hasAnyLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMenu: () -> Unit,
    onSearch: () -> Unit,
    onArrivals: (stopCode: String) -> Unit,
    onMapManual: () -> Unit,
    onOpenRouteMap: (routeCode: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(
        factory = remember(container, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        context.applicationContext,
                        container.oasaRepository,
                        container.favoritesRepository,
                        container.recentActivityRepository,
                    ) as T
            }
        },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var activeMenuStopCode by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<FavoriteStopCard?>(null) }
    var renameValue by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (hasAnyLocationPermission(context)) {
                try {
                    fused.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            vm.refreshNearby(loc.latitude, loc.longitude)
                        }
                    }
                } catch (_: SecurityException) {
                    // Revoked between grant callback and call; ignore.
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("screen_home"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ClockText(modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onOpenMenu, modifier = Modifier.testTag("btn_menu")) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                    IconButton(onClick = vm::refreshNow) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                    }
                    IconButton(onClick = onMapManual) {
                        Icon(Icons.Default.Map, contentDescription = stringResource(R.string.cd_map))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.home_nearby_stops),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.home_location_button))
                }
                ui.nearbyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(ui.nearby, key = { it.stopCode }) { n ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArrivals(n.stopCode) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n.description, fontWeight = FontWeight.Medium)
                        Text(n.stopCode, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (ui.recentStop != null || ui.recentRoute != null) {
                item {
                    Text(
                        stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                ui.recentStop?.let { recent ->
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArrivals(recent.code) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.home_recent_stop),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    recent.title,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    recent.subtitle ?: recent.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                ui.recentRoute?.let { recent ->
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRouteMap(recent.code) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.home_recent_route),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    recent.title,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    recent.subtitle ?: recent.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.home_favorites),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (ui.isLoading && ui.favoriteCards.isEmpty()) {
                item { CircularProgressIndicator() }
            }
            if (ui.favoriteCards.isEmpty() && !ui.isLoading) {
                item {
                    Text(
                        stringResource(R.string.home_favorites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.favoriteCards, key = { it.stopCode }) { card ->
                val freshness = freshnessUpdatedLabel(context, card.lastUpdatedMillis, R.string.home_updated_at)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArrivals(card.stopCode) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    card.alias?.takeIf { it.isNotBlank() } ?: card.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                card.alias?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        card.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { activeMenuStopCode = card.stopCode }) {
                                    Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_more_actions),
                                )
                                }
                                DropdownMenu(
                                    expanded = activeMenuStopCode == card.stopCode,
                                    onDismissRequest = { activeMenuStopCode = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_favorite_rename)) },
                                        onClick = {
                                            renameTarget = card
                                            renameValue = card.alias.orEmpty()
                                            activeMenuStopCode = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_favorite_move_up)) },
                                        onClick = {
                                            vm.moveFavorite(card.stopCode, -1)
                                            activeMenuStopCode = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_favorite_move_down)) },
                                        onClick = {
                                            vm.moveFavorite(card.stopCode, 1)
                                            activeMenuStopCode = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_favorite_remove)) },
                                        onClick = {
                                            vm.removeFavorite(card.stopCode)
                                            activeMenuStopCode = null
                                        },
                                    )
                                }
                            }
                        }
                        freshness?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (card.arrivals.isEmpty()) {
                            Text(
                                stringResource(R.string.home_no_arrivals),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            card.arrivals.take(2).forEach { a ->
                                val m = if (a.minutes >= 999) {
                                    "—"
                                } else {
                                    stringResource(R.string.minutes_short, a.minutes)
                                }
                                Column(Modifier.padding(top = 10.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            m,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "·",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Text(
                                            a.lineLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        "→ ${a.destinationLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.home_favorite_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.home_favorite_alias_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget?.let { vm.renameFavorite(it.stopCode, renameValue) }
                        renameTarget = null
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cd_back))
                }
            },
        )
    }
}
