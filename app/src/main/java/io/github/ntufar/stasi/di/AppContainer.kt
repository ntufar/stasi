package io.github.ntufar.stasi.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ntufar.stasi.data.local.AppDatabase
import io.github.ntufar.stasi.data.repository.FavoritesRepository
import io.github.ntufar.stasi.data.repository.OasaRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: AppDatabase = AppDatabase.build(appContext)

    val favoritesRepository = FavoritesRepository(appContext)
    val oasaRepository: OasaRepository = OasaRepository(dao = database.stasiDao())
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}
