package io.github.ntufar.stasi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.stasi.data.repository.ArrivalDetail
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.FavoriteStopEntry
import io.github.ntufar.stasi.data.repository.NearbyStop
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.data.repository.RecentActivityRepository
import io.github.ntufar.stasi.data.repository.RecentRouteVisit
import io.github.ntufar.stasi.data.repository.RecentStopVisit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoriteStopCard(
    val stopCode: String,
    val title: String,
    val alias: String? = null,
    val arrivals: List<ArrivalDetail>,
    val lastUpdatedMillis: Long? = null,
)

data class RecentShortcut(
    val code: String,
    val title: String,
    val subtitle: String? = null,
)

data class HomeUiState(
    val favoriteCards: List<FavoriteStopCard> = emptyList(),
    val recentStop: RecentShortcut? = null,
    val recentRoute: RecentShortcut? = null,
    val nearby: List<NearbyStop> = emptyList(),
    val isLoading: Boolean = false,
    val nearbyError: String? = null,
)

class HomeViewModel(
    private val repository: OasaRepository,
    private val favoritesRepository: FavoritesRepository,
    private val recentActivityRepository: RecentActivityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var latestFavoriteEntries: List<FavoriteStopEntry> = emptyList()

    init {
        viewModelScope.launch {
            combine(
                favoritesRepository.favoriteEntries,
                tickerFlow(),
            ) { favs, _ -> favs }
                .collect {
                    latestFavoriteEntries = it
                    refreshFavorites(it)
                }
        }
        viewModelScope.launch {
            recentActivityRepository.recentStopVisit.collect { visit ->
                refreshRecentStop(visit)
            }
        }
        viewModelScope.launch {
            recentActivityRepository.recentRouteVisit.collect { visit ->
                refreshRecentRoute(visit)
            }
        }
    }

    private fun tickerFlow() = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    fun refreshNearby(lat: Double, lng: Double) {
        viewModelScope.launch {
            _uiState.update { it.copy(nearbyError = null) }
            try {
                val list = repository.getClosestStops(lat, lng)
                    .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                    .take(12)
                _uiState.update { it.copy(nearby = list) }
            } catch (e: Exception) {
                _uiState.update { it.copy(nearbyError = e.message) }
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            refreshFavorites(latestFavoriteEntries)
        }
    }

    fun renameFavorite(stopCode: String, alias: String) {
        viewModelScope.launch {
            favoritesRepository.renameFavorite(stopCode, alias)
        }
    }

    fun moveFavorite(stopCode: String, delta: Int) {
        viewModelScope.launch {
            favoritesRepository.moveFavorite(stopCode, delta)
        }
    }

    fun removeFavorite(stopCode: String) {
        viewModelScope.launch {
            favoritesRepository.removeFavorite(stopCode)
        }
    }

    private suspend fun refreshFavorites(entries: List<FavoriteStopEntry>) {
        if (entries.isEmpty()) {
            _uiState.update { it.copy(favoriteCards = emptyList(), isLoading = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        val cards = entries.map { entry ->
            val code = entry.stopCode
            val title = repository.getStopLabel(code)
            val snapshot = repository.getStopArrivalsSnapshot(code)
            val arrivals = snapshot.arrivals.sortedBy { it.minutes }.take(2)
            FavoriteStopCard(
                stopCode = code,
                title = title,
                alias = entry.alias.takeIf { it.isNotBlank() },
                arrivals = arrivals,
                lastUpdatedMillis = snapshot.fetchedAtMillis,
            )
        }
        _uiState.update { it.copy(favoriteCards = cards, isLoading = false) }
    }

    private suspend fun refreshRecentStop(visit: RecentStopVisit?) {
        if (visit == null) {
            _uiState.update { it.copy(recentStop = null) }
            return
        }
        val title = repository.getStopLabel(visit.stopCode)
        _uiState.update {
            it.copy(
                recentStop = RecentShortcut(
                    code = visit.stopCode,
                    title = title,
                    subtitle = visit.stopCode,
                ),
            )
        }
    }

    private suspend fun refreshRecentRoute(visit: RecentRouteVisit?) {
        if (visit == null) {
            _uiState.update { it.copy(recentRoute = null) }
            return
        }
        val info = repository.getLineRouteInfoForRoute(visit.routeCode)
        val hasId = info?.lineId?.isNotBlank() == true
        val hasDescr = info?.lineDescr?.isNotBlank() == true
        val title = when {
            hasId && hasDescr -> "${info!!.lineId} · ${info.lineDescr}"
            hasId -> info!!.lineId
            hasDescr -> info!!.lineDescr
            else -> visit.routeCode
        }
        _uiState.update {
            it.copy(
                recentRoute = RecentShortcut(
                    code = visit.routeCode,
                    title = title,
                    subtitle = visit.routeCode,
                ),
            )
        }
    }
}
