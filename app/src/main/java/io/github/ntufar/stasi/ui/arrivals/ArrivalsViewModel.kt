package io.github.ntufar.stasi.ui.arrivals

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.WorkManager
import io.github.ntufar.stasi.data.repository.AlertsRepository
import io.github.ntufar.stasi.data.repository.ArrivalDetail
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.workers.ArrivalAlertWorker
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
    val activeAlertKeys: Set<String> = emptySet(),
)

class ArrivalsViewModel(
    val stopCode: String,
    private val repository: OasaRepository,
    private val favoritesRepository: FavoritesRepository,
    private val alertsRepository: AlertsRepository,
    private val appContext: Context,
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
        viewModelScope.launch {
            alertsRepository.activeAlerts.collect { alerts ->
                val keys = alerts
                    .filter { it.stopCode == stopCode }
                    .map { "${it.routeCode}:${it.vehCode}" }
                    .toSet()
                _uiState.update { it.copy(activeAlertKeys = keys) }
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val favNow = favoritesRepository.toggleFavorite(stopCode)
            _uiState.update { it.copy(isFavorite = favNow) }
        }
    }

    fun toggleAlert(routeCode: String, vehCode: String, lineLabel: String) {
        viewModelScope.launch {
            if (alertsRepository.isAlertActive(stopCode, routeCode, vehCode)) {
                alertsRepository.removeAlert(stopCode, routeCode, vehCode)
                WorkManager.getInstance(appContext)
                    .cancelUniqueWork(ArrivalAlertWorker.uniqueWorkName(stopCode, routeCode, vehCode))
            } else {
                alertsRepository.addAlert(stopCode, routeCode, vehCode)
                val request = OneTimeWorkRequestBuilder<ArrivalAlertWorker>()
                    .setInputData(workDataOf(
                        ArrivalAlertWorker.KEY_STOP_CODE to stopCode,
                        ArrivalAlertWorker.KEY_ROUTE_CODE to routeCode,
                        ArrivalAlertWorker.KEY_VEH_CODE to vehCode,
                        ArrivalAlertWorker.KEY_LINE_LABEL to lineLabel,
                        ArrivalAlertWorker.KEY_STOP_TITLE to _uiState.value.title.ifBlank { stopCode },
                    ))
                    .build()
                WorkManager.getInstance(appContext)
                    .enqueueUniqueWork(
                        ArrivalAlertWorker.uniqueWorkName(stopCode, routeCode, vehCode),
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
            }
        }
    }

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
