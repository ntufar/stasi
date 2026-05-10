package io.github.ntufar.stasi.ui.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.stasi.R
import io.github.ntufar.stasi.data.repository.BusOnRoute
import io.github.ntufar.stasi.data.repository.LineRouteInfo
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.data.repository.RouteDailyTimetable
import io.github.ntufar.stasi.BuildConfig
import io.github.ntufar.stasi.data.repository.RouteDirection
import io.github.ntufar.stasi.data.repository.RouteStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class MapUiState(
    val manualMode: Boolean = false,
    val routeCodeInput: String = "",
    val appliedRouteCode: String = "",
    val stops: List<RouteStop> = emptyList(),
    val buses: List<BusOnRoute> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedVehicleNo: String? = null,
    /** Public-facing line number (e.g., "750"), shown in the top bar title. */
    val lineLabel: String? = null,
    /** Optional human-readable line description (e.g., "ΛΕΩΦ. ΣΤΑΥΡΟΥ - ΤΕΧΝΟΠΟΛΗ"). */
    val lineDescr: String? = null,
    /** All known directions (routes) for the current line. */
    val directions: List<RouteDirection> = emptyList(),
    /** Internal OASA line code for timetable API ([getDailySchedule]); not the public line number. */
    val internalLineCode: String? = null,
    /** 0 = map, 1 = timetable (only when route stops are shown). */
    val routeTabIndex: Int = 0,
    val timetable: RouteDailyTimetable? = null,
    val timetableLoading: Boolean = false,
    val timetableError: String? = null,
    /**
     * Closest stops around the user on the manual route map when no line is loaded yet
     * ([getClosestStops]); cleared when a route is shown.
     */
    val nearbyStopsForMap: List<RouteStop> = emptyList(),
)

