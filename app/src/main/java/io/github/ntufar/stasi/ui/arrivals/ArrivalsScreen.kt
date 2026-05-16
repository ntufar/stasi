package io.github.ntufar.stasi.ui.arrivals

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.ntufar.stasi.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.util.freshnessUpdatedLabel
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val ARRIVALS_DISPLAY_TICK_MS = 15_000L

private fun shareMinutes(context: android.content.Context, minutes: Int): String =
    if (minutes >= 999) {
        context.getString(R.string.arrivals_share_minutes_unknown)
    } else {
        context.getString(R.string.minutes_short, minutes)
    }

private fun buildArrivalsShareText(
    context: android.content.Context,
    stopTitle: String,
    stopCode: String,
    lastUpdatedMillis: Long?,
    arrivals: List<io.github.ntufar.stasi.data.repository.ArrivalDetail>,
): String {
    val lines = mutableListOf<String>()
    lines += context.getString(R.string.arrivals_share_heading, stopTitle, stopCode)
    freshnessUpdatedLabel(context, lastUpdatedMillis, R.string.arrivals_updated_at)?.let { lines += it }
    if (arrivals.isEmpty()) {
        lines += context.getString(R.string.arrivals_share_no_arrivals)
    } else {
        lines += context.getString(R.string.arrivals_share_next_arrivals)
        arrivals.take(3).forEachIndexed { index, arrival ->
            val minutes = shareMinutes(context, arrival.minutes)
            val fromOrigin = arrival.originDepartureMinutes?.let { om ->
                if (om >= 999) null else context.getString(R.string.arrivals_share_from_origin, shareMinutes(context, om))
            }
            val builder = StringBuilder()
                .append(index + 1)
                .append(". ")
                .append(minutes)
                .append(" · ")
                .append(arrival.lineLabel)
                .append(" → ")
                .append(arrival.destinationLabel)
            if (!fromOrigin.isNullOrBlank()) {
                builder.append(" | ").append(fromOrigin)
            }
            lines += builder.toString()
        }
    }
    lines += context.getString(R.string.arrivals_share_deep_link, buildStopDeepLink(stopCode))
    return lines.joinToString("\n")
}

private fun buildStopDeepLink(stopCode: String): String =
    "stasi://stop/${stopCode.trim()}"

