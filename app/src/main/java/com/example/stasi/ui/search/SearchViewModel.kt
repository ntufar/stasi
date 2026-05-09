package com.example.stasi.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stasi.data.local.CachedLineEntity
import com.example.stasi.data.local.CachedStopEntity
import com.example.stasi.data.repository.OasaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val stops: List<CachedStopEntity> = emptyList(),
    val lines: List<CachedLineEntity> = emptyList(),
    val isSearching: Boolean = false,
)

class SearchViewModel(
    private val repository: OasaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.syncCatalogIncremental()
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
}
