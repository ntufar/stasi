package io.github.ntufar.stasi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ntufar.stasi.data.repository.SettingsRepository
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.ui.locale.ProvideAppLocaleCompositionLocals
import io.github.ntufar.stasi.ui.theme.StasiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private var pendingStopCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as StasiApplication).container
        pendingStopCode = extractStopCode(intent)
        runBlocking {
            val tag = container.settingsRepository.localeTag.first()
            AppLocale.apply(tag)
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides this@MainActivity,
                ) {
                    val localeTag by container.settingsRepository.localeTag.collectAsStateWithLifecycle(
                        initialValue = SettingsRepository.LANGUAGE_EL,
                    )
                    val darkTheme by container.settingsRepository.darkMode.collectAsStateWithLifecycle(
                        initialValue = SettingsRepository.DEFAULT_DARK_MODE,
                    )
                    ProvideAppLocaleCompositionLocals(localeTag) {
                        StasiTheme(darkTheme = darkTheme) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                StasiApp(initialStopCode = pendingStopCode)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingStopCode = extractStopCode(intent)
        setIntent(intent)
    }

    private fun extractStopCode(intent: Intent): String? {
        intent.getStringExtra("navigate_to_stop")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val uri = intent.data ?: return null
        if (uri.scheme != "stasi" || uri.host != "stop") return null
        return uri.lastPathSegment?.trim()?.takeIf { it.isNotBlank() }
    }
}
