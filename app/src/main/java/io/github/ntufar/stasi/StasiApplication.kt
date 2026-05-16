package io.github.ntufar.stasi

import android.app.Application
import io.github.ntufar.stasi.di.AppContainer
import io.github.ntufar.stasi.util.NotificationHelper
import io.github.ntufar.stasi.util.withAppLocaleTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.maplibre.android.MapLibre

class StasiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        val localeTag = runBlocking { container.settingsRepository.localeTag.first() }
        AppLocale.apply(localeTag)
        NotificationHelper(withAppLocaleTag(localeTag)).createChannel()
    }
}
