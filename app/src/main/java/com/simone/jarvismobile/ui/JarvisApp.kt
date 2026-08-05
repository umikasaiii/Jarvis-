package com.simone.jarvismobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.ui.common.PlaceholderScreen
import com.simone.jarvismobile.ui.dashboard.DashboardScreen
import com.simone.jarvismobile.ui.diagnostics.DiagnosticsScreen
import com.simone.jarvismobile.ui.home.HomeScreen
import com.simone.jarvismobile.ui.memory.MemoryScreen
import com.simone.jarvismobile.ui.models.ModelsScreen
import com.simone.jarvismobile.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    COMANDI("Comandi", Icons.Filled.Apps),
    NOTIFICHE("Notifiche", Icons.Filled.Notifications),
    IMPOSTAZIONI("Impostazioni", Icons.Filled.Settings),
}

/** Full-screen overlays reached from the tabs (no bottom bar while open). */
private enum class Overlay { CHAT, MODELS, MEMORY, DIAGNOSTICS }

/**
 * Top-level navigation: a bottom-nav dashboard shell. The dashboard (Home) shows
 * the JARVIS overview; Chat opens the full-screen voice/written conversation;
 * Models/Memory/Diagnostics open full-screen from Settings or the dashboard.
 */
@Composable
fun JarvisApp(autoStartListening: Boolean = false) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf(if (autoStartListening) Overlay.CHAT else null) }

    // Full-screen overlays take over the whole window (keeps the chat's keyboard
    // handling clean and avoids bottom-bar/IME conflicts).
    overlay?.let { current ->
        BackHandler { overlay = null }
        when (current) {
            Overlay.CHAT -> HomeScreen(
                autoStart = autoStartListening,
                onOpenDiagnostics = { overlay = Overlay.DIAGNOSTICS },
                onOpenSettings = { overlay = null; tab = Tab.IMPOSTAZIONI },
                onOpenModels = { overlay = Overlay.MODELS },
                onOpenMemory = { overlay = Overlay.MEMORY },
            )
            Overlay.MODELS -> ModelsScreen(onBack = { overlay = null })
            Overlay.MEMORY -> MemoryScreen(onBack = { overlay = null })
            Overlay.DIAGNOSTICS -> DiagnosticsScreen(onBack = { overlay = null })
        }
        return
    }

    Scaffold(
        containerColor = Color(0xFF061019),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A1622)) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && entry != Tab.CHAT,
                        onClick = {
                            if (entry == Tab.CHAT) overlay = Overlay.CHAT else tab = entry
                        },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF35D0EA),
                            selectedTextColor = Color(0xFF35D0EA),
                            indicatorColor = Color(0x2635D0EA),
                            unselectedIconColor = Color(0xFF7C8B95),
                            unselectedTextColor = Color(0xFF7C8B95),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                Tab.HOME -> DashboardScreen(
                    onOpenSettings = { tab = Tab.IMPOSTAZIONI },
                    onOpenMemory = { overlay = Overlay.MEMORY },
                    onOpenChat = { overlay = Overlay.CHAT },
                )
                Tab.CHAT -> Unit // handled as an overlay
                Tab.COMANDI -> PlaceholderScreen(
                    "Comandi",
                    "I comandi rapidi arriveranno con la Fase 6 (strumenti e azioni).",
                )
                Tab.NOTIFICHE -> PlaceholderScreen(
                    "Notifiche",
                    "Il centro notifiche arriverà più avanti.",
                )
                Tab.IMPOSTAZIONI -> SettingsScreen(
                    onBack = { tab = Tab.HOME },
                    onOpenModels = { overlay = Overlay.MODELS },
                    onOpenMemory = { overlay = Overlay.MEMORY },
                )
            }
        }
    }
}
