package io.github.ntufar.stasi

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.ntufar.stasi.data.repository.SettingsRepository

/** Logcat filter tag for language / locale debugging. */
internal const val LOCALE_LOG_TAG = "StasiLocale"

object AppLocale {
    fun apply(tag: String) {
        val locales = when (tag) {
            SettingsRepository.LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.forLanguageTags("el")
        }
        Log.d(
            LOCALE_LOG_TAG,
            "AppLocale.apply(tag=$tag) -> setApplicationLocales(${locales.toLanguageTags()}) " +
                "thread=${Thread.currentThread().name}",
        )
        AppCompatDelegate.setApplicationLocales(locales)
        Log.d(LOCALE_LOG_TAG, "AppLocale.apply: setApplicationLocales returned")
    }
}
