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
            val arrivals = repository.enrichArrivalsWithOriginBoardings(stopCode, raw)
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
