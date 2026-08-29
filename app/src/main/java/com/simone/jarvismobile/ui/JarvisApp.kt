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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.simone.jarvismobile.R
import com.simone.jarvismobile.ui.archive.ArchiveScreen
import com.simone.jarvismobile.ui.components.ThemedIcon
import com.simone.jarvismobile.ui.commands.CommandsScreen
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
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId

private enum class Tab(val label: String, val icon: ImageVector, val rougeIcon: Int? = null) {
    HOME("Home", Icons.Filled.Home, R.drawable.rouge_ic_home),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat, R.drawable.rouge_ic_chat),
    COMANDI("Comandi", Icons.Filled.Apps, R.drawable.rouge_ic_commands),
    NOTIFICHE("Attività", Icons.Filled.CheckCircle, R.drawable.rouge_ic_tasks),
    IMPOSTAZIONI("Impostazioni", Icons.Filled.Settings, R.drawable.rouge_ic_settings),
}

private enum class Overlay {
    CHAT, MODELS, MEMORY, DIAGNOSTICS, AUTOMATIONS, RULES, TRANSLATOR, DOCUMENTS,
    NAVIGATION, MAPS, FAVORITES, BACKUP, ARCHIVE, SYSTEM_STATUS,
}

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
    openAgendaRequest: Int = 0,
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

    // The reminder notification's tap opens the Attività (agenda) screen.
    LaunchedEffect(openAgendaRequest) {
        if (openAgendaRequest > 0) {
            overlay = null
            tab = Tab.NOTIFICHE
        }
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

    // The one HUD accent used directly in this file (bottom nav wash + active
    // tab tint), following the theme the same way the dashboard's Cyan does.
    val palette = LocalJarvisPalette.current
    val themeId = LocalJarvisThemeId.current

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
                                listOf(Color.Transparent, palette.accent.copy(alpha = 0x22 / 255f)),
                            ),
                        ),
                ) {
                    // Rouge gets the user's own reference art as the bar frame
                    // itself (§ richiesta esplicita: "cambia anche barra dello
                    // stato sotto con questa, e le relative icone") instead of
                    // the flat translucent wash — same "real dedicated art on
                    // Rouge" pattern as JarvisOrb/JarvisCard/ChatFab.
                    if (themeId == JarvisThemeId.ROUGE) {
                        // Crop, not FillBounds (§ richiesta esplicita
                        // dell'utente: "barra e icone si vedono male") — lo
                        // stretch a riempimento distorceva la cornice quando
                        // la larghezza reale dello schermo non coincide col
                        // rapporto d'aspetto sorgente dell'immagine; Crop
                        // preserva le proporzioni e ritaglia l'eccesso.
                        Image(
                            painter = painterResource(R.drawable.rouge_navbar_bg),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    // Custom bar rather than Material's NavigationBar: the
                    // stock one paints an opaque surface and a pill-shaped
                    // indicator behind the active item, both of which fight a
                    // translucent HUD. Here the bar is glass and the active
                    // entry is marked by the icon itself lighting up. On Rouge
                    // the frame image above already supplies the dark pill
                    // background, so the flat fill here is skipped there —
                    // layering it on top would dim the artwork underneath.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (themeId == JarvisThemeId.ROUGE) Modifier else Modifier.background(Color(0x99040C14)))
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tab.entries.forEach { entry ->
                            val active = tab == entry && entry != Tab.CHAT
                            val tint = if (active) palette.accentBright else Color(0xFF6B7C87)
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
                                ThemedIcon(
                                    entry.icon,
                                    // Real Rouge art only for the active tab: it is
                                    // already fully-coloured and has no muted/grey
                                    // form the way the plain vector icon does, so
                                    // showing it while inactive would break the
                                    // active/inactive contrast the bar relies on.
                                    // Chat is the one exception — it opens an overlay
                                    // rather than becoming the selected tab, so
                                    // `active` is always false for it by design; its
                                    // Rouge icon would otherwise never appear even
                                    // though the user explicitly asked for it here.
                                    rouge = entry.rougeIcon.takeIf { active || entry == Tab.CHAT },
                                    contentDescription = entry.label,
                                    tint = tint,
                                    // 26dp non 22dp (§ "barra e icone si
                                    // vedono male") — le icone Rouge sono arte
                                    // reale a colore pieno, non un vettoriale
                                    // sottile: a 22dp risultavano minute contro
                                    // la nuova cornice più decorata.
                                    modifier = Modifier.size(26.dp),
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
                                                color = palette.accentBright,
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
                                        .background(palette.accentBright),
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
                        onOpenSystemStatus = { overlay = Overlay.SYSTEM_STATUS },
                        onOpenArchive = { overlay = Overlay.ARCHIVE },
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
                        onOpenDocuments = { overlay = Overlay.DOCUMENTS },
                        onOpenNavigation = { overlay = Overlay.NAVIGATION },
                        onOpenMaps = { overlay = Overlay.MAPS },
                        onOpenFavorites = { overlay = Overlay.FAVORITES },
                        onOpenBackup = { overlay = Overlay.BACKUP },
                        onOpenArchive = { overlay = Overlay.ARCHIVE },
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
            overlay == Overlay.RULES ||
            overlay == Overlay.TRANSLATOR || overlay == Overlay.DOCUMENTS ||
            overlay == Overlay.NAVIGATION || overlay == Overlay.MAPS ||
            overlay == Overlay.FAVORITES || overlay == Overlay.BACKUP ||
            overlay == Overlay.ARCHIVE || overlay == Overlay.SYSTEM_STATUS
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
                        onOpenTasks = { overlay = null; tab = Tab.NOTIFICHE },
                        onOpenDocuments = { overlay = Overlay.DOCUMENTS },
                    )
                    Overlay.SYSTEM_STATUS -> SystemStatusScreen(onBack = { overlay = null })
                    else -> Unit
                }
            }
        }
    }
}
