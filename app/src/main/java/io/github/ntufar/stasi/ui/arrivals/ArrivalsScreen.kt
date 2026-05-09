package io.github.ntufar.stasi.ui.arrivals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            imageVector = if (ui.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorite",
                        )
                    }
                    IconButton(
                        onClick = {
                            mapRouteCode?.let(onOpenMap)
                        },
                        enabled = mapRouteCode != null,
                    ) {
                        Icon(Icons.Default.Map, contentDescription = "Map")
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
                items(ui.arrivals, key = { "${it.routeCode}-${it.vehCode}-${it.minutes}" }) { a ->
                    val minutesText = if (a.minutes >= 999) "—" else "${a.minutes}΄"
                    val originText = a.originDepartureMinutes?.let { om ->
                        if (om >= 999) return@let null
                        val oLabel = a.originStopDescription?.takeIf { it.isNotBlank() } ?: ""
                        val fromPart = if (oLabel.isNotEmpty()) " ($oLabel)" else ""
                        "Από αφετηρία$fromPart: ${om}΄"
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = a.routeCode.isNotBlank()) {
                                onOpenMap(a.routeCode)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            minutesText,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(a.lineLabel, style = MaterialTheme.typography.titleMedium)
                        Text(a.destinationLabel, style = MaterialTheme.typography.bodyLarge)
                        if (originText != null) {
                            Text(
                                originText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
