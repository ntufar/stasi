package io.github.ntufar.stasi.ui.map

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ntufar.stasi.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.stasi.data.repository.BusOnRoute
import io.github.ntufar.stasi.data.repository.RouteDailyTimetable
import io.github.ntufar.stasi.data.repository.RouteDailyTimetableRow
import io.github.ntufar.stasi.data.repository.RouteStop
import io.github.ntufar.stasi.di.LocalAppContainer
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** OSM-based vector style with roads and labels (demotiles URL is a sparse preview and often looks “empty”). */
private const val STYLE_URI = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LINE_LAYER_ID = "route-line"
private const val STOPS_SOURCE_ID = "stops-source"
private const val STOPS_CIRCLE_PREFIX = "stops-circle-"
private const val STOPS_LABEL_LAYER_ID = "stops-labels"
private const val STOPS_NAME_LAYER_ID = "stops-names"
private const val BUSES_SOURCE_ID = "buses-source"
private const val BUSES_LAYER_ID = "buses-layer"
private const val PROP_VEH = "vehicleNo"
private const val PROP_STOP_CODE = "stopCode"
private const val PROP_SEQ = "seq"
private const val PROP_STOP_NAME = "stopName"
private const val PROP_KIND = "kind"
private const val PROP_BEARING = "bearing"
private const val BUS_ICON_ID = "bus-heading-icon"
private const val USER_LOCATION_SOURCE_ID = "user-location-source"
private const val USER_LOCATION_LAYER_ID = "user-location-dot"

private fun hasAnyLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

