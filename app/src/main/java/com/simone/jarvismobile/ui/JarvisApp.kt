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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.ui.archive.ArchiveScreen
import com.simone.jarvismobile.ui.dashboard.DashboardScreen
import com.simone.jarvismobile.ui.diagnostics.DiagnosticsScreen
import com.simone.jarvismobile.ui.automation.AutomationsScreen
import com.simone.jarvismobile.ui.automation.RulesScreen
import com.simone.jarvismobile.ui.backup.BackupScreen
import com.simone.jarvismobile.ui.home.JarvisChatWindow
import com.simone.jarvismobile.ui.livetranslate.LiveTranslatorScreen
import com.simone.jarvismobile.ui.documents.DocumentArchiveScreen
import com.simone.jarvismobile.ui.memory.MemoryScreen
import com.simone.jarvismobile.ui.navigation.FavoritesScreen
import com.simone.jarvismobile.ui.navigation.MapsScreen
import com.simone.jarvismobile.ui.navigation.NavigationScreen
import com.simone.jarvismobile.ui.models.ModelsScreen
import com.simone.jarvismobile.ui.settings.SettingsScreen
import com.simone.jarvismobile.ui.agenda.AgendaScreen
import com.simone.jarvismobile.ui.status.SystemStatusScreen

/**
 * Full-screen destinations reached from Home. Home itself (§ richiesta
 * esplicita dell'utente: "la home resta fissa, e ci si torna andando
 * indietro") is never one of these — it is the permanent base underneath
 * the [Scaffold], and every overlay here closes on the system back
 * gesture/button via [BackHandler], never via a bottom tab. AGENDA and
 * SETTINGS used to be tabs in a now-removed bottom navigation bar; COMANDI
 * used to be a third tab and is gone entirely — it lives inside Impostazioni
 * as a collapsible section instead (see [SettingsScreen]).
 */
private enum class Overlay {
    CHAT, MODELS, MEMORY, DIAGNOSTICS, AUTOMATIONS, RULES, TRANSLATOR, DOCUMENTS,
    NAVIGATION, MAPS, FAVORITES, BACKUP, ARCHIVE, SYSTEM_STATUS, AGENDA, SETTINGS,
}

/**
 * Top-level navigation. Home is the one fixed base screen; every other
 * destination (chat, agenda, settings, models, memory, …) opens as a full
 * overlay on top of it and closes back to Home on the system back gesture —
 * there is no bottom navigation bar any more (§ richiesta esplicita
 * dell'utente: "togli la barra in basso: la home resta fissa, e ci si torna
 * andando indietro"). The written chat still opens as a bottom sheet over
 * the dimmed dashboard (so the background is glimpsed behind it); every
 * other destination opens full-screen and opaque.
 */