private fun buildConciseSummaryText(
    context: android.content.Context,
    stopTitle: String,
    stopCode: String,
    arrivals: List<io.github.ntufar.stasi.data.repository.ArrivalDetail>,
): String {
    val lines = mutableListOf<String>()
    lines += context.getString(R.string.arrivals_share_heading, stopTitle, stopCode)
    if (arrivals.isEmpty()) {
        lines += context.getString(R.string.arrivals_share_no_arrivals)
    } else {
        arrivals.take(2).forEach { arrival ->
            val minutes = shareMinutes(context, arrival.minutes)
            lines += "${minutes} · ${arrival.lineLabel} → ${arrival.destinationLabel}"
        }
    }
    return lines.joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrivalsScreen(
    onOpenMenu: () -> Unit,
    stopCode: String,
    routeCodeHint: String? = null,
    onBack: () -> Unit,
    onOpenMap: (routeCode: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: ArrivalsViewModel = viewModel(
        key = "$stopCode:${routeCodeHint.orEmpty()}",
        factory = remember(stopCode, routeCodeHint, container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ArrivalsViewModel(
                        stopCode,
                        container.oasaRepository,
                        container.favoritesRepository,
                        container.alertsRepository,
                        container.recentActivityRepository,
                        context.applicationContext,
                        routeCodeHint = routeCodeHint,
                    ) as T
            }
        },
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()
    val thresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    val pullTranslationY = pullRefreshState.distanceFraction * thresholdPx
    val listRows = buildArrivalListRows(ui.arrivals)
    val mapRouteCode = ui.arrivals.firstOrNull { it.routeCode.isNotBlank() }?.routeCode
    val clipboard = LocalClipboardManager.current

    var displayNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            displayNowMillis = System.currentTimeMillis()
            while (isActive) {
                delay(ARRIVALS_DISPLAY_TICK_MS)
                displayNowMillis = System.currentTimeMillis()
            }
        }
    }

    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshNow()
        }
    }

    var pendingAlertArgs by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingAlertArgs?.let { (rc, vc, ll) -> vm.toggleAlert(rc, vc, ll) }
        }
        pendingAlertArgs = null
    }
    fun onBellTap(routeCode: String, vehCode: String, lineLabel: String) {
        val alertKey = "$routeCode:$vehCode"
        if (alertKey in ui.activeAlertKeys) {
            vm.toggleAlert(routeCode, vehCode, lineLabel)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAlertArgs = Triple(routeCode, vehCode, lineLabel)
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.toggleAlert(routeCode, vehCode, lineLabel)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = ui.title.ifBlank { stopCode },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionsMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = actionsMenuExpanded,
                            onDismissRequest = { actionsMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_refresh)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    vm.refreshNow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_menu)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    onOpenMenu()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_favorite)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    vm.toggleFavorite()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_map)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    mapRouteCode?.let(onOpenMap)
                                },
                                enabled = mapRouteCode != null,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.arrivals_action_share)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    val stopTitle = ui.title.ifBlank { stopCode }
                                    val text = buildArrivalsShareText(
                                        context = context,
                                        stopTitle = stopTitle,
                                        stopCode = stopCode,
                                        lastUpdatedMillis = ui.lastUpdatedMillis,
                                        arrivals = ui.arrivals,
                                    )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    context.getString(R.string.arrivals_share_subject, stopTitle),
                                                )
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            },
                                            context.getString(R.string.arrivals_share_chooser),
                                        ),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.arrivals_action_copy_summary)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    val stopTitle = ui.title.ifBlank { stopCode }
                                    clipboard.setText(
                                        AnnotatedString(
                                            buildConciseSummaryText(
                                                context = context,
                                                stopTitle = stopTitle,
                                                stopCode = stopCode,
                                                arrivals = ui.arrivals,
                                            ),
                                        ),
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.arrivals_copied_summary),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.arrivals_action_copy_link)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    clipboard.setText(AnnotatedString(buildStopDeepLink(stopCode)))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.arrivals_copied_link),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = { vm.onPullToRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = pullRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    state = pullRefreshState,
                    isRefreshing = ui.isRefreshing,
                )
            },
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = pullTranslationY
                    },
            ) {
                if (ui.isLoading && ui.arrivals.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    item {
                        ui.error?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        freshnessUpdatedLabel(context, ui.lastUpdatedMillis, R.string.arrivals_updated_at)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    items(
                        listRows,
                        key = { row ->
                            when (row) {
                                is ArrivalListRow.Live ->
                                    "live-${row.detail.routeCode}-${row.detail.vehCode}"
                                is ArrivalListRow.ScheduledOriginDeparture ->
                                    "sched-${row.routeCode}-${row.clock}"
                            }
                        },
                    ) { row ->
                    when (row) {
                        is ArrivalListRow.Live -> {
                            val a = row.detail
                            val displayMinutes = effectiveMinutesSinceSnapshot(
                                a.minutes,
                                ui.lastUpdatedMillis,
                                displayNowMillis,
                            )
                            val minutesText = if (displayMinutes >= 999) {
                                "—"
                            } else {
                                stringResource(R.string.minutes_short, displayMinutes)
                            }
                            val originText = a.originDepartureMinutes?.let { om ->
                                if (om >= 999) return@let null
                                val omDisplay = effectiveMinutesSinceSnapshot(
                                    om,
                                    ui.lastUpdatedMillis,
                                    displayNowMillis,
                                )
                                if (omDisplay >= 999) return@let null
                                val oLabel = a.originStopDescription?.takeIf { it.isNotBlank() } ?: ""
                                val fromPart = if (oLabel.isNotEmpty()) " ($oLabel)" else ""
                                stringResource(
                                    R.string.arrivals_from_origin_minutes,
                                    fromPart,
                                    stringResource(R.string.minutes_short, omDisplay),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable(enabled = a.routeCode.isNotBlank()) {
                                            onOpenMap(a.routeCode)
                                        }
                                        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
                                ) {
                                    val scheme = MaterialTheme.colorScheme
                                    Text(
                                        minutesText,
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (displayMinutes >= 999) scheme.onSurfaceVariant else scheme.primary,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            a.lineLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (a.isLastBusWarning) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFFFFA726),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.arrivals_last_service_warning),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        a.destinationLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    if (originText != null) {
                                        Text(
                                            originText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                                if (a.routeCode.isNotBlank()) {
                                    val alertKey = "${a.routeCode}:${a.vehCode}"
                                    val isAlertActive = alertKey in ui.activeAlertKeys
                                    IconButton(
                                        onClick = { onBellTap(a.routeCode, a.vehCode, a.lineLabel) },
                                        modifier = Modifier.padding(top = 12.dp, end = 4.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isAlertActive) Icons.Filled.Notifications
                                            else Icons.Outlined.NotificationsNone,
                                            contentDescription = stringResource(R.string.cd_alert),
                                            tint = if (isAlertActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        is ArrivalListRow.ScheduledOriginDeparture -> {
                            val scheme = MaterialTheme.colorScheme
                            val oLabel = row.originStopDescription?.takeIf { it.isNotBlank() } ?: ""
                            val fromPart = if (oLabel.isNotEmpty()) " ($oLabel)" else ""
                            val schedDisplayMin = effectiveMinutesSinceSnapshot(
                                row.minutesUntil,
                                ui.lastUpdatedMillis,
                                displayNowMillis,
                            )
                            val approx = when {
                                schedDisplayMin >= 999 -> null
                                schedDisplayMin <= 0 -> stringResource(R.string.arrivals_traffic_approx_less_than)
                                else -> stringResource(R.string.arrivals_traffic_approx_minutes, schedDisplayMin)
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = row.routeCode.isNotBlank()) {
                                        onOpenMap(row.routeCode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                Text(
                                    row.clock,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.secondary,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    row.lineLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.arrivals_schedule_from_origin, fromPart),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                if (approx != null) {
                                    Text(
                                        text = stringResource(R.string.arrivals_traffic_start_approx, approx),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