private val STOP_KINDS = listOf(
    Triple("start", Color.rgb(76, 175, 80), 11f),
    Triple("mid", Color.rgb(0, 172, 193), 9f),
    Triple("end", Color.rgb(244, 67, 54), 11f),
    /** Closest-stop pins (manual map); same size/color as mid — labels use [STOPS_NAME_LAYER_ID]. */
    Triple("nearby", Color.rgb(0, 172, 193), 9f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    presetRouteCode: String?,
    onOpenMenu: () -> Unit,
    onBack: (() -> Unit)?,
    onStopSelected: (stopCode: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: MapViewModel = viewModel(
        key = presetRouteCode ?: "manual",
        factory = remember(presetRouteCode, container, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MapViewModel(
                        context.applicationContext as Application,
                        container.oasaRepository,
                        container.recentActivityRepository,
                        presetRouteCode,
                    ) as T
            }
        },
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var userLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var recenterSignal by remember { mutableIntStateOf(0) }
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationAllowed = hasAnyLocationPermission(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            try {
                fused.lastLocation.addOnSuccessListener { loc ->
                    loc?.let { userLatLng = it.latitude to it.longitude }
                }
            } catch (_: SecurityException) {
                // ignore
            }
            recenterSignal++
        }
    }

    DisposableEffect(locationAllowed, fused) {
        if (!hasAnyLocationPermission(context)) {
            userLatLng = null
            return@DisposableEffect onDispose { }
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    userLatLng = loc.latitude to loc.longitude
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                loc?.let { userLatLng = it.latitude to it.longitude }
            }
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            userLatLng = null
        }
        onDispose {
            fused.removeLocationUpdates(callback)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val nearbyRefreshKey = userLatLng?.let { (lat, lng) ->
        "${(lat * 400).toInt()}_${(lng * 400).toInt()}"
    }
    LaunchedEffect(
        nearbyRefreshKey,
        uiState.manualMode,
        uiState.stops.isEmpty(),
        uiState.isLoading,
        uiState.error,
    ) {
        if (!uiState.manualMode || uiState.stops.isNotEmpty() || uiState.isLoading) return@LaunchedEffect
        val ul = userLatLng ?: return@LaunchedEffect
        vm.refreshNearbyForManualMap(ul.first, ul.second)
    }
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshNow()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("screen_map"),
        topBar = {
            TopAppBar(
                title = {
                    val line = uiState.lineLabel?.takeIf { it.isNotBlank() }
                    val directionDescr = uiState.directions
                        .firstOrNull { it.routeCode == uiState.appliedRouteCode }
                        ?.descr
                        ?.takeIf { it.isNotBlank() }
                    val descr = directionDescr
                        ?: uiState.lineDescr?.takeIf { it.isNotBlank() }
                    Column {
                        Text(
                            text = line?.let { stringResource(R.string.map_title_line, it) }
                                ?: stringResource(R.string.map_title_fallback),
                            maxLines = 1,
                        )
                        if (descr != null) {
                            Text(
                                text = descr,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                    if (uiState.directions.size >= 2) {
                        IconButton(onClick = vm::toggleDirection) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.cd_swap_direction),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.manualMode) {
                val routeHint = stringResource(R.string.map_label_route_or_code)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.routeCodeInput,
                        onValueChange = vm::onRouteCodeInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = routeHint },
                        placeholder = {
                            Text(
                                text = routeHint,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { vm.applyRouteCode() },
                        ),
                        trailingIcon = {
                            IconButton(onClick = vm::applyRouteCode) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search_title),
                                )
                            }
                        },
                    )
                    FilledTonalButton(onClick = vm::applyRouteCode) {
                        Text(stringResource(R.string.map_show))
                    }
                }
            }
            val mapStops = if (uiState.stops.isNotEmpty()) uiState.stops else uiState.nearbyStopsForMap
            val nearbyMarkersOnly =
                uiState.stops.isEmpty() && uiState.nearbyStopsForMap.isNotEmpty()
            val showRouteTabs =
                uiState.stops.isNotEmpty() && uiState.error == null && !uiState.isLoading
            if (showRouteTabs) {
                PrimaryTabRow(selectedTabIndex = uiState.routeTabIndex) {
                    Tab(
                        selected = uiState.routeTabIndex == 0,
                        onClick = { vm.onRouteTabSelected(0) },
                        text = { Text(stringResource(R.string.map_tab_map)) },
                    )
                    Tab(
                        selected = uiState.routeTabIndex == 1,
                        onClick = { vm.onRouteTabSelected(1) },
                        text = { Text(stringResource(R.string.map_tab_timetable)) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (!showRouteTabs || uiState.routeTabIndex == 0) {
                    StasiMapLibre(
                        stops = mapStops,
                        buses = uiState.buses,
                        nearbyMarkersOnly = nearbyMarkersOnly,
                        userLatLng = userLatLng,
                        recenterSignal = recenterSignal,
                        onBusVehicleSelected = vm::selectVehicle,
                        onStopSelected = onStopSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (uiState.routeTabIndex == 0) {
                        FloatingActionButton(
                            onClick = {
                                if (!hasAnyLocationPermission(context)) {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                } else {
                                    recenterSignal++
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = stringResource(R.string.cd_my_location),
                            )
                        }
                    }
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    }
                    uiState.error?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    RouteTimetablePanel(
                        loading = uiState.timetableLoading,
                        error = uiState.timetableError,
                        timetable = uiState.timetable,
                        lastBusWarning = uiState.timetableLastBusWarning,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    uiState.selectedVehicleNo?.let { veh ->
        AlertDialog(
            onDismissRequest = vm::dismissVehicleInfo,
            confirmButton = {
                TextButton(onClick = vm::dismissVehicleInfo) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.map_vehicle_title)) },
            text = { Text(stringResource(R.string.map_vehicle_body, veh)) },
        )
    }
}

@Composable
private fun RouteTimetablePanel(
    loading: Boolean,
    error: String?,
    timetable: RouteDailyTimetable?,
    lastBusWarning: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
            timetable != null -> {
                val origins = timetable.originDepartures
                val termini = timetable.terminusDepartures
                val pairedRows = maxOf(origins.size, termini.size)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.timetable_daily_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (lastBusWarning) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = androidx.compose.ui.graphics.Color(0xFFFFA726).copy(alpha = 0.15f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.timetable_last_service_warning),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = androidx.compose.ui.graphics.Color(0xFFFFA726),
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = stringResource(R.string.timetable_origin),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            VerticalDivider(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp),
                            )
                            Text(
                                text = stringResource(R.string.timetable_terminus),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (pairedRows == 0) {
                        item {
                            Text(
                                text = stringResource(R.string.timetable_no_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(
                            count = pairedRows,
                            key = { ix ->
                                val o = origins.getOrNull(ix)?.primaryRange ?: ""
                                val t = termini.getOrNull(ix)?.primaryRange ?: ""
                                "pair-$ix-$o-$t"
                            },
                        ) { ix ->
                            val isLastOriginRow = lastBusWarning && ix == origins.size - 1
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    TimetableRowCell(origins.getOrNull(ix), isHighlighted = isLastOriginRow)
                                }
                                VerticalDivider(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(vertical = 4.dp),
                                )
                                Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                    TimetableRowCell(termini.getOrNull(ix))
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.timetable_footer_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun TimetableRowCell(row: RouteDailyTimetableRow?, isHighlighted: Boolean = false) {
    val bgColor = if (isHighlighted) {
        androidx.compose.ui.graphics.Color(0xFFFFA726).copy(alpha = 0.15f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp, horizontal = if (isHighlighted) 6.dp else 0.dp),
    ) {
        if (row == null) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        } else {
            Text(
                text = row.primaryRange,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
            row.secondaryRange?.let { sec ->
                Text(
                    text = sec,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StasiMapLibre(
    stops: List<RouteStop>,
    buses: List<BusOnRoute>,
    /** True when [stops] are closest-stop pins, not an OASA route sequence (uniform markers). */
    nearbyMarkersOnly: Boolean,
    userLatLng: Pair<Double, Double>?,
    recenterSignal: Int,
    onBusVehicleSelected: (String) -> Unit,
    onStopSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        onDispose { mapView.onDestroy() }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    val onBusSelected by rememberUpdatedState(onBusVehicleSelected)
    val onStopClicked by rememberUpdatedState(onStopSelected)
    val styleLoadedState = rememberUpdatedState(styleLoaded)
    val mapRefState = rememberUpdatedState(mapRef)
    val userLatLngState = rememberUpdatedState(userLatLng)
    val stopsState = rememberUpdatedState(stops)
    val stopsFingerprint = remember(stops, nearbyMarkersOnly) {
        stops.joinToString("|") { "${it.stopCode}:${it.order}" } + "|n=$nearbyMarkersOnly"
    }
    var initialCameraDoneForRoute by remember(stopsFingerprint) { mutableStateOf(false) }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                ensureRouteLayers(style)
                ensureStopLayers(style)
                val iconSizePx = (context.resources.displayMetrics.density * 28f).toInt().coerceIn(24, 72)
                style.addImage(BUS_ICON_ID, createBusArrowBitmap(iconSizePx))
                ensureBusLayers(style)
                ensureUserLocationLayer(style)
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val busFeat = map.queryRenderedFeatures(screenPoint, BUSES_LAYER_ID)
                    val veh = busFeat.firstOrNull()?.getStringProperty(PROP_VEH)
                    if (veh != null) {
                        onBusSelected(veh)
                        return@addOnMapClickListener true
                    }
                    val stopLayerIds =
                        STOP_KINDS.map { (k, _, _) -> "$STOPS_CIRCLE_PREFIX$k" } +
                            STOPS_LABEL_LAYER_ID +
                            STOPS_NAME_LAYER_ID
                    val stopFeat = map.queryRenderedFeatures(screenPoint, *stopLayerIds.toTypedArray())
                        .firstOrNull()
                    val code = stopFeat?.getStringProperty(PROP_STOP_CODE)
                    if (!code.isNullOrBlank()) {
                        onStopClicked(code)
                        return@addOnMapClickListener true
                    }
                    false
                }
                styleRef = style
                styleLoaded = true
            }
        }
        onDispose {
            styleLoaded = false
            styleRef = null
            mapRef = null
        }
    }

    LaunchedEffect(styleLoaded, stops, buses, nearbyMarkersOnly) {
        if (!styleLoaded) return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect
        val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        val stopsSource = style.getSourceAs<GeoJsonSource>(STOPS_SOURCE_ID)
        val busSource = style.getSourceAs<GeoJsonSource>(BUSES_SOURCE_ID)
        routeSource?.setGeoJson(
            if (nearbyMarkersOnly) {
                FeatureCollection.fromFeatures(emptyArray())
            } else {
                routeFeatureCollection(stops)
            },
        )
        stopsSource?.setGeoJson(stopsFeatureCollection(stops, nearbyMarkersOnly = nearbyMarkersOnly))
        busSource?.setGeoJson(busFeatureCollection(buses, stops))
    }

    LaunchedEffect(styleLoaded, userLatLng) {
        if (!styleLoaded) return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect
        val userSource = style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID) ?: return@LaunchedEffect
        if (userLatLng == null) {
            userSource.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            userSource.setGeoJson(userLocationFeatureCollection(userLatLng.first, userLatLng.second))
        }
    }

    LaunchedEffect(recenterSignal) {
        if (recenterSignal == 0) return@LaunchedEffect
        if (!styleLoadedState.value) return@LaunchedEffect
        val map = mapRefState.value ?: return@LaunchedEffect
        val ul = userLatLngState.value ?: return@LaunchedEffect
        val sorted = stopsState.value.sortedBy { it.order }
        val padding = 80
        val userPoint = LatLng(ul.first, ul.second)
        if (sorted.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            sorted.forEach { builder.include(LatLng(it.lat, it.lng)) }
            builder.include(userPoint)
            try {
                map.easeCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), padding), 500)
            } catch (_: Exception) {
                map.easeCamera(CameraUpdateFactory.newLatLngZoom(userPoint, 14.0), 400)
            }
        } else {
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(userPoint, 14.0), 400)
        }
    }

    LaunchedEffect(styleLoaded, stops, userLatLng, stopsFingerprint, nearbyMarkersOnly) {
        if (!styleLoaded) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (initialCameraDoneForRoute) return@LaunchedEffect

        if (stops.isEmpty()) {
            // Manual route map (no line loaded yet): still zoom to user like the FAB does.
            val ul = userLatLng
            if (ul != null) {
                map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(ul.first, ul.second), 14.0),
                    500,
                )
                initialCameraDoneForRoute = true
                return@LaunchedEffect
            }
            delay(700)
            if (initialCameraDoneForRoute) return@LaunchedEffect
            userLatLngState.value?.let { late ->
                map.easeCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(late.first, late.second), 14.0),
                    500,
                )
            }
            initialCameraDoneForRoute = true
            return@LaunchedEffect
        }

        if (nearbyMarkersOnly) {
            fitCameraToStopsAndUser(map, stops, userLatLng ?: userLatLngState.value)
            initialCameraDoneForRoute = true
            return@LaunchedEffect
        }

        // OASA route loaded: frame the full line, not the user's location (FAB still fits both).
        fitCameraToRoute(map, stops)
        initialCameraDoneForRoute = true
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}

private fun ensureRouteLayers(style: Style) {
    if (style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                ROUTE_SOURCE_ID,
                FeatureCollection.fromFeatures(emptyArray()),
            ),
        )
    }
    if (style.getLayer(ROUTE_LINE_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.rgb(76, 175, 80)),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Expression.literal("round")),
                PropertyFactory.lineJoin(Expression.literal("round")),
            ),
        )
    }
}

private fun ensureStopLayers(style: Style) {
    if (style.getSourceAs<GeoJsonSource>(STOPS_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                STOPS_SOURCE_ID,
                FeatureCollection.fromFeatures(emptyArray()),
            ),
        )
    }
    for ((kind, color, radius) in STOP_KINDS) {
        val layerId = "$STOPS_CIRCLE_PREFIX$kind"
        if (style.getLayer(layerId) == null) {
            style.addLayer(
                CircleLayer(layerId, STOPS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(radius),
                    PropertyFactory.circleColor(color),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(
                    Expression.eq(Expression.get(PROP_KIND), Expression.literal(kind)),
                ),
            )
        }
    }
    if (style.getLayer(STOPS_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(STOPS_LABEL_LAYER_ID, STOPS_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get(PROP_SEQ)),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(Color.WHITE),
                PropertyFactory.textHaloColor(Color.BLACK),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ).withFilter(
                Expression.any(
                    Expression.eq(Expression.get(PROP_KIND), Expression.literal("start")),
                    Expression.eq(Expression.get(PROP_KIND), Expression.literal("mid")),
                    Expression.eq(Expression.get(PROP_KIND), Expression.literal("end")),
                ),
            ),
        )
    }
    if (style.getLayer(STOPS_NAME_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(STOPS_NAME_LAYER_ID, STOPS_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get(PROP_STOP_NAME)),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(Color.rgb(33, 33, 33)),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAnchor(Expression.literal("top")),
                PropertyFactory.textOffset(arrayOf(0f, 1.35f)),
                PropertyFactory.textMaxWidth(14f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ).withFilter(
                Expression.eq(Expression.get(PROP_KIND), Expression.literal("nearby")),
            ),
        )
    }
}

