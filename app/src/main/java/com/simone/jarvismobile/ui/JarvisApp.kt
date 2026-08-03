package com.simone.jarvismobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.simone.jarvismobile.ui.diagnostics.DiagnosticsScreen
import com.simone.jarvismobile.ui.home.HomeScreen
import com.simone.jarvismobile.ui.settings.SettingsScreen

private enum class Screen { HOME, DIAGNOSTICS, SETTINGS }

/** Lightweight top-level navigation between the Phase-1 screens. */
@Composable
fun JarvisApp(autoStartListening: Boolean = false) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            autoStart = autoStartListening,
            onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
    }
}
