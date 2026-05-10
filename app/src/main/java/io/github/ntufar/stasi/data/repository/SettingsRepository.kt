package io.github.ntufar.stasi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val UI_LOCALE = stringPreferencesKey("ui_locale")

class SettingsRepository(
    context: Context,
) {
    private val store = context.applicationContext.settingsDataStore

    /** Persisted UI language: [LANGUAGE_EN] or [LANGUAGE_EL]. */
    val localeTag: Flow<String> = store.data.map { prefs ->
        prefs[UI_LOCALE] ?: LANGUAGE_EL
    }

    suspend fun setLocaleTag(tag: String) {
        require(tag == LANGUAGE_EN || tag == LANGUAGE_EL)
        store.edit { it[UI_LOCALE] = tag }
    }

    companion object {
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_EL = "el"
    }
}
