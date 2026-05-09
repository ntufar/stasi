package com.example.stasi.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stasi.di.LocalAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onStopSelected: (stopCode: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: SearchViewModel = viewModel(
        factory = remember(container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(container.oasaRepository) as T
            }
        },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Αναζήτηση") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    label = { Text("Στάση ή γραμμή") },
                    singleLine = true,
                )
            }
            when (ui.linesCatalog) {
                LinesCatalogState.Loading -> {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Φόρτωση καταλόγου γραμμών…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                LinesCatalogState.Unavailable -> {
                    item {
                        Text(
                            "Δεν ήταν δυνατή η φόρτωση των γραμμών από τον διακομιστή OASA. Ελέγξτε τη σύνδεση δικτύου.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = vm::retryLinesCatalog) {
                            Text("Επανάληψη")
                        }
                    }
                }
                LinesCatalogState.Ready -> Unit
            }
            if (ui.isSearching) {
                item { CircularProgressIndicator(Modifier.padding(vertical = 8.dp)) }
            }
            item {
                Text("Γραμμές", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(ui.lines, key = { it.lineCode }) { line ->
                Text(
                    "${line.lineId} · ${line.descr}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(vertical = 8.dp),
                )
            }
            item {
                Text("Στάσεις", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            }
            items(ui.stops, key = { it.stopCode }) { stop ->
                Text(
                    "${stop.descr} (${stop.stopCode})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStopSelected(stop.stopCode) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
