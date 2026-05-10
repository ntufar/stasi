package io.github.ntufar.stasi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.alertsDataStore by preferencesDataStore(name = "arrival_alerts")
private val ACTIVE_ALERTS = stringSetPreferencesKey("active_alerts")

data class AlertKey(val stopCode: String, val routeCode: String, val vehCode: String) {
    fun encode(): String = "$stopCode:$routeCode:$vehCode"

    companion object {
        fun decode(encoded: String): AlertKey? {
            val parts = encoded.split(":", limit = 3)
            if (parts.size != 3) return null
            if (parts.any { it.isBlank() }) return null
            return AlertKey(parts[0], parts[1], parts[2])
        }
    }
}

class AlertsRepository(context: Context) {
    private val store = context.applicationContext.alertsDataStore

    val activeAlerts: Flow<Set<AlertKey>> = store.data.map { prefs ->
        prefs[ACTIVE_ALERTS].orEmpty().mapNotNull { AlertKey.decode(it) }.toSet()
    }

    suspend fun isAlertActive(stopCode: String, routeCode: String, vehCode: String): Boolean {
        val key = AlertKey(stopCode, routeCode, vehCode).encode()
        return store.data.first()[ACTIVE_ALERTS].orEmpty().contains(key)
    }

    suspend fun addAlert(stopCode: String, routeCode: String, vehCode: String) {
        store.edit { prefs ->
            val cur = prefs[ACTIVE_ALERTS].orEmpty().toMutableSet()
            cur.add(AlertKey(stopCode, routeCode, vehCode).encode())
            prefs[ACTIVE_ALERTS] = cur
        }
    }

    suspend fun removeAlert(stopCode: String, routeCode: String, vehCode: String) {
        store.edit { prefs ->
            val cur = prefs[ACTIVE_ALERTS].orEmpty().toMutableSet()
            cur.remove(AlertKey(stopCode, routeCode, vehCode).encode())
            prefs[ACTIVE_ALERTS] = cur
        }
    }
}
