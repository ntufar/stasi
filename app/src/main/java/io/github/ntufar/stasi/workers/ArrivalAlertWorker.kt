package io.github.ntufar.stasi.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.ntufar.stasi.StasiApplication
import io.github.ntufar.stasi.data.repository.AlertsRepository
import io.github.ntufar.stasi.data.repository.SettingsRepository
import io.github.ntufar.stasi.data.util.effectiveMinutesSinceSnapshot
import io.github.ntufar.stasi.data.util.isQuietHoursActive
import io.github.ntufar.stasi.util.NotificationHelper
import io.github.ntufar.stasi.util.withAppLocaleTag
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * One poll per run, then reschedules itself so WorkManager is not stuck in a long [delay] loop
 * (which the OS often stops when the app is backgrounded).
 */
class ArrivalAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_STOP_CODE = "stop_code"
        const val KEY_ROUTE_CODE = "route_code"
        const val KEY_VEH_CODE = "veh_code"
        const val KEY_LINE_LABEL = "line_label"
        const val KEY_STOP_TITLE = "stop_title"
        const val KEY_STARTED_AT_MILLIS = "started_at_millis"
        const val KEY_NOTIFIED = "notified"

        private const val POLL_INTERVAL_SECONDS = 30L
        private const val MAX_RUNTIME_MS = 30L * 60_000L

        fun uniqueWorkName(stopCode: String, routeCode: String, vehCode: String): String =
            "arrival_alert_${stopCode}_${routeCode}_${vehCode}"

        fun schedule(
            context: Context,
            stopCode: String,
            routeCode: String,
            vehCode: String,
            lineLabel: String,
            stopTitle: String,
            delaySeconds: Long = 0,
        ) {
            val startedAt = System.currentTimeMillis()
            val request = OneTimeWorkRequestBuilder<ArrivalAlertWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_STOP_CODE to stopCode,
                        KEY_ROUTE_CODE to routeCode,
                        KEY_VEH_CODE to vehCode,
                        KEY_LINE_LABEL to lineLabel,
                        KEY_STOP_TITLE to stopTitle,
                        KEY_STARTED_AT_MILLIS to startedAt,
                        KEY_NOTIFIED to false,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(stopCode, routeCode, vehCode),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        private fun scheduleNext(
            context: Context,
            stopCode: String,
            routeCode: String,
            vehCode: String,
            lineLabel: String,
            stopTitle: String,
            startedAtMillis: Long,
            notified: Boolean,
        ) {
            val request = OneTimeWorkRequestBuilder<ArrivalAlertWorker>()
                .setInitialDelay(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_STOP_CODE to stopCode,
                        KEY_ROUTE_CODE to routeCode,
                        KEY_VEH_CODE to vehCode,
                        KEY_LINE_LABEL to lineLabel,
                        KEY_STOP_TITLE to stopTitle,
                        KEY_STARTED_AT_MILLIS to startedAtMillis,
                        KEY_NOTIFIED to notified,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(stopCode, routeCode, vehCode),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        val stopCode = inputData.getString(KEY_STOP_CODE) ?: return Result.failure()
        val routeCode = inputData.getString(KEY_ROUTE_CODE) ?: return Result.failure()
        val vehCode = inputData.getString(KEY_VEH_CODE) ?: return Result.failure()
        val lineLabel = inputData.getString(KEY_LINE_LABEL) ?: routeCode
        val stopTitle = inputData.getString(KEY_STOP_TITLE) ?: stopCode
        val startedAt = inputData.getLong(KEY_STARTED_AT_MILLIS, System.currentTimeMillis())
        var notified = inputData.getBoolean(KEY_NOTIFIED, false)

        val now = System.currentTimeMillis()
        if (now - startedAt >= MAX_RUNTIME_MS) {
            AlertsRepository(applicationContext).removeAlert(stopCode, routeCode, vehCode)
            return Result.success()
        }

        val alertsRepository = AlertsRepository(applicationContext)
        if (!alertsRepository.isAlertActive(stopCode, routeCode, vehCode)) {
            return Result.success()
        }

        val container = (applicationContext as StasiApplication).container
        val oasaRepository = container.oasaRepository
        val settingsRepository = container.settingsRepository

        try {
            val threshold = settingsRepository.arrivalAlertThresholdMinutes.first()
            val quietHours = settingsRepository.quietHours.first()
            val quietNow = isQuietHoursActive(quietHours, LocalTime.now())
            val notificationHelper = NotificationHelper(
                applicationContext.withAppLocaleTag(settingsRepository.localeTag.first()),
            )
            val snapshot = oasaRepository.getStopArrivalsSnapshot(stopCode, forceRefresh = true)
            val hit = snapshot.arrivals.firstOrNull {
                it.routeCode == routeCode && it.vehCode == vehCode
            }
            val displayMinutes = hit?.let {
                effectiveMinutesSinceSnapshot(
                    it.minutes,
                    snapshot.fetchedAtMillis,
                    now,
                )
            }

            when {
                hit == null && notified -> {
                    if (!quietNow) {
                        notificationHelper.showOrUpdateArrivalNotification(
                            stopCode = stopCode,
                            routeCode = routeCode,
                            vehCode = vehCode,
                            lineLabel = lineLabel,
                            stopTitle = stopTitle,
                            phase = NotificationHelper.ArrivalPhase.Departed,
                        )
                    }
                    alertsRepository.removeAlert(stopCode, routeCode, vehCode)
                    return Result.success()
                }
                hit == null -> {
                    // Vehicle not on board yet or transient API gap — keep polling.
                }
                displayMinutes != null && (notified || displayMinutes <= threshold) -> {
                    notified = true
                    if (!quietNow) {
                        val phase = if (displayMinutes <= 0) {
                            NotificationHelper.ArrivalPhase.Arrived
                        } else {
                            NotificationHelper.ArrivalPhase.Countdown
                        }
                        notificationHelper.showOrUpdateArrivalNotification(
                            stopCode = stopCode,
                            routeCode = routeCode,
                            vehCode = vehCode,
                            lineLabel = lineLabel,
                            stopTitle = stopTitle,
                            phase = phase,
                            minutes = displayMinutes,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Network failure — reschedule below if alert still active.
        }

        if (alertsRepository.isAlertActive(stopCode, routeCode, vehCode) &&
            System.currentTimeMillis() - startedAt < MAX_RUNTIME_MS
        ) {
            scheduleNext(
                context = applicationContext,
                stopCode = stopCode,
                routeCode = routeCode,
                vehCode = vehCode,
                lineLabel = lineLabel,
                stopTitle = stopTitle,
                startedAtMillis = startedAt,
                notified = notified,
            )
        }
        return Result.success()
    }
}
