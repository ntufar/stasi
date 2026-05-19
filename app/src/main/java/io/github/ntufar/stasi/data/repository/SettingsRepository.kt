package io.github.ntufar.stasi.data.repository

import android.content.Context
import android.util.Log
import io.github.ntufar.stasi.LOCALE_LOG_TAG
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val UI_LOCALE = stringPreferencesKey("ui_locale")
private val ARRIVAL_ALERT_THRESHOLD_MINUTES = intPreferencesKey("arrival_alert_threshold_minutes")
private val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
private val QUIET_HOURS_START_MINUTES = intPreferencesKey("quiet_hours_start_minutes")
private val QUIET_HOURS_END_MINUTES = intPreferencesKey("quiet_hours_end_minutes")
private val SHOW_MAP_STOP_NAMES = booleanPreferencesKey("show_map_stop_names")

data class QuietHoursSettings(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
)

class SettingsRepository(
    context: Context,
) {
    private val store = context.applicationContext.settingsDataStore

    /** Persisted UI language: [LANGUAGE_EN] or [LANGUAGE_EL]. */
    val localeTag: Flow<String> = store.data.map { prefs ->
        prefs[UI_LOCALE] ?: LANGUAGE_EL
    }

    /**
     * When arrival alerts begin: first notification once live ETA for the watched vehicle is
     * less than or equal to this many minutes (default [DEFAULT_ARRIVAL_ALERT_THRESHOLD_MINUTES]).
     */
    val arrivalAlertThresholdMinutes: Flow<Int> = store.data.map { prefs ->
        (prefs[ARRIVAL_ALERT_THRESHOLD_MINUTES] ?: DEFAULT_ARRIVAL_ALERT_THRESHOLD_MINUTES)
            .coerceIn(ARRIVAL_ALERT_THRESHOLD_MIN, ARRIVAL_ALERT_THRESHOLD_MAX)
    }

    /** When true, route map markers show OASA stop names below pins (default [DEFAULT_SHOW_MAP_STOP_NAMES]). */
    val showMapStopNames: Flow<Boolean> = store.data.map { prefs ->
        prefs[SHOW_MAP_STOP_NAMES] ?: DEFAULT_SHOW_MAP_STOP_NAMES
    }

    val quietHours: Flow<QuietHoursSettings> = store.data.map { prefs ->
        QuietHoursSettings(
            enabled = prefs[QUIET_HOURS_ENABLED] ?: false,
            startMinutes = (prefs[QUIET_HOURS_START_MINUTES] ?: DEFAULT_QUIET_HOURS_START_MINUTES)
                .coerceIn(0, 24 * 60 - 1),
            endMinutes = (prefs[QUIET_HOURS_END_MINUTES] ?: DEFAULT_QUIET_HOURS_END_MINUTES)
                .coerceIn(0, 24 * 60 - 1),
        )
    }

    suspend fun setLocaleTag(tag: String) {
        require(tag == LANGUAGE_EN || tag == LANGUAGE_EL)
        Log.d(LOCALE_LOG_TAG, "SettingsRepository.setLocaleTag: persisting tag=$tag")
        store.edit { it[UI_LOCALE] = tag }
        Log.d(LOCALE_LOG_TAG, "SettingsRepository.setLocaleTag: DataStore edit completed for tag=$tag")
    }

    suspend fun setArrivalAlertThresholdMinutes(minutes: Int) {
        val v = minutes.coerceIn(ARRIVAL_ALERT_THRESHOLD_MIN, ARRIVAL_ALERT_THRESHOLD_MAX)
        store.edit { it[ARRIVAL_ALERT_THRESHOLD_MINUTES] = v }
    }

    suspend fun setShowMapStopNames(show: Boolean) {
        store.edit { it[SHOW_MAP_STOP_NAMES] = show }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        store.edit { it[QUIET_HOURS_ENABLED] = enabled }
    }

    suspend fun setQuietHoursStartMinutes(minutes: Int) {
        store.edit { it[QUIET_HOURS_START_MINUTES] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setQuietHoursEndMinutes(minutes: Int) {
        store.edit { it[QUIET_HOURS_END_MINUTES] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    companion object {
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_EL = "el"

        const val DEFAULT_ARRIVAL_ALERT_THRESHOLD_MINUTES = 5
        const val ARRIVAL_ALERT_THRESHOLD_MIN = 1
        const val ARRIVAL_ALERT_THRESHOLD_MAX = 30
        const val DEFAULT_SHOW_MAP_STOP_NAMES = true
        const val DEFAULT_QUIET_HOURS_START_MINUTES = 23 * 60
        const val DEFAULT_QUIET_HOURS_END_MINUTES = 7 * 60

        /** Presets offered in the navigation drawer settings control. */
        val arrivalAlertThresholdChoices: List<Int> = listOf(1, 2, 3, 5, 7, 10, 12, 15, 20, 25, 30)
    }
}
