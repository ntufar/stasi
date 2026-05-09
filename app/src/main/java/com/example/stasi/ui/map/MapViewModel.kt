package com.example.stasi.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stasi.data.repository.BusOnRoute
import com.example.stasi.data.repository.OasaRepository
import com.example.stasi.BuildConfig
import com.example.stasi.data.repository.RouteStop
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
            )
        }
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
                while (isActive) {
                    val buses = try {
                        withContext(Dispatchers.IO) {
                            repository.getBusesOnRoute(routeForLive)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    _uiState.update { it.copy(buses = buses) }
                    delay(BUS_REFRESH_MS)
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

    override fun onCleared() {
        routeJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_ROUTE_CODE = "2045"
        private const val BUS_REFRESH_MS = 15_000L
        private const val MAP_FETCH_TIMEOUT_MS = 50_000L
    }
}