private fun ensureBusLayers(style: Style) {
    if (style.getSourceAs<GeoJsonSource>(BUSES_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                BUSES_SOURCE_ID,
                FeatureCollection.fromFeatures(emptyArray()),
            ),
        )
    }
    if (style.getLayer(BUSES_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(BUSES_LAYER_ID, BUSES_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(BUS_ICON_ID),
                PropertyFactory.iconSize(1f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Expression.literal("map")),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get(PROP_BEARING))),
            ),
        )
    }
}

private fun ensureUserLocationLayer(style: Style) {
    if (style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                USER_LOCATION_SOURCE_ID,
                FeatureCollection.fromFeatures(emptyArray()),
            ),
        )
    }
    if (style.getLayer(USER_LOCATION_LAYER_ID) == null) {
        val dot = CircleLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Color.rgb(66, 133, 244)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        )
        if (style.getLayer(BUSES_LAYER_ID) != null) {
            style.addLayerAbove(dot, BUSES_LAYER_ID)
        } else {
            style.addLayer(dot)
        }
    }
}

private fun userLocationFeatureCollection(lat: Double, lng: Double): FeatureCollection {
    val feature = Feature.fromGeometry(Point.fromLngLat(lng, lat))
    return FeatureCollection.fromFeatures(arrayOf(feature))
}

