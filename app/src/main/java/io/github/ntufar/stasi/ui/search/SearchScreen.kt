package io.github.ntufar.stasi.ui.search

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ntufar.stasi.R
import io.github.ntufar.stasi.ui.ClockText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.stasi.di.LocalAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
    onStopSelected: (stopCode: String) -> Unit,
    onOpenLineOnMap: (routeCode: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
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
        modifier = Modifier.testTag("screen_search"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    ClockText(modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
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
                    label = { Text(stringResource(R.string.search_label_stop_or_line)) },
                    singleLine = true,
                )
            }
            when (ui.linesCatalog) {
                LinesCatalogState.Loading -> {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.search_loading_catalog),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                LinesCatalogState.Unavailable -> {
                    item {
                        Text(
                            stringResource(R.string.search_catalog_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = vm::retryLinesCatalog) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                LinesCatalogState.Ready -> Unit
            }
            if (ui.isSearching) {
                item { CircularProgressIndicator(Modifier.padding(vertical = 8.dp)) }
            }
            item {
                Text(
                    stringResource(R.string.lines_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(ui.lines, key = { it.lineCode }) { line ->
                val openingLine = ui.lineOpenInProgressForCode != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !openingLine,
                            onClick = {
                                vm.openLineOnMap(line.lineCode) { route ->
                                    if (route != null) {
                                        onOpenLineOnMap(route)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_route_load_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${line.lineId} · ${line.descr}",
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.lineOpenInProgressForCode == line.lineCode) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.stops_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
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
