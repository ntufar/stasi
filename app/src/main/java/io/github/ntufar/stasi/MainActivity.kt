package io.github.ntufar.stasi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import io.github.ntufar.stasi.di.LocalAppContainer
import io.github.ntufar.stasi.ui.theme.StasiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as StasiApplication).container
        runBlocking {
            val tag = container.settingsRepository.localeTag.first()
            AppLocale.apply(tag)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                StasiTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        StasiApp()
                    }
                }
            }
        }
    }
}
