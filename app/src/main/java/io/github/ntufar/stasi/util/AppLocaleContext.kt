package io.github.ntufar.stasi.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import io.github.ntufar.stasi.data.repository.SettingsRepository

/** [Context] whose [android.content.res.Resources] use the persisted in-app language tag. */
fun Context.withAppLocaleTag(localeTag: String): Context {
    val config = Configuration(resources.configuration)
    val tags = when (localeTag) {
        SettingsRepository.LANGUAGE_EN -> SettingsRepository.LANGUAGE_EN
        else -> SettingsRepository.LANGUAGE_EL
    }
    config.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(config)
}
