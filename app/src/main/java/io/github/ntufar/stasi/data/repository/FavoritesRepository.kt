package io.github.ntufar.stasi.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

private val FAVORITE_STOP_CODES = stringSetPreferencesKey("favorite_stop_codes")
private val FAVORITE_ENTRIES_V2 = stringPreferencesKey("favorite_entries_v2")

data class FavoriteStopEntry(
    val stopCode: String,
    val alias: String = "",
)

private fun encodeFavoriteEntries(entries: List<FavoriteStopEntry>): String =
    entries.joinToString("\n") { entry ->
        val alias = Uri.encode(entry.alias.trim())
        "${entry.stopCode.trim()}|$alias"
    }

private fun decodeFavoriteEntries(raw: String?): List<FavoriteStopEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val parts = trimmed.split("|", limit = 2)
        val stopCode = parts.firstOrNull()?.trim().orEmpty()
        if (stopCode.isBlank()) return@mapNotNull null
        val alias = parts.getOrNull(1)?.let { Uri.decode(it) }.orEmpty()
        FavoriteStopEntry(stopCode = stopCode, alias = alias)
    }.toList()
}

class FavoritesRepository(
    context: Context,
) {
    private val store = context.applicationContext.favoritesDataStore

    val favoriteEntries: Flow<List<FavoriteStopEntry>> = store.data.map { prefs ->
        val v2 = decodeFavoriteEntries(prefs[FAVORITE_ENTRIES_V2])
        if (v2.isNotEmpty()) {
            v2
        } else {
            prefs[FAVORITE_STOP_CODES].orEmpty().sorted().map { FavoriteStopEntry(it) }
        }
    }

    val favoriteStopCodes: Flow<Set<String>> = favoriteEntries.map { entries ->
        entries.map { it.stopCode }.toSet()
    }

    private suspend fun currentEntries(): List<FavoriteStopEntry> {
        val prefs = store.data.firstOrNull() ?: return emptyList()
        val v2 = decodeFavoriteEntries(prefs[FAVORITE_ENTRIES_V2])
        if (v2.isNotEmpty()) return v2
        return prefs[FAVORITE_STOP_CODES].orEmpty().sorted().map { FavoriteStopEntry(it) }
    }

    private suspend fun persistEntries(entries: List<FavoriteStopEntry>) {
        store.edit { prefs ->
            prefs[FAVORITE_ENTRIES_V2] = encodeFavoriteEntries(entries)
            prefs.remove(FAVORITE_STOP_CODES)
        }
    }

    suspend fun addFavorite(stopCode: String) {
        val code = stopCode.trim()
        if (code.isEmpty()) return
        val cur = currentEntries().toMutableList()
        if (cur.any { it.stopCode == code }) return
        cur.add(FavoriteStopEntry(code))
        persistEntries(cur)
    }

    suspend fun removeFavorite(stopCode: String) {
        val code = stopCode.trim()
        val cur = currentEntries().filterNot { it.stopCode == code }
        persistEntries(cur)
    }

    /** @return true if the stop is a favorite after the toggle. */
    suspend fun toggleFavorite(stopCode: String): Boolean {
        val code = stopCode.trim()
        val cur = currentEntries().toMutableList()
        val nowFavorite = if (cur.any { it.stopCode == code }) {
            cur.removeAll { it.stopCode == code }
            false
        } else {
            cur.add(FavoriteStopEntry(code))
            true
        }
        persistEntries(cur)
        return nowFavorite
    }

    suspend fun isFavorite(stopCode: String): Boolean {
        val code = stopCode.trim()
        return currentEntries().any { it.stopCode == code }
    }

    suspend fun renameFavorite(stopCode: String, alias: String) {
        val code = stopCode.trim()
        val cleanedAlias = alias.trim()
        val updated = currentEntries().map {
            if (it.stopCode == code) it.copy(alias = cleanedAlias) else it
        }
        persistEntries(updated)
    }

    suspend fun moveFavorite(stopCode: String, delta: Int) {
        if (delta == 0) return
        val code = stopCode.trim()
        val entries = currentEntries().toMutableList()
        val index = entries.indexOfFirst { it.stopCode == code }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, entries.lastIndex)
        if (target == index) return
        val entry = entries.removeAt(index)
        entries.add(target, entry)
        persistEntries(entries)
    }
}
