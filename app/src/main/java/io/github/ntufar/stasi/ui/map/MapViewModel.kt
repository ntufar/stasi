package io.github.ntufar.stasi.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.stasi.data.repository.BusOnRoute
import io.github.ntufar.stasi.data.repository.LineRouteInfo
import io.github.ntufar.stasi.data.repository.OasaRepository
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
)

class MapViewModel(
    private val repository: OasaRepository,
    private val presetRouteCode: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var routeJob: Job? = null

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
            )
        }
        startRouteJob(code, refreshLineInfo = true)
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
            )
        }
        startRouteJob(target, refreshLineInfo = false)
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
            _uiState.update { s ->
                s.copy(isLoading = true, error = null, buses = emptyList())
            }
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
                            error = "Λήξη χρόνου· ελέγξτε το δίκτυο ή δοκιμάστε ξανά.",
                        )
                    }
                    return@launch
                }
                if (fetch.stops.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stops = emptyList(),
                            error = "Δεν βρέθηκαν στάσεις για τη διαδρομή $code.",
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
                    if (info != null) {
                        _uiState.update {
                            it.copy(
                                lineLabel = info.lineId.ifBlank { info.lineCode },
                                lineDescr = info.lineDescr.ifBlank { null },
                                directions = info.directions,
                            )
                        }
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
                        error = e.message ?: "Σφάλμα φόρτωσης διαδρομής.",
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
        super.onCleared()
    }

    companion object {
        const val DEFAULT_ROUTE_CODE = "2045"
        /** Background poll cadence for buses + route stops while the map is open. */
        private const val LIVE_REFRESH_MS = 15_000L
        private const val MAP_FETCH_TIMEOUT_MS = 50_000L
    }
}
