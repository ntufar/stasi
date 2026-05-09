package io.github.ntufar.stasi.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.stasi.data.local.CachedLineEntity
import io.github.ntufar.stasi.data.local.CachedStopEntity
import io.github.ntufar.stasi.data.repository.OasaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

sealed interface LinesCatalogState {
    data object Loading : LinesCatalogState
    data object Ready : LinesCatalogState
    data object Unavailable : LinesCatalogState
}

data class SearchUiState(
    val query: String = "",
    val stops: List<CachedStopEntity> = emptyList(),
    val lines: List<CachedLineEntity> = emptyList(),
    val isSearching: Boolean = false,
    val linesCatalog: LinesCatalogState = LinesCatalogState.Loading,
    /** Non-null while resolving a line tap → map (shows spinner on that row). */
    val lineOpenInProgressForCode: String? = null,
)

class SearchViewModel(
    private val repository: OasaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private val lineOpenMutex = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.warmLinesCatalogIfEmpty()
            _uiState.update {
                it.copy(
                    linesCatalog = if (ok) LinesCatalogState.Ready else LinesCatalogState.Unavailable,
                )
            }
        }
    }

    fun retryLinesCatalog() {
        if (_uiState.value.linesCatalog == LinesCatalogState.Loading) return
        _uiState.update { it.copy(linesCatalog = LinesCatalogState.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.warmLinesCatalogIfEmpty()
            _uiState.update {
                it.copy(
                    linesCatalog = if (ok) LinesCatalogState.Ready else LinesCatalogState.Unavailable,
                )
            }
        }
    }

    fun onQueryChange(raw: String) {
        _uiState.update { it.copy(query = raw) }
        searchJob?.cancel()
        val q = raw.trim()
        if (q.length < 2) {
            _uiState.update { it.copy(stops = emptyList(), lines = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            if (_uiState.value.query.trim() != raw.trim()) return@launch
            _uiState.update { it.copy(isSearching = true) }
            val stops = repository.searchStops(q)
            val lines = repository.searchLines(q)
            _uiState.update { it.copy(stops = stops, lines = lines, isSearching = false) }
        }
    }

    /**
     * Resolves a line to a route and invokes [onRouteResolved] on the main thread.
     * Ignores further taps while a resolution is already in progress (single-flight).
     */
    fun openLineOnMap(
        lineCode: String,
        onRouteResolved: (routeCode: String?) -> Unit,
    ) {
        viewModelScope.launch {
            if (!lineOpenMutex.tryLock()) return@launch
            try {
                _uiState.update { it.copy(lineOpenInProgressForCode = lineCode) }
                val route = withContext(Dispatchers.IO) {
                    repository.primaryRouteCodeForLine(lineCode)
                }.takeUnless { it.isNullOrBlank() }
                onRouteResolved(route)
            } finally {
                _uiState.update { it.copy(lineOpenInProgressForCode = null) }
                lineOpenMutex.unlock()
            }
        }
    }
}
