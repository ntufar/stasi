package com.example.stasi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.example.stasi.di.LocalAppContainer
import com.example.stasi.ui.theme.StasiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StasiApplication).container
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
