package com.example.stasi.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.stasi.data.local.AppDatabase
import com.example.stasi.data.repository.FavoritesRepository
import com.example.stasi.data.repository.OasaRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: AppDatabase = AppDatabase.build(appContext)

    val favoritesRepository = FavoritesRepository(appContext)
    val oasaRepository: OasaRepository = OasaRepository(dao = database.stasiDao())
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}
