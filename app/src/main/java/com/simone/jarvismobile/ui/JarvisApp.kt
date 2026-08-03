package com.simone.jarvismobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.simone.jarvismobile.ui.diagnostics.DiagnosticsScreen
import com.simone.jarvismobile.ui.home.HomeScreen

/** Top-level navigation between the two Phase-1 screens (Home and Diagnostics). */
@Composable
fun JarvisApp(autoStartListening: Boolean = false) {
    var showDiagnostics by remember { mutableStateOf(false) }

    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false })
    } else {
        HomeScreen(
            autoStart = autoStartListening,
            onOpenDiagnostics = { showDiagnostics = true },
        )
    }
}
