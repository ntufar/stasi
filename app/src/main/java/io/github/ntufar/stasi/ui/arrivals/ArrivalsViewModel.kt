package io.github.ntufar.stasi.ui.arrivals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.stasi.data.repository.ArrivalDetail
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.OasaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ArrivalsUiState(
    val stopCode: String,
    val title: String = "",
    val arrivals: List<ArrivalDetail> = emptyList(),
    val isFavorite: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class ArrivalsViewModel(
    val stopCode: String,
    private val repository: OasaRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArrivalsUiState(stopCode = stopCode))
    val uiState: StateFlow<ArrivalsUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val favNow = favoritesRepository.toggleFavorite(stopCode)
            _uiState.update { it.copy(isFavorite = favNow) }
        }
    }

    /**
     * Out-of-cycle refresh used by `ArrivalsScreen` when the screen returns to RESUMED, so
     * arrivals appear up-to-date the moment the user navigates back instead of waiting up to
     * [POLL_INTERVAL_MS] for the next tick.
     */
    fun refreshNow() {
        viewModelScope.launch {
            fetchOnce()
        }
    }

    private suspend fun fetchOnce() {
        runCatching {
            val title = repository.getStopLabel(stopCode)
            val raw = repository.getStopArrivals(stopCode).sortedBy { it.minutes }
            val withSchedule = repository.enrichArrivalsWithOriginSchedule(stopCode, raw)
            val withBoardings = repository.enrichArrivalsWithOriginBoardings(stopCode, withSchedule)
            val arrivals = repository.enrichArrivalsWithLastBusWarning(withBoardings)
            val fav = favoritesRepository.isFavorite(stopCode)
            _uiState.update {
                it.copy(
                    title = title,
                    arrivals = arrivals,
                    isFavorite = fav,
                    isLoading = false,
                    error = null,
                )
            }
        }.onFailure { e ->
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
    }
}

/**
 * Flat list for the arrivals screen: live predictions plus, when applicable, a **separate** row for
 * the next scheduled origin departure (not nested under a specific vehicle row).
 */
sealed class ArrivalListRow {
    data class Live(val detail: ArrivalDetail) : ArrivalListRow()

    data class ScheduledOriginDeparture(
        val routeCode: String,
        val lineLabel: String,
        val originStopDescription: String?,
        val clock: String,
        val minutesUntil: Int,
    ) : ArrivalListRow()
}

internal fun buildArrivalListRows(arrivals: List<ArrivalDetail>): List<ArrivalListRow> {
    if (arrivals.isEmpty()) return emptyList()
    val scheduleEmitted = mutableSetOf<String>()
    val out = ArrayList<ArrivalListRow>(arrivals.size + 2)
    for (a in arrivals) {
        val scheduleRow = a.originScheduleClock?.takeIf { it.isNotBlank() } != null
        val live = if (scheduleRow) {
            a.copy(
                originScheduleClock = null,
                originDepartureMinutes = null,
                originStopDescription = null,
            )
        } else {
            a
        }
        out.add(ArrivalListRow.Live(live))
        if (scheduleRow && a.routeCode.isNotBlank() && a.routeCode !in scheduleEmitted) {
            scheduleEmitted.add(a.routeCode)
            out.add(
                ArrivalListRow.ScheduledOriginDeparture(
                    routeCode = a.routeCode,
                    lineLabel = a.lineLabel,
                    originStopDescription = a.originStopDescription,
                    clock = a.originScheduleClock!!.trim(),
                    minutesUntil = a.originDepartureMinutes ?: 999,
                ),
            )
        }
    }
    return out
}