private fun routeFeatureCollection(stops: List<RouteStop>): FeatureCollection {
    if (stops.size < 2) {
        return FeatureCollection.fromFeatures(emptyArray())
    }
    val sorted = stops.sortedBy { it.order }
    val points = sorted.map { Point.fromLngLat(it.lng, it.lat) }
    val line = LineString.fromLngLats(points)
    return FeatureCollection.fromFeature(Feature.fromGeometry(line))
}

private fun stopsFeatureCollection(
    stops: List<RouteStop>,
    nearbyMarkersOnly: Boolean = false,
): FeatureCollection {
    if (stops.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val sorted = stops.sortedBy { it.order }
    val last = sorted.lastIndex
    val features = sorted.mapIndexed { index, s ->
        val kind = if (nearbyMarkersOnly) {
            "nearby"
        } else when (index) {
            0 -> "start"
            last -> "end"
            else -> "mid"
        }
        val props = JsonObject()
        props.add(PROP_STOP_CODE, JsonPrimitive(s.stopCode))
        props.add(PROP_SEQ, JsonPrimitive((index + 1).toString()))
        props.add(PROP_KIND, JsonPrimitive(kind))
        if (nearbyMarkersOnly) {
            props.add(
                PROP_STOP_NAME,
                JsonPrimitive(truncateStopMapLabel(s.description.ifBlank { s.stopCode })),
            )
        }
        Feature.fromGeometry(Point.fromLngLat(s.lng, s.lat), props)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

private fun truncateStopMapLabel(raw: String, maxChars: Int = 28): String {
    val t = raw.trim()
    if (t.length <= maxChars) return t
    return t.take(maxChars - 1).trimEnd() + "…"
}

private fun busFeatureCollection(buses: List<BusOnRoute>, stops: List<RouteStop>): FeatureCollection {
    if (buses.isEmpty()) {
        return FeatureCollection.fromFeatures(emptyArray())
    }
    val features = buses.map { bus ->
        val bearing = computeBusHeading(bus, stops)
        val props = JsonObject()
        props.add(PROP_VEH, JsonPrimitive(bus.vehicleNo))
        props.add(PROP_BEARING, JsonPrimitive(bearing))
        Feature.fromGeometry(Point.fromLngLat(bus.lng, bus.lat), props)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

private fun computeBusHeading(bus: BusOnRoute, stops: List<RouteStop>): Double {
    val sorted = stops.sortedBy { it.order }
    if (sorted.size < 2) return 0.0
    var closest = 0
    var best = Double.MAX_VALUE
    sorted.forEachIndexed { i, s ->
        val d = haversineMeters(bus.lat, bus.lng, s.lat, s.lng)
        if (d < best) {
            best = d
            closest = i
        }
    }
    return when {
        closest < sorted.lastIndex ->
            bearingDegrees(bus.lat, bus.lng, sorted[closest + 1].lat, sorted[closest + 1].lng)
        else ->
            bearingDegrees(
                sorted[closest - 1].lat,
                sorted[closest - 1].lng,
                sorted[closest].lat,
                sorted[closest].lng,
            )
    }
}

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

private fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δλ = Math.toRadians(lng2 - lng1)
    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    val θ = atan2(y, x)
    return (Math.toDegrees(θ) + 360.0) % 360.0
}

/**
 * Slim teardrop / kite glyph: long pointed nose, short notched tail. Keeps the heading obvious
 * during turns without dominating the map at the route stops' scale.
 */
private fun createBusArrowBitmap(sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 193, 7)
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.07f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(27, 27, 27)
    }
    val w = sizePx.toFloat()
    val h = sizePx.toFloat()
    val cx = w / 2f
    val path = Path().apply {
        moveTo(cx, h * 0.08f)
        lineTo(w * 0.78f, h * 0.92f)
        lineTo(cx, h * 0.72f)
        lineTo(w * 0.22f, h * 0.92f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bmp
}

private fun fitCameraToRoute(map: MapLibreMap, stops: List<RouteStop>) {
    val sorted = stops.sortedBy { it.order }
    if (sorted.isEmpty()) return
    val builder = LatLngBounds.Builder()
    sorted.forEach { builder.include(LatLng(it.lat, it.lng)) }
    val bounds = builder.build()
    try {
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 80),
            600,
        )
    } catch (_: Exception) {
        val center = sorted[sorted.size / 2]
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(center.lat, center.lng), 12.0),
            400,
        )
    }
}

private fun fitCameraToStopsAndUser(
    map: MapLibreMap,
    stops: List<RouteStop>,
    userLatLng: Pair<Double, Double>?,
    paddingPx: Int = 88,
) {
    val sorted = stops.sortedBy { it.order }
    if (sorted.isEmpty()) return
    val builder = LatLngBounds.Builder()
    sorted.forEach { builder.include(LatLng(it.lat, it.lng)) }
    userLatLng?.let { builder.include(LatLng(it.first, it.second)) }
    try {
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx),
            550,
        )
    } catch (_: Exception) {
        fitCameraToRoute(map, stops)
    }
}
