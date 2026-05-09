package io.github.ntufar.stasi

import android.app.Application
import io.github.ntufar.stasi.di.AppContainer
import org.maplibre.android.MapLibre

class StasiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        // Do not run syncCatalogIncremental here: it competes with map/search API calls on rate limits.
        // Search screen and cache TTL trigger sync; map uses ensureLinesCatalogForResolve when needed.
    }
}
