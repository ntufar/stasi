package io.github.ntufar.stasi.ui.locale

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.ntufar.stasi.util.withAppLocaleTag

/**
 * Overrides [LocalContext] and [LocalConfiguration] so Compose [androidx.compose.ui.res.stringResource]
 * follows the persisted app language, without replacing the activity context (which would break
 * [androidx.activity.compose.rememberLauncherForActivityResult] and other locals that need an
 * [android.app.Activity]).
 */
@Composable
fun ProvideAppLocaleCompositionLocals(
    localeTag: String,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val localeOverlayContext = remember(baseContext, localeTag) {
        val localized = baseContext.withAppLocaleTag(localeTag)
        val localizedResources = localized.resources
        object : ContextWrapper(baseContext) {
            override fun getResources(): Resources = localizedResources
        }
    }
    val overlayConfiguration = remember(localeOverlayContext) {
        Configuration(localeOverlayContext.resources.configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localeOverlayContext,
        LocalConfiguration provides overlayConfiguration,
    ) {
        content()
    }
}