class MapViewModel(
    application: Application,
    private val repository: OasaRepository,
    private val presetRouteCode: String?,
) : AndroidViewModel(application) {

    private fun str(resId: Int): String = getApplication<Application>().getString(resId)
    private fun str(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var routeJob: Job? = null
    private var timetableJob: Job? = null
    private var lastTimetableLineCode: String? = null

    init {
        if (presetRouteCode != null) {
            val code = presetRouteCode.trim()
            _uiState.update {
                it.copy(
                    manualMode = false,
                    routeCodeInput = code,
                    appliedRouteCode = code,
                )
            }
            applyRouteCode()
        } else {
            _uiState.update {
                it.copy(
                    manualMode = true,
                    routeCodeInput = "",
                    appliedRouteCode = "",
                )
            }
        }
    }

    fun onRouteCodeInputChange(value: String) {
        _uiState.update { it.copy(routeCodeInput = value) }
    }

    fun applyRouteCode() {
        val code = _uiState.value.routeCodeInput.trim().ifBlank { DEFAULT_ROUTE_CODE }
        _uiState.update {
            it.copy(
                routeCodeInput = code,
                appliedRouteCode = code,
                selectedVehicleNo = null,
                lineLabel = null,
                lineDescr = null,
                directions = emptyList(),
                internalLineCode = null,
                routeTabIndex = 0,
                timetable = null,
                timetableError = null,
                timetableLoading = false,
                nearbyStopsForMap = emptyList(),
            )
        }
        lastTimetableLineCode = null
        startRouteJob(code, refreshLineInfo = true)
    }

    /** Loads closest stops for the manual map (no route); no-op if a route is already shown. */
    fun refreshNearbyForManualMap(lat: Double, lng: Double) {
        val s = _uiState.value
        if (!s.manualMode || s.stops.isNotEmpty() || s.isLoading) return
        viewModelScope.launch {
            val current = _uiState.value
            if (!current.manualMode || current.stops.isNotEmpty() || current.isLoading) return@launch
            val nearby = try {
                withContext(Dispatchers.IO) {
                    repository.getClosestStops(lat, lng)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val asRoute = nearby
                .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                .take(20)
                .mapIndexed { idx, n ->
                    RouteStop(
                        stopCode = n.stopCode,
                        description = n.description,
                        lat = n.lat,
                        lng = n.lng,
                        order = idx + 1,
                    )
                }
            _uiState.update { st ->
                if (!st.manualMode || st.stops.isNotEmpty() || st.isLoading) st
                else st.copy(nearbyStopsForMap = asRoute)
            }
        }
    }

    /**
     * Switch the map to another direction (route) of the same line. Resolves stops + buses for
     * the picked [routeCode] but keeps the cached line label / directions list so the title and
     * toggle stay stable while the data refreshes.
     */
    fun selectDirection(routeCode: String) {
        val target = routeCode.trim()
        if (target.isEmpty()) return
        if (target == _uiState.value.appliedRouteCode) return
        _uiState.update {
            it.copy(
                appliedRouteCode = target,
                routeCodeInput = target,
                selectedVehicleNo = null,
                routeTabIndex = 0,
                timetable = null,
                timetableError = null,
                timetableLoading = false,
            )
        }
        lastTimetableLineCode = null
        startRouteJob(target, refreshLineInfo = false)
    }

    fun onRouteTabSelected(index: Int) {
        val idx = index.coerceIn(0, 1)
        _uiState.update { it.copy(routeTabIndex = idx) }
        if (idx == 1) {
            fetchTimetableIfNeeded()
        }
    }

    private fun fetchTimetableIfNeeded() {
        val line = _uiState.value.internalLineCode?.trim().orEmpty()
        if (line.isEmpty()) {
            _uiState.update {
                it.copy(
                    timetableLoading = false,
                    timetableError = str(R.string.map_error_line_code_missing),
                    timetable = null,
                )
            }
            return
        }
        if (line == lastTimetableLineCode && _uiState.value.timetable != null) return
        timetableJob?.cancel()
        timetableJob = viewModelScope.launch {
            _uiState.update { it.copy(timetableLoading = true, timetableError = null) }
            try {
                val t = withContext(Dispatchers.IO) {
                    repository.getRouteDailyTimetable(line)
                }
                lastTimetableLineCode = line
                val empty = t.originDepartures.isEmpty() && t.terminusDepartures.isEmpty()
                _uiState.update {
                    it.copy(
                        timetable = t,
                        timetableLoading = false,
                        timetableError = if (empty) {
                            str(R.string.map_error_timetable_empty)
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        timetableLoading = false,
                        timetable = null,
                        timetableError = e.message ?: str(R.string.map_error_timetable_load),
                    )
                }
            }
        }
    }

    fun toggleDirection() {
        val state = _uiState.value
        val dirs = state.directions
        if (dirs.size < 2) return
        val currentIndex = dirs.indexOfFirst { it.routeCode == state.appliedRouteCode }
            .takeIf { it >= 0 } ?: 0
        val next = dirs[(currentIndex + 1) % dirs.size]
        selectDirection(next.routeCode)
    }

    private fun startRouteJob(code: String, refreshLineInfo: Boolean) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            val keepLineMeta = !refreshLineInfo && _uiState.value.directions.isNotEmpty()
            val preservedLineCode =
                if (keepLineMeta) _uiState.value.internalLineCode else null
            _uiState.update { s ->
                s.copy(
                    isLoading = true,
                    error = null,
                    buses = emptyList(),
                    routeTabIndex = 0,
                    timetable = null,
                    timetableError = null,
                    timetableLoading = false,
                    internalLineCode = preservedLineCode,
                    nearbyStopsForMap = emptyList(),
                )
            }
            lastTimetableLineCode = null
            try {
                if (BuildConfig.DEBUG) {
                    Log.d("StasiMap", "fetch route stops for \"$code\"")
                }
                val fetch = withTimeoutOrNull(MAP_FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        repository.getRouteStops(code)
                    }
                }
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "StasiMap",
                        "fetch done: timeout=${fetch == null} stops=${fetch?.stops?.size ?: 0}",
                    )
                }
                if (fetch == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stops = emptyList(),
                            error = str(R.string.map_error_timeout),
                        )
                    }
                    return@launch
                }
                if (fetch.stops.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stops = emptyList(),
                            error = str(R.string.map_error_no_stops, code),
                        )
                    }
                    return@launch
                }
                val routeForLive = fetch.effectiveRouteCode
                _uiState.update {
                    it.copy(
                        stops = fetch.stops,
                        appliedRouteCode = routeForLive,
                        isLoading = false,
                        error = null,
                        nearbyStopsForMap = emptyList(),
                    )
                }
                if (refreshLineInfo || _uiState.value.directions.isEmpty()) {
                    val hintStop = fetch.stops.firstOrNull()?.stopCode
                    val info: LineRouteInfo? = try {
                        withContext(Dispatchers.IO) {
                            repository.getLineRouteInfoForRoute(routeForLive, hintStop)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val lc = info?.lineCode?.trim().orEmpty().ifBlank { null }
                    val fallbackLc = if (lc == null) {
                        try {
                            withContext(Dispatchers.IO) {
                                repository.lineCodeForRoute(routeForLive)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    val resolvedLineCode = lc ?: fallbackLc
                    if (info != null) {
                        _uiState.update {
                            it.copy(
                                lineLabel = info.lineId.ifBlank { info.lineCode },
                                lineDescr = info.lineDescr.ifBlank { null },
                                directions = info.directions,
                                internalLineCode = resolvedLineCode,
                            )
                        }
                    } else if (resolvedLineCode != null) {
                        _uiState.update { it.copy(internalLineCode = resolvedLineCode) }
                    }
                }
                while (isActive) {
                    fetchLiveData(routeForLive)
                    delay(LIVE_REFRESH_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        stops = emptyList(),
                        error = e.message ?: str(R.string.map_error_route_load),
                    )
                }
            }
        }
    }

    fun dismissVehicleInfo() {
        _uiState.update { it.copy(selectedVehicleNo = null) }
    }

    fun selectVehicle(no: String?) {
        _uiState.update { it.copy(selectedVehicleNo = no) }
    }

    /**
     * Out-of-cycle refresh of buses + route stops. Triggered by `MapScreen` when the screen
     * returns to RESUMED so the map shows fresh data immediately after the user navigates back
     * to it, instead of waiting up to [LIVE_REFRESH_MS] for the next tick.
     */
    fun refreshNow() {
        val state = _uiState.value
        val routeCode = state.appliedRouteCode
        if (routeCode.isBlank() || state.stops.isEmpty()) return
        viewModelScope.launch {
            fetchLiveData(routeCode)
        }
    }

    private suspend fun fetchLiveData(routeCode: String) {
        val buses = try {
            withContext(Dispatchers.IO) {
                repository.getBusesOnRoute(routeCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(buses = buses) }

        val refreshed = try {
            withContext(Dispatchers.IO) {
                repository.getRouteStops(routeCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val refreshedStops = refreshed?.stops
        if (!refreshedStops.isNullOrEmpty()) {
            _uiState.update { state ->
                if (state.stops == refreshedStops) state
                else state.copy(stops = refreshedStops)
            }
        }
    }

    override fun onCleared() {
        routeJob?.cancel()
        timetableJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_ROUTE_CODE = "2045"
        /** Background poll cadence for buses + route stops while the map is open. */
        private const val LIVE_REFRESH_MS = 15_000L
        private const val MAP_FETCH_TIMEOUT_MS = 50_000L
    }
}
