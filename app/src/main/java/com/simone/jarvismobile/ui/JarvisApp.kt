package com.simone.jarvismobile.ui

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import com.simone.jarvismobile.ui.automation.AutomationsScreen
import com.simone.jarvismobile.ui.home.JarvisChatWindow
import com.simone.jarvismobile.ui.livetranslate.LiveTranslatorScreen
import com.simone.jarvismobile.ui.memory.MemoryScreen
import com.simone.jarvismobile.ui.models.ModelsScreen
import com.simone.jarvismobile.ui.settings.SettingsScreen
import com.simone.jarvismobile.ui.agenda.AgendaScreen
import com.simone.jarvismobile.ui.tasks.TasksScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    COMANDI("Comandi", Icons.Filled.Apps),
    NOTIFICHE("Attività", Icons.Filled.CheckCircle),
    IMPOSTAZIONI("Impostazioni", Icons.Filled.Settings),
}

private enum class Overlay { CHAT, MODELS, MEMORY, DIAGNOSTICS, AUTOMATIONS, TRANSLATOR }

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
                    // Custom bar rather than Material's NavigationBar: the
                    // stock one paints an opaque surface and a pill-shaped
                    // indicator behind the active item, both of which fight a
                    // translucent HUD. Here the bar is glass and the active
                    // entry is marked by the icon itself lighting up.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x99040C14))
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tab.entries.forEach { entry ->
                            val active = tab == entry && entry != Tab.CHAT
                            val tint = if (active) Color(0xFF12D9FF) else Color(0xFF6B7C87)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (entry == Tab.CHAT) overlay = Overlay.CHAT else tab = entry
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    entry.icon,
                                    contentDescription = entry.label,
                                    tint = tint,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    entry.label,
                                    fontSize = 9.sp,
                                    color = tint,
                                    maxLines = 1,
                                    style = if (active) {
                                        androidx.compose.ui.text.TextStyle(
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color(0xFF12D9FF),
                                                blurRadius = 16f,
                                            ),
                                        )
                                    } else {
                                        androidx.compose.ui.text.TextStyle.Default
                                    },
                                )
                                // A short lit underline under the open section.
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier
                                        .width(if (active) 18.dp else 0.dp)
                                        .height(2.dp)
                                        .background(Color(0xFF12D9FF)),
                                )
                            }
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
                        onOpenAgenda = { tab = Tab.NOTIFICHE },
                        onOpenAutomations = { overlay = Overlay.AUTOMATIONS },
                        onOpenModels = { overlay = Overlay.MODELS },
                        onOpenTranslator = { overlay = Overlay.TRANSLATOR },
                    )
                    Tab.CHAT -> Unit
                    Tab.COMANDI -> CommandsScreen()
                    Tab.NOTIFICHE -> AgendaScreen()
                    Tab.IMPOSTAZIONI -> SettingsScreen(
                        onBack = { tab = Tab.HOME },
                        onOpenModels = { overlay = Overlay.MODELS },
                        onOpenMemory = { overlay = Overlay.MEMORY },
                        onOpenAutomations = { overlay = Overlay.AUTOMATIONS },
                        onOpenTranslator = { overlay = Overlay.TRANSLATOR },
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
                        JarvisChatWindow(
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
        if (overlay == Overlay.MODELS || overlay == Overlay.MEMORY ||
            overlay == Overlay.DIAGNOSTICS || overlay == Overlay.AUTOMATIONS ||
            overlay == Overlay.TRANSLATOR
        ) {
            BackHandler { overlay = null }
            Box(Modifier.fillMaxSize().background(Color(0xFF071119))) {
                when (overlay) {
                    Overlay.MODELS -> ModelsScreen(onBack = { overlay = null })
                    Overlay.MEMORY -> MemoryScreen(onBack = { overlay = null })
                    Overlay.AUTOMATIONS -> AutomationsScreen(onBack = { overlay = null })
                    Overlay.DIAGNOSTICS -> DiagnosticsScreen(onBack = { overlay = null })
                    Overlay.TRANSLATOR -> LiveTranslatorScreen(onBack = { overlay = null })
                    else -> Unit
                }
            }
        }
    }
}
