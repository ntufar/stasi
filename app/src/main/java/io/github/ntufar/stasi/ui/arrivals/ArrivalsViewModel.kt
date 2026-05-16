package io.github.ntufar.stasi.ui.arrivals

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import io.github.ntufar.stasi.R
import io.github.ntufar.stasi.data.repository.AlertsRepository
import io.github.ntufar.stasi.data.repository.ArrivalDetail
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.data.repository.RecentActivityRepository
import io.github.ntufar.stasi.workers.ArrivalAlertWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val lastUpdatedMillis: Long? = null,
    val isFavorite: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val activeAlertKeys: Set<String> = emptySet(),
)

class ArrivalsViewModel(
    val stopCode: String,
    private val repository: OasaRepository,
    private val favoritesRepository: FavoritesRepository,
    private val alertsRepository: AlertsRepository,
    private val recentActivityRepository: RecentActivityRepository,
    private val appContext: Context,
    private val routeCodeHint: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArrivalsUiState(stopCode = stopCode))
    val uiState: StateFlow<ArrivalsUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            recentActivityRepository.recordStopVisit(stopCode)
        }
        viewModelScope.launch {
            runCatching { repository.getStopLabel(stopCode) }
                .onSuccess { label ->
                    if (label.isNotBlank()) {
                        _uiState.update { it.copy(title = label) }
                    }
                }
        }
        pollJob = viewModelScope.launch {
            fetchOnce(forceRefresh = true)
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // Always bypass the short Room cache so open Arrivals keeps polling the API; stale
                // cache + StateFlow structural equality could otherwise leave the UI unchanged for
                // long stretches when the operator repeats the same ETA integer.
                fetchOnce(forceRefresh = true)
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
                ArrivalAlertWorker.schedule(
                    context = appContext,
                    stopCode = stopCode,
                    routeCode = routeCode,
                    vehCode = vehCode,
                    lineLabel = lineLabel,
                    stopTitle = _uiState.value.title.ifBlank { stopCode },
                )
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            fetchOnce(forceRefresh = true)
        }
    }

    /** User pull-to-refresh: same fetch as [refreshNow] but drives the Material pull indicator until complete. */
    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                fetchOnce(forceRefresh = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun fetchOnce(forceRefresh: Boolean) {
        runCatching {
            val title = repository.getStopLabel(stopCode)
            if (forceRefresh && _uiState.value.arrivals.isEmpty()) {
                val cached = repository.getStopArrivalsSnapshot(stopCode, forceRefresh = false)
                if (cached.arrivals.isNotEmpty()) {
                    publishBasicArrivals(
                        title = title,
                        arrivals = cached.arrivals.sortedBy { it.minutes },
                        lastUpdatedMillis = cached.fetchedAtMillis,
                    )
                }
            }
            val snapshot = repository.getStopArrivalsSnapshot(stopCode, forceRefresh = forceRefresh)
            val raw = snapshot.arrivals.sortedBy { it.minutes }
            publishBasicArrivals(
                title = title,
                arrivals = raw,
                lastUpdatedMillis = snapshot.fetchedAtMillis,
            )
            val (siblingRoutes, enriched) = coroutineScope {
                val siblings = async { resolveSiblingRoutes() }
                val enrichedArrivals = async { repository.enrichStopArrivals(stopCode, raw, routeCodeHint) }
                siblings.await() to enrichedArrivals.await()
            }
            val arrivals = sortByRouteHint(enriched, siblingRoutes)
            _uiState.update {
                it.copy(
                    arrivals = arrivals,
                    error = null,
                )
            }
        }.onFailure {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = appContext.getString(R.string.arrivals_load_failed),
                )
            }
        }
    }

    private suspend fun publishBasicArrivals(
        title: String,
        arrivals: List<ArrivalDetail>,
        lastUpdatedMillis: Long?,
    ) {
        val fav = favoritesRepository.isFavorite(stopCode)
        _uiState.update {
            it.copy(
                title = title,
                arrivals = sortByRouteHint(arrivals, emptySet()),
                lastUpdatedMillis = lastUpdatedMillis,
                isFavorite = fav,
                isLoading = false,
                error = null,
            )
        }
    }

    private suspend fun resolveSiblingRoutes(): Set<String> {
        val hint = routeCodeHint ?: return emptySet()
        return try {
            val info = repository.getLineRouteInfoForRoute(hint)
            info?.directions?.map { it.routeCode }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun sortByRouteHint(
        arrivals: List<ArrivalDetail>,
        siblingRoutes: Set<String> = emptySet(),
    ): List<ArrivalDetail> {
        val hint = routeCodeHint ?: return arrivals
        return arrivals.sortedWith(
            compareBy<ArrivalDetail> {
                when (it.routeCode) {
                    hint -> 0
                    in siblingRoutes -> 1
                    else -> 2
                }
            }.thenBy { it.minutes },
        )
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
        val hasSchedule = a.originScheduleClock?.takeIf { it.isNotBlank() } != null
        if (a.isScheduleOnly && hasSchedule) {
            if (a.routeCode.isNotBlank() && a.routeCode !in scheduleEmitted) {
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
            continue
        }
        val live = if (hasSchedule) {
            a.copy(
                originScheduleClock = null,
                originDepartureMinutes = null,
                originStopDescription = null,
            )
        } else {
            a
        }
        out.add(ArrivalListRow.Live(live))
        if (hasSchedule && a.routeCode.isNotBlank() && a.routeCode !in scheduleEmitted) {
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
