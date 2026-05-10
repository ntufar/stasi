package io.github.ntufar.stasi.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ntufar.stasi.data.local.AppDatabase
import io.github.ntufar.stasi.data.repository.AlertsRepository
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.OasaRepository
import io.github.ntufar.stasi.data.repository.SettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: AppDatabase = AppDatabase.build(appContext)

    val favoritesRepository = FavoritesRepository(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val alertsRepository = AlertsRepository(appContext)
    val oasaRepository: OasaRepository = OasaRepository(dao = database.stasiDao())
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}
