package io.github.ntufar.stasi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

private val FAVORITE_STOP_CODES = stringSetPreferencesKey("favorite_stop_codes")

class FavoritesRepository(
    context: Context,
) {
    private val store = context.applicationContext.favoritesDataStore

    val favoriteStopCodes: Flow<Set<String>> = store.data.map { prefs ->
        prefs[FAVORITE_STOP_CODES].orEmpty()
    }

    suspend fun addFavorite(stopCode: String) {
        val code = stopCode.trim()
        if (code.isEmpty()) return
        store.edit { prefs ->
            val cur = prefs[FAVORITE_STOP_CODES].orEmpty().toMutableSet()
            cur.add(code)
            prefs[FAVORITE_STOP_CODES] = cur
        }
    }

    suspend fun removeFavorite(stopCode: String) {
        val code = stopCode.trim()
        store.edit { prefs ->
            val cur = prefs[FAVORITE_STOP_CODES].orEmpty().toMutableSet()
            cur.remove(code)
            prefs[FAVORITE_STOP_CODES] = cur
        }
    }

    /** @return true if the stop is a favorite after the toggle. */
    suspend fun toggleFavorite(stopCode: String): Boolean {
        val code = stopCode.trim()
        var nowFavorite = false
        store.edit { prefs ->
            val cur = prefs[FAVORITE_STOP_CODES].orEmpty().toMutableSet()
            nowFavorite = if (cur.contains(code)) {
                cur.remove(code)
                false
            } else {
                cur.add(code)
                true
            }
            prefs[FAVORITE_STOP_CODES] = cur
        }
        return nowFavorite
    }

    suspend fun isFavorite(stopCode: String): Boolean {
        val prefs = store.data.first()
        return prefs[FAVORITE_STOP_CODES].orEmpty().contains(stopCode.trim())
    }
}
