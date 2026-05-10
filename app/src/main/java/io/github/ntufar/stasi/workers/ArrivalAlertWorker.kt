package io.github.ntufar.stasi.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.ntufar.stasi.data.local.AppDatabase
import io.github.ntufar.stasi.data.repository.AlertsRepository
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.data.repository.SettingsRepository
import io.github.ntufar.stasi.util.NotificationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

        private const val POLL_INTERVAL_MS = 45_000L
        private const val MAX_RUNTIME_MS = 30L * 60_000L

        fun uniqueWorkName(stopCode: String, routeCode: String, vehCode: String): String =
            "arrival_alert_${stopCode}_${routeCode}_${vehCode}"
    }

    override suspend fun doWork(): Result {
        val stopCode = inputData.getString(KEY_STOP_CODE) ?: return Result.failure()
        val routeCode = inputData.getString(KEY_ROUTE_CODE) ?: return Result.failure()
        val vehCode = inputData.getString(KEY_VEH_CODE) ?: return Result.failure()
        val lineLabel = inputData.getString(KEY_LINE_LABEL) ?: routeCode
        val stopTitle = inputData.getString(KEY_STOP_TITLE) ?: stopCode

        val dao = AppDatabase.build(applicationContext).stasiDao()
        val oasaRepository = OasaRepository(dao = dao)
        val alertsRepository = AlertsRepository(applicationContext)
        val notificationHelper = NotificationHelper(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)

        val startTime = System.currentTimeMillis()
        var tracking = false

        while (System.currentTimeMillis() - startTime < MAX_RUNTIME_MS) {
            if (!alertsRepository.isAlertActive(stopCode, routeCode, vehCode)) {
                return Result.success()
            }

            try {
                val threshold = settingsRepository.arrivalAlertThresholdMinutes.first()
                val arrivals = oasaRepository.getStopArrivals(stopCode)
                val hit = arrivals.firstOrNull {
                    it.routeCode == routeCode && it.vehCode == vehCode
                }

                if (!tracking) {
                    if (hit != null && hit.minutes <= threshold) {
                        tracking = true
                        val initialPhase = if (hit.minutes <= 0) {
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
                            phase = initialPhase,
                            minutes = hit.minutes,
                        )
                    }
                } else {
                    if (hit == null) {
                        notificationHelper.showOrUpdateArrivalNotification(
                            stopCode = stopCode,
                            routeCode = routeCode,
                            vehCode = vehCode,
                            lineLabel = lineLabel,
                            stopTitle = stopTitle,
                            phase = NotificationHelper.ArrivalPhase.Departed,
                        )
                        alertsRepository.removeAlert(stopCode, routeCode, vehCode)
                        return Result.success()
                    }
                    val phase = if (hit.minutes <= 0) {
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
                        minutes = hit.minutes,
                    )
                }
            } catch (_: Exception) {
                // Network failure — keep retrying on next poll
            }

            delay(POLL_INTERVAL_MS)
        }

        alertsRepository.removeAlert(stopCode, routeCode, vehCode)
        return Result.success()
    }
}
