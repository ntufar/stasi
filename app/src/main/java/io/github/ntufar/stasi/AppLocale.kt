package io.github.ntufar.stasi

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.ntufar.stasi.data.repository.SettingsRepository

object AppLocale {
    fun apply(tag: String) {
        val locales = when (tag) {
            SettingsRepository.LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.forLanguageTags("el")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
