package io.github.ntufar.stasi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentActivityDataStore by preferencesDataStore(name = "recent_activity")

private val RECENT_STOP_CODE = stringPreferencesKey("recent_stop_code")
private val RECENT_STOP_AT = longPreferencesKey("recent_stop_at")
private val RECENT_ROUTE_CODE = stringPreferencesKey("recent_route_code")
private val RECENT_ROUTE_AT = longPreferencesKey("recent_route_at")

data class RecentStopVisit(
    val stopCode: String,
    val atMillis: Long,
)

data class RecentRouteVisit(
    val routeCode: String,
    val atMillis: Long,
)

class RecentActivityRepository(context: Context) {
    private val store = context.applicationContext.recentActivityDataStore

    val recentStopVisit: Flow<RecentStopVisit?> = store.data.map { prefs ->
        val code = prefs[RECENT_STOP_CODE].orEmpty().trim()
        val at = prefs[RECENT_STOP_AT] ?: 0L
        if (code.isBlank() || at <= 0L) null else RecentStopVisit(code, at)
    }

    val recentRouteVisit: Flow<RecentRouteVisit?> = store.data.map { prefs ->
        val code = prefs[RECENT_ROUTE_CODE].orEmpty().trim()
        val at = prefs[RECENT_ROUTE_AT] ?: 0L
        if (code.isBlank() || at <= 0L) null else RecentRouteVisit(code, at)
    }

    suspend fun recordStopVisit(stopCode: String) {
        val code = stopCode.trim()
        if (code.isBlank()) return
        store.edit { prefs ->
            prefs[RECENT_STOP_CODE] = code
            prefs[RECENT_STOP_AT] = System.currentTimeMillis()
        }
    }

    suspend fun recordRouteVisit(routeCode: String) {
        val code = routeCode.trim()
        if (code.isBlank()) return
        store.edit { prefs ->
            prefs[RECENT_ROUTE_CODE] = code
            prefs[RECENT_ROUTE_AT] = System.currentTimeMillis()
        }
    }
}
