package com.example.stasi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stasi.data.repository.ArrivalDetail
import com.example.stasi.data.repository.FavoritesRepository
import com.example.stasi.data.repository.NearbyStop
import com.example.stasi.data.repository.OasaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoriteStopCard(
    val stopCode: String,
    val title: String,
    val arrivals: List<ArrivalDetail>,
)

data class HomeUiState(
    val favoriteCards: List<FavoriteStopCard> = emptyList(),
    val nearby: List<NearbyStop> = emptyList(),
    val isLoading: Boolean = false,
    val nearbyError: String? = null,
)

class HomeViewModel(
    private val repository: OasaRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val favorites: StateFlow<Set<String>> = favoritesRepository.favoriteStopCodes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptySet(),
    )

    init {
        viewModelScope.launch {
            combine(
                favoritesRepository.favoriteStopCodes,
                tickerFlow(),
            ) { favs, _ -> favs }
                .collect { refreshFavorites(it) }
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

    private suspend fun refreshFavorites(codes: Set<String>) {
        if (codes.isEmpty()) {
            _uiState.update { it.copy(favoriteCards = emptyList(), isLoading = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        val cards = codes.map { code ->
            val title = repository.getStopLabel(code)
            val arrivals = repository.getStopArrivals(code).sortedBy { it.minutes }.take(2)
            FavoriteStopCard(code, title, arrivals)
        }
        _uiState.update { it.copy(favoriteCards = cards, isLoading = false) }
    }
}
