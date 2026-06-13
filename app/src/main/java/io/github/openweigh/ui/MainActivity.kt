package io.github.openweigh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.openweigh.ui.theme.OpenWeighTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity entry point for OpenWeigh.
 *
 * Hosts the entire Compose UI: theme, navigation, and all screens. Declared in the
 * manifest as `.ui.MainActivity` (launcher + Health Connect rationale handler).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenWeighTheme {
                AppRoot()
            }
        }
    }
}