@Composable
fun JarvisApp(
    initiallyOpenChat: Boolean = false,
    openChatRequest: Int = 0,
    startListeningRequest: Int = 0,
    openAgendaRequest: Int = 0,
) {
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

    // The reminder notification's tap opens the Attività (agenda) screen.
    LaunchedEffect(openAgendaRequest) {
        if (openAgendaRequest > 0) overlay = Overlay.AGENDA
    }

    // A spoken "avvia traduzione live …" starts the session in the background and
    // bumps this counter; bring the translator screen to the front so the user
    // lands where they asked to be.
    val translatorViewModel: com.simone.jarvismobile.ui.livetranslate.LiveTranslatorViewModel = hiltViewModel()
    val openTranslator by translatorViewModel.openScreenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openTranslator) {
        if (openTranslator > 0) overlay = Overlay.TRANSLATOR
    }

    // A spoken "portami a …" starts an offline route and asks to open the map.
    val navigationViewModel: com.simone.jarvismobile.ui.navigation.NavigationViewModel = hiltViewModel()
    val openNavigation by navigationViewModel.openScreenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openNavigation) {
        if (openNavigation > 0) overlay = Overlay.NAVIGATION
    }

    // JARVIS Drive's missing-map state asks to open the offline-maps download screen.
    val openMaps by navigationViewModel.openMapsScreenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openMaps) {
        if (openMaps > 0) overlay = Overlay.MAPS
    }

    Box(Modifier.fillMaxSize()) {
        // Base: Home alone, no bottom bar — always present underneath every overlay.
        Scaffold(containerColor = Color(0xFF05101A)) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                DashboardScreen(
                    onOpenSettings = { overlay = Overlay.SETTINGS },
                    onOpenMemory = { overlay = Overlay.MEMORY },
                    onOpenChat = { overlay = Overlay.CHAT },
                    onOpenAgenda = { overlay = Overlay.AGENDA },
                    onOpenAutomations = { overlay = Overlay.AUTOMATIONS },
                    onOpenModels = { overlay = Overlay.MODELS },
                    onOpenTranslator = { overlay = Overlay.TRANSLATOR },
                    onOpenSystemStatus = { overlay = Overlay.SYSTEM_STATUS },
                    onOpenArchive = { overlay = Overlay.ARCHIVE },
                )
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
                            onOpenSettings = { overlay = Overlay.SETTINGS },
                            onOpenModels = { overlay = Overlay.MODELS },
                            onOpenMemory = { overlay = Overlay.MEMORY },
                        )
                    }
                }
            }
        }

        // Full-screen overlays (opaque) for every other destination, Agenda and
        // Impostazioni included now that neither has a bottom tab of its own.
        if (overlay == Overlay.MODELS || overlay == Overlay.MEMORY ||
            overlay == Overlay.DIAGNOSTICS || overlay == Overlay.AUTOMATIONS ||
            overlay == Overlay.RULES ||
            overlay == Overlay.TRANSLATOR || overlay == Overlay.DOCUMENTS ||
            overlay == Overlay.NAVIGATION || overlay == Overlay.MAPS ||
            overlay == Overlay.FAVORITES || overlay == Overlay.BACKUP ||
            overlay == Overlay.ARCHIVE || overlay == Overlay.SYSTEM_STATUS ||
            overlay == Overlay.AGENDA || overlay == Overlay.SETTINGS
        ) {
            BackHandler { overlay = null }
            Box(Modifier.fillMaxSize().background(Color(0xFF071119))) {
                when (overlay) {
                    Overlay.MODELS -> ModelsScreen(onBack = { overlay = null })
                    Overlay.MEMORY -> MemoryScreen(onBack = { overlay = null })
                    Overlay.AUTOMATIONS -> AutomationsScreen(
                        onBack = { overlay = null },
                        onOpenAdvanced = { overlay = Overlay.RULES },
                    )
                    Overlay.RULES -> RulesScreen(onBack = { overlay = null })
                    Overlay.DIAGNOSTICS -> DiagnosticsScreen(onBack = { overlay = null })
                    Overlay.TRANSLATOR -> LiveTranslatorScreen(onBack = { overlay = null })
                    Overlay.DOCUMENTS -> DocumentArchiveScreen(onBack = { overlay = null })
                    Overlay.NAVIGATION -> NavigationScreen(
                        onBack = { overlay = null },
                        onOpenMaps = { overlay = Overlay.MAPS },
                    )
                    Overlay.MAPS -> MapsScreen(onBack = { overlay = null })
                    Overlay.FAVORITES -> FavoritesScreen(onBack = { overlay = null })
                    Overlay.BACKUP -> BackupScreen(onBack = { overlay = null })
                    Overlay.ARCHIVE -> ArchiveScreen(
                        onBack = { overlay = null },
                        onOpenTasks = { overlay = Overlay.AGENDA },
                        onOpenDocuments = { overlay = Overlay.DOCUMENTS },
                    )
                    Overlay.SYSTEM_STATUS -> SystemStatusScreen(onBack = { overlay = null })
                    Overlay.AGENDA -> AgendaScreen()
                    Overlay.SETTINGS -> SettingsScreen(
                        onBack = { overlay = null },
                        onOpenModels = { overlay = Overlay.MODELS },
                        onOpenMemory = { overlay = Overlay.MEMORY },
                        onOpenAutomations = { overlay = Overlay.AUTOMATIONS },
                        onOpenTranslator = { overlay = Overlay.TRANSLATOR },
                        onOpenDocuments = { overlay = Overlay.DOCUMENTS },
                        onOpenNavigation = { overlay = Overlay.NAVIGATION },
                        onOpenMaps = { overlay = Overlay.MAPS },
                        onOpenFavorites = { overlay = Overlay.FAVORITES },
                        onOpenBackup = { overlay = Overlay.BACKUP },
                        onOpenArchive = { overlay = Overlay.ARCHIVE },
                    )
                    else -> Unit
                }
            }
        }
    }
}
