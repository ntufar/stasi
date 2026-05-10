package io.github.ntufar.stasi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val UI_LOCALE = stringPreferencesKey("ui_locale")
private val ARRIVAL_ALERT_THRESHOLD_MINUTES = intPreferencesKey("arrival_alert_threshold_minutes")

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

    suspend fun setLocaleTag(tag: String) {
        require(tag == LANGUAGE_EN || tag == LANGUAGE_EL)
        store.edit { it[UI_LOCALE] = tag }
    }

    suspend fun setArrivalAlertThresholdMinutes(minutes: Int) {
        val v = minutes.coerceIn(ARRIVAL_ALERT_THRESHOLD_MIN, ARRIVAL_ALERT_THRESHOLD_MAX)
        store.edit { it[ARRIVAL_ALERT_THRESHOLD_MINUTES] = v }
    }

    companion object {
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_EL = "el"

        const val DEFAULT_ARRIVAL_ALERT_THRESHOLD_MINUTES = 5
        const val ARRIVAL_ALERT_THRESHOLD_MIN = 1
        const val ARRIVAL_ALERT_THRESHOLD_MAX = 30

        /** Presets offered in the navigation drawer settings control. */
        val arrivalAlertThresholdChoices: List<Int> = listOf(1, 2, 3, 5, 7, 10, 12, 15, 20, 25, 30)
    }
}
