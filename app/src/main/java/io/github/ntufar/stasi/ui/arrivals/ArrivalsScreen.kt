package io.github.ntufar.stasi.ui.arrivals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.ntufar.stasi.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.stasi.di.LocalAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrivalsScreen(
    onOpenMenu: () -> Unit,
    stopCode: String,
    onBack: () -> Unit,
    onOpenMap: (routeCode: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ArrivalsViewModel = viewModel(
        key = stopCode,
        factory = remember(stopCode, container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ArrivalsViewModel(stopCode, container.oasaRepository, container.favoritesRepository) as T
            }
        },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val listRows = remember(ui.arrivals) { buildArrivalListRows(ui.arrivals) }
    val mapRouteCode = remember(ui.arrivals) {
        ui.arrivals.firstOrNull { it.routeCode.isNotBlank() }?.routeCode
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshNow()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title.ifBlank { stopCode }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            imageVector = if (ui.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.cd_favorite),
                        )
                    }
                    IconButton(
                        onClick = {
                            mapRouteCode?.let(onOpenMap)
                        },
                        enabled = mapRouteCode != null,
                    ) {
                        Icon(Icons.Default.Map, contentDescription = stringResource(R.string.cd_map))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.isLoading && ui.arrivals.isEmpty()) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(
                    listRows,
                    key = { row ->
                        when (row) {
                            is ArrivalListRow.Live ->
                                "live-${row.detail.routeCode}-${row.detail.vehCode}-${row.detail.minutes}"
                            is ArrivalListRow.ScheduledOriginDeparture ->
                                "sched-${row.routeCode}-${row.clock}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is ArrivalListRow.Live -> {
                            val a = row.detail
                            val minutesText = if (a.minutes >= 999) {
                                "—"
                            } else {
                                stringResource(R.string.minutes_short, a.minutes)
                            }
                            val originText = a.originDepartureMinutes?.let { om ->
                                if (om >= 999) return@let null
                                val oLabel = a.originStopDescription?.takeIf { it.isNotBlank() } ?: ""
                                val fromPart = if (oLabel.isNotEmpty()) " ($oLabel)" else ""
                                stringResource(
                                    R.string.arrivals_from_origin_minutes,
                                    fromPart,
                                    stringResource(R.string.minutes_short, om),
                                )
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = a.routeCode.isNotBlank()) {
                                        onOpenMap(a.routeCode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                val scheme = MaterialTheme.colorScheme
                                Text(
                                    minutesText,
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (a.minutes >= 999) scheme.onSurfaceVariant else scheme.primary,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        a.lineLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.onSurface,
                                    )
                                    if (a.isLastBusWarning) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFA726),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.arrivals_last_service_warning),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    a.destinationLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                if (originText != null) {
                                    Text(
                                        originText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        is ArrivalListRow.ScheduledOriginDeparture -> {
                            val scheme = MaterialTheme.colorScheme
                            val oLabel = row.originStopDescription?.takeIf { it.isNotBlank() } ?: ""
                            val fromPart = if (oLabel.isNotEmpty()) " ($oLabel)" else ""
                            val approx = when {
                                row.minutesUntil >= 999 -> null
                                row.minutesUntil <= 0 -> stringResource(R.string.arrivals_traffic_approx_less_than)
                                else -> stringResource(R.string.arrivals_traffic_approx_minutes, row.minutesUntil)
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = row.routeCode.isNotBlank()) {
                                        onOpenMap(row.routeCode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                Text(
                                    row.clock,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.secondary,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    row.lineLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.arrivals_schedule_from_origin, fromPart),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                if (approx != null) {
                                    Text(
                                        text = stringResource(R.string.arrivals_traffic_start_approx, approx),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
