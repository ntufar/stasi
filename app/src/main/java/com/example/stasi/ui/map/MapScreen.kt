package com.example.stasi.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stasi.data.repository.BusOnRoute
import com.example.stasi.data.repository.RouteStop
import com.example.stasi.di.LocalAppContainer
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URI = "https://demotiles.maplibre.org/style.json"
private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LINE_LAYER_ID = "route-line"
private const val BUSES_SOURCE_ID = "buses-source"
private const val BUSES_LAYER_ID = "buses-layer"
private const val PROP_VEH = "vehicleNo"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    presetRouteCode: String?,
    onBack: (() -> Unit)?,
) {
    val container = LocalAppContainer.current
    val vm: MapViewModel = viewModel(
        key = presetRouteCode ?: "manual",
        factory = remember(presetRouteCode, container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MapViewModel(container.oasaRepository, presetRouteCode) as T
            }
        },
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Χάρτης διαδρομής") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                OutlinedTextField(
                    value = uiState.routeCodeInput,
                    onValueChange = vm::onRouteCodeInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Κωδικός διαδρομής") },
                    singleLine = true,
                    trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                FilledTonalButton(
                    onClick = vm::applyRouteCode,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .align(Alignment.End),
                ) {
                    Text("Εμφάνιση")
                }
            }
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.CenterHorizontally),
                )
            }
            uiState.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            StasiMapLibre(
                stops = uiState.stops,
                buses = uiState.buses,
                onBusVehicleSelected = vm::selectVehicle,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    uiState.selectedVehicleNo?.let { veh ->
        AlertDialog(
            onDismissRequest = vm::dismissVehicleInfo,
            confirmButton = {
                TextButton(onClick = vm::dismissVehicleInfo) {
                    Text("OK")
                }
            },
            title = { Text("Όχημα") },
            text = { Text("Αριθμός οχήματος: $veh") },
        )
    }
}

@Composable
private fun StasiMapLibre(
    stops: List<RouteStop>,
    buses: List<BusOnRoute>,
    onBusVehicleSelected: (String) -> Unit,
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

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                ensureRouteLayers(style)
                ensureBusLayers(style)
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val features = map.queryRenderedFeatures(screenPoint, BUSES_LAYER_ID)
                    val veh = features.firstOrNull()?.getStringProperty(PROP_VEH)
                    if (veh != null) {
                        onBusSelected(veh)
                        true
                    } else {
                        false
                    }
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

    LaunchedEffect(styleLoaded, stops, buses) {
        if (!styleLoaded) return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect
        val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        val busSource = style.getSourceAs<GeoJsonSource>(BUSES_SOURCE_ID)
        routeSource?.setGeoJson(routeFeatureCollection(stops))
        busSource?.setGeoJson(busFeatureCollection(buses))
    }

    LaunchedEffect(styleLoaded, stops) {
        if (!styleLoaded) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (stops.isEmpty()) return@LaunchedEffect
        fitCameraToRoute(map, stops)
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
                PropertyFactory.lineColor(android.graphics.Color.rgb(76, 175, 80)),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Expression.literal("round")),
                PropertyFactory.lineJoin(Expression.literal("round")),
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
            CircleLayer(BUSES_LAYER_ID, BUSES_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(
                    android.graphics.Color.rgb(255, 193, 7),
                ),
                PropertyFactory.circleStrokeColor(
                    android.graphics.Color.rgb(27, 27, 27),
                ),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }
}

private fun routeFeatureCollection(stops: List<RouteStop>): FeatureCollection {
    if (stops.size < 2) {
        return FeatureCollection.fromFeatures(emptyArray())
    }
    val points = stops.map { Point.fromLngLat(it.lng, it.lat) }
    val line = LineString.fromLngLats(points)
    return FeatureCollection.fromFeature(Feature.fromGeometry(line))
}

private fun busFeatureCollection(buses: List<BusOnRoute>): FeatureCollection {
    if (buses.isEmpty()) {
        return FeatureCollection.fromFeatures(emptyArray())
    }
    val features = buses.map { bus ->
        val props = JsonObject()
        props.add(PROP_VEH, JsonPrimitive(bus.vehicleNo))
        Feature.fromGeometry(Point.fromLngLat(bus.lng, bus.lat), props)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

private fun fitCameraToRoute(map: MapLibreMap, stops: List<RouteStop>) {
    val builder = LatLngBounds.Builder()
    stops.forEach { builder.include(LatLng(it.lat, it.lng)) }
    val bounds = builder.build()
    try {
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 80),
            600,
        )
    } catch (_: Exception) {
        val center = stops[stops.size / 2]
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(center.lat, center.lng), 12.0),
            400,
        )
    }
}
