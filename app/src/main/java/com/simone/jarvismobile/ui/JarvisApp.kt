package com.simone.jarvismobile.ui

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.ui.commands.CommandsScreen
import com.simone.jarvismobile.ui.dashboard.DashboardScreen
import com.simone.jarvismobile.ui.diagnostics.DiagnosticsScreen
import com.simone.jarvismobile.ui.home.HomeScreen
import com.simone.jarvismobile.ui.memory.MemoryScreen
import com.simone.jarvismobile.ui.models.ModelsScreen
import com.simone.jarvismobile.ui.settings.SettingsScreen
import com.simone.jarvismobile.ui.tasks.TasksScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    COMANDI("Comandi", Icons.Filled.Apps),
    NOTIFICHE("Notifiche", Icons.Filled.Notifications),
    IMPOSTAZIONI("Impostazioni", Icons.Filled.Settings),
}

private enum class Overlay { CHAT, MODELS, MEMORY, DIAGNOSTICS }

/**
 * Top-level navigation. The dashboard shell is always present; the written chat
 * opens as a bottom sheet over the dimmed dashboard (so the background still
 * shows through), while Models/Memory/Diagnostics open full-screen.
 */
@Composable
fun JarvisApp(
    initiallyOpenChat: Boolean = false,
    openChatRequest: Int = 0,
    startListeningRequest: Int = 0,
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf(if (initiallyOpenChat) Overlay.CHAT else null) }
    // Reading the chat marks it read: the badge must clear when the sheet closes,
    // not only when it opens, or messages seen while it was open stay counted.
    val dashboardViewModel: com.simone.jarvismobile.ui.dashboard.DashboardViewModel = hiltViewModel()
    LaunchedEffect(overlay) {
        if (overlay != Overlay.CHAT) dashboardViewModel.markChatSeen()
    }

    LaunchedEffect(openChatRequest) {
        if (openChatRequest > 0) overlay = Overlay.CHAT
    }

    Box(Modifier.fillMaxSize()) {
        // Base: the tab shell with the bottom navigation.
        Scaffold(
            containerColor = Color(0xFF05101A),
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0x00000000), Color(0x223FD8F0)),
                            ),
                        ),
                ) {
                    NavigationBar(containerColor = Color(0xCC0A1826)) {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry && entry != Tab.CHAT,
                                onClick = {
                                    if (entry == Tab.CHAT) overlay = Overlay.CHAT else tab = entry
                                },
                                icon = { Icon(entry.icon, contentDescription = entry.label) },
                                label = { Text(entry.label, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF9FEEFF),
                                    selectedTextColor = Color(0xFF9FEEFF),
                                    indicatorColor = Color(0x593FD8F0),
                                    unselectedIconColor = Color(0xFF7C8B95),
                                    unselectedTextColor = Color(0xFF7C8B95),
                                ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when (tab) {
                    Tab.HOME -> DashboardScreen(
                        onOpenSettings = { tab = Tab.IMPOSTAZIONI },
                        onOpenMemory = { overlay = Overlay.MEMORY },
                        onOpenChat = { overlay = Overlay.CHAT },
                    )
                    Tab.CHAT -> Unit
                    Tab.COMANDI -> CommandsScreen()
                    Tab.NOTIFICHE -> TasksScreen()
                    Tab.IMPOSTAZIONI -> SettingsScreen(
                        onBack = { tab = Tab.HOME },
                        onOpenModels = { overlay = Overlay.MODELS },
                        onOpenMemory = { overlay = Overlay.MEMORY },
                    )
                }
            }
        }

        // Chat as a bottom sheet — the dashboard stays visible behind a light scrim.
        AnimatedVisibility(
            visible = overlay == Overlay.CHAT,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler { overlay = null }
            Box(Modifier.fillMaxSize()) {
                // Scrim (tap to dismiss); light so the background is glimpsed.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { overlay = null },
                        ),
                )
                AnimatedVisibility(
                    visible = overlay == Overlay.CHAT,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                            .background(Color(0xFF0A1826)),
                    ) {
                        HomeScreen(
                            autoStartRequest = startListeningRequest,
                            onOpenDiagnostics = { overlay = Overlay.DIAGNOSTICS },
                            onOpenSettings = { overlay = null; tab = Tab.IMPOSTAZIONI },
                            onOpenModels = { overlay = Overlay.MODELS },
                            onOpenMemory = { overlay = Overlay.MEMORY },
                        )
                    }
                }
            }
        }

        // Full-screen overlays (opaque) for the secondary screens.
        if (overlay == Overlay.MODELS || overlay == Overlay.MEMORY || overlay == Overlay.DIAGNOSTICS) {
            BackHandler { overlay = null }
            Box(Modifier.fillMaxSize().background(Color(0xFF071119))) {
                when (overlay) {
                    Overlay.MODELS -> ModelsScreen(onBack = { overlay = null })
                    Overlay.MEMORY -> MemoryScreen(onBack = { overlay = null })
                    Overlay.DIAGNOSTICS -> DiagnosticsScreen(onBack = { overlay = null })
                    else -> Unit
                }
            }
        }
    }
}
