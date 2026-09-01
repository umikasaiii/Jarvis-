package com.simone.jarvismobile.ui.dashboard

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.R
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.ui.automation.AutomationsViewModel
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.core.agenda.ReminderAlertType
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WindDirection
import com.simone.jarvismobile.driving.DrivingModeActivity
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeeklyOutlook
import com.simone.jarvismobile.ui.components.HudOverlay
import com.simone.jarvismobile.ui.components.JarvisCard
import com.simone.jarvismobile.ui.components.JarvisOrb
import com.simone.jarvismobile.ui.components.OrbState
import com.simone.jarvismobile.ui.components.ThemedIcon
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId
import com.simone.jarvismobile.llm.LlmLoadState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// --- Palette ---------------------------------------------------------------
// The brand accent (§ Impostazioni › Temi) — every other name here stays a
// fixed literal (they are semantic: success, warning, alert, neutral text).
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
private val Blue = Color(0xFF3B9EFF)
private val Green = Color(0xFF2ECC71)
private val Amber = Color(0xFFF3B23C)
private val Violet = Color(0xFF9B7BFF)
private val Rose = Color(0xFFFF5E5E)
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)

private val CardTop = Color(0xD90A1826)
private val CardBottom = Color(0xCC040C15)

/** Angular "HUD" card shape: chamfered top-left and bottom-right corners. */
private val TechShape = CutCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 18.dp)

/** Fixed starfield so the ambient background is stable across recompositions. */
private data class Dot(val x: Float, val y: Float, val r: Float, val a: Float)
private val ambientDots = listOf(
    Dot(0.12f, 0.10f, 1.4f, 0.5f), Dot(0.30f, 0.06f, 1.0f, 0.35f), Dot(0.55f, 0.09f, 1.6f, 0.5f),
    Dot(0.78f, 0.05f, 1.1f, 0.4f), Dot(0.90f, 0.12f, 1.3f, 0.45f), Dot(0.20f, 0.18f, 1.0f, 0.3f),
    Dot(0.68f, 0.16f, 1.2f, 0.4f), Dot(0.05f, 0.24f, 1.5f, 0.5f), Dot(0.42f, 0.22f, 0.9f, 0.3f),
    Dot(0.85f, 0.26f, 1.4f, 0.45f), Dot(0.15f, 0.34f, 1.1f, 0.35f), Dot(0.60f, 0.32f, 1.6f, 0.5f),
    Dot(0.95f, 0.38f, 1.0f, 0.3f), Dot(0.33f, 0.42f, 1.3f, 0.4f), Dot(0.72f, 0.46f, 1.1f, 0.35f),
    Dot(0.08f, 0.52f, 1.5f, 0.45f), Dot(0.50f, 0.55f, 0.9f, 0.3f), Dot(0.88f, 0.58f, 1.4f, 0.4f),
    Dot(0.22f, 0.64f, 1.2f, 0.35f), Dot(0.65f, 0.68f, 1.0f, 0.3f), Dot(0.40f, 0.74f, 1.5f, 0.4f),
    Dot(0.92f, 0.78f, 1.1f, 0.35f), Dot(0.14f, 0.82f, 1.3f, 0.4f), Dot(0.58f, 0.86f, 1.0f, 0.3f),
    Dot(0.80f, 0.90f, 1.4f, 0.4f), Dot(0.28f, 0.92f, 1.1f, 0.35f), Dot(0.48f, 0.96f, 1.2f, 0.35f),
)

/**
 * Ambient JARVIS background: a deep blue gradient, a soft glow behind the orb, a
 * bright light-leak from the bottom edge, cool side glows and a faint starfield —
 * matching the concept render. Drawn once behind the (glass) cards.
 */
@Composable
private fun JarvisBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF030810), Color(0xFF061019), Color(0xFF01050A))))
        // Glow behind the orb (upper third).
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x243B9EFF), Color.Transparent), center = Offset(w * 0.5f, h * 0.20f), radius = w * 0.72f),
            radius = w * 0.72f, center = Offset(w * 0.5f, h * 0.20f),
        )
        // Light-leak from the bottom.
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x3335D0EA), Color.Transparent), center = Offset(w * 0.5f, h * 1.04f), radius = w * 0.9f),
            radius = w * 0.9f, center = Offset(w * 0.5f, h * 1.04f),
        )
        // Cool side glows.
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x1A35D0EA), Color.Transparent), center = Offset(0f, h * 0.93f), radius = w * 0.5f),
            radius = w * 0.5f, center = Offset(0f, h * 0.93f),
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x1A35D0EA), Color.Transparent), center = Offset(w, h * 0.93f), radius = w * 0.5f),
            radius = w * 0.5f, center = Offset(w, h * 0.93f),
        )
        // Starfield.
        for (d in ambientDots) {
            drawCircle(Color(0xFFA9E8F5).copy(alpha = d.a * 0.8f), radius = d.r, center = Offset(d.x * w, d.y * h))
        }
    }
}

/**
 * The full JARVIS dashboard (Home tab). Tiles backed by real data — the listen
 * orb, battery, local AI/model, the agenda, and the indexed Obsidian note counts
 * — are live; the rest show representative sample content with a clear DEMO/FASE
 * badge, so nothing fake is passed off as real while the look stays faithful to
 * the design.
 */
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenAgenda: () -> Unit = {},
    onOpenAutomations: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenTranslator: () -> Unit = {},
    onOpenSystemStatus: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val backupCloudEnabled by viewModel.backupCloudEnabled.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val loadState by viewModel.llmLoadState.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val llmGenerating by viewModel.llmGenerating.collectAsStateWithLifecycle()
    val memory by viewModel.memoryStatus.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val allEntries by viewModel.allEntries.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val proModeActive by viewModel.proModeActive.collectAsStateWithLifecycle()
    val battery = rememberBatteryStatus()
    val context = LocalContext.current

    // The wake word listens ONLY while this screen is resumed (app open + visible):
    // start on ON_RESUME, stop on ON_PAUSE. Never a background microphone.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.setWakeActive(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.setWakeActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setWakeActive(false)
        }
    }
    var editingAlerts by remember { mutableStateOf<AgendaEntry?>(null) }
    // The day the agenda block is focused on. Tapping a day in the week strip
    // updates this in place (no dialog); the timeline below re-renders for it.
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    // Buzz once when a new message lands (e.g. a voice reply) while on the dashboard.
    var prevUnread by remember { mutableStateOf(unread) }
    LaunchedEffect(unread) {
        if (unread > prevUnread) runCatching { vibrateOnce(context) }
        prevUnread = unread
    }

    val accent = accentFor(state, Cyan)
    // Rouge (§ Impostazioni › Temi) uses a real reference background instead of
    // the default blue one — unlike the card frame/HUD edges, this image is
    // fully opaque, so a flat SrcIn tint (as Rosso applies elsewhere) would
    // flatten its whole gradient into one solid colour; Rosso therefore leaves
    // it untouched, and only Rouge — which has real red art for this role —
    // actually changes it.
    val dashboardBgRes = if (LocalJarvisThemeId.current == JarvisThemeId.ROUGE) {
        R.drawable.rouge_bg_dashboard
    } else {
        R.drawable.bg_dashboard
    }

    // Ares has its own Home layout entirely (§ richiesta esplicita
    // dell'utente: "cambiano solo quelli di questo tema senza toccare gli
    // altri") — everything below this branch (through the ChatFab) is
    // completely untouched for Classico/Rosso/Rouge.
    if (LocalJarvisThemeId.current == JarvisThemeId.ARES) {
        AresHomeScreen(
            name = name,
            state = state,
            hasError = lastError != null,
            today = today,
            upcoming = upcoming,
            allEntries = allEntries,
            timeline = timeline,
            selectedDate = selectedDate,
            unread = unread,
            proModeActive = proModeActive,
            onSelectDate = { selectedDate = it },
            onOrbClick = {
                when {
                    state == ConversationState.Speaking -> viewModel.onInterruptAndTalk()
                    state.isRestingLike() -> viewModel.onTalkPressed()
                    else -> viewModel.onCancel()
                }
            },
            onToggleDone = { viewModel.toggleDone(it) },
            onEditAlerts = { editingAlerts = it },
            onOpenSettings = onOpenSettings,
            onOpenChat = { viewModel.markChatSeen(); onOpenChat() },
            onOpenAgenda = onOpenAgenda,
            onOpenDrive = { context.startActivity(DrivingModeActivity.intent(context)) },
            onOpenTranslator = onOpenTranslator,
            onOpenArchive = onOpenArchive,
        )
    } else {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        Image(
            painter = painterResource(dashboardBgRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )

        // Fixed frame: above the background, below the content, never scrolls.
        HudOverlay(Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
            Text(
                text = name.uppercase(),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 12.sp,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Cyan,
                        offset = Offset.Zero,
                        blurRadius = 28f,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (proModeActive) {
                com.simone.jarvismobile.ui.components.ProModeBadge(
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            // Settings icon, now inside the title box itself — bigger and at
            // "JARVIS height" (§ richiesta esplicita dell'utente: "icona del
            // menu piu grande e più in basso, ad altezza jarvis") instead of
            // a thin strip above it. CenterEnd, not TopEnd, so it never
            // shares the PRO badge's corner.
            ThemedIcon(
                Icons.Filled.Settings,
                R.drawable.rouge_ic_menu,
                tint = Muted,
                contentDescription = "Impostazioni",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp)
                    .clickable(onClick = onOpenSettings),
            )
            }

            // --- Status line + orb, alone --------------------------------
            // The battery and weather tiles are gone. Battery survives as the
            // footer of the Sistema tile below, so the reading is not lost with
            // the block; the weather was a DEMO placeholder and is simply out.
            // The hero is now the orb and what JARVIS is doing, nothing else.
            val status = statusFor(state, lastError != null, Cyan)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(status.color))
                Spacer(Modifier.width(10.dp))
                Text(
                    status.label,
                    color = status.color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = status.color,
                            offset = Offset.Zero,
                            blurRadius = 20f,
                        ),
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(status.color))
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                JarvisOrb(
                    state = orbStateFor(state, lastError != null),
                    onClick = {
                        when {
                            state == ConversationState.Speaking -> viewModel.onInterruptAndTalk()
                            state.isRestingLike() -> viewModel.onTalkPressed()
                            else -> viewModel.onCancel()
                        }
                    },
                    size = 205.dp,
                )
            }


            // --- Three stat tiles --------------------------------------------
            // Every one is a real number and every one opens the screen that
            // owns it: a tile that shows a count but does nothing is furniture.
            // "Calendario" and "Attività" used to be two separate tiles that
            // both opened the very same Attività screen — appointments and
            // tasks are one unified Agenda (§ phase 6b+), so there was never a
            // second screen for either of them to open on its own. Merged into
            // one wider "Agenda" tile instead of pretending they led somewhere
            // different.
            val todayEvents = today.count { it.time != null }
            val todayTasks = today.count { it.time == null }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    icon = Icons.Filled.CalendarMonth,
                    label = "Agenda",
                    value = today.size.toString(),
                    unit = "oggi",
                    footer = "$todayEvents eventi · $todayTasks attività",
                    accent = Cyan,
                    onClick = onOpenAgenda,
                    modifier = Modifier.weight(2f),
                    rougeIcon = R.drawable.rouge_ic_agenda,
                )
                StatTile(
                    icon = Icons.Filled.Memory,
                    label = "Sistema",
                    // "Occupato" covers exactly the window a cancel/timeout leaves
                    // behind: the orb/state above already reads "Pronto", but the
                    // native call hasn't actually unwound yet, so a new request
                    // would still fail fast rather than really being answered —
                    // this tile is the one place that says so honestly instead of
                    // a flat "OK" that looks identical whether JARVIS is truly
                    // free or not. See SessionCoordinator.llmGenerating. Tapping
                    // it opens the full status screen, not the Models screen —
                    // that used to be the tile's only reachable destination even
                    // though its label promised general system status.
                    value = when {
                        llmGenerating -> "Occupato"
                        loadState == LlmLoadState.LOADED -> "OK"
                        else -> "—"
                    },
                    unit = when {
                        llmGenerating -> "un attimo"
                        loadState == LlmLoadState.LOADED -> "attivo"
                        else -> "modello"
                    },
                    footer = "Batteria ${battery.percent}%",
                    accent = if (llmGenerating) Amber else Green,
                    onClick = onOpenSystemStatus,
                    modifier = Modifier.weight(1f),
                    rougeIcon = R.drawable.rouge_ic_system,
                )
                StatTile(
                    icon = Icons.Filled.Book,
                    label = "Memoria",
                    value = memory.noteCount.toString(),
                    unit = "note",
                    footer = "${memory.chunkCount} frammenti",
                    accent = Violet,
                    onClick = onOpenMemory,
                    modifier = Modifier.weight(1f),
                    rougeIcon = R.drawable.rouge_ic_memory,
                )
            }

            // Automations keep most of the row (a section entry point, not a
            // metric to compare against the others), narrowed by about a
            // quarter to make room for "Archivio locale" alongside it (§
            // richiesta esplicita dell'utente) instead of a fifth full-width
            // row of its own.
            val automationsVm: AutomationsViewModel = hiltViewModel()
            val rules by automationsVm.automations.collectAsStateWithLifecycle()
            val archiveVm: com.simone.jarvismobile.ui.archive.ArchiveViewModel = hiltViewModel()
            val localArchiveCount by archiveVm.itemCount.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    icon = Icons.Filled.Bolt,
                    label = "Automazioni",
                    value = rules.count { it.enabled }.toString(),
                    unit = if (rules.count { it.enabled } == 1) "regola attiva" else "regole attive",
                    footer = rules.firstOrNull { it.enabled }
                        ?.let { AutomationCodec.describe(it) }
                        ?: "Nessuna regola",
                    accent = Amber,
                    onClick = onOpenAutomations,
                    modifier = Modifier.weight(3f),
                    rougeIcon = R.drawable.rouge_ic_automations,
                )
                StatTile(
                    icon = Icons.Filled.FolderOpen,
                    label = "Archivio",
                    value = localArchiveCount.toString(),
                    unit = "file e note",
                    footer = "Apri",
                    accent = Cyan,
                    onClick = onOpenArchive,
                    modifier = Modifier.weight(1f),
                )
            }

            // --- Agenda: date block + week strip + vertical timeline ------
            // Right after Automazioni, per the requested order. One cohesive
            // view of the real Agenda.md: today's date, the week with the
            // active day ringed, then a timeline of appointments and tasks
            // with an "In arrivo"/"Completato" badge.
            AgendaBlock(
                today = today,
                week = upcoming,
                allEntries = allEntries,
                timeline = timeline,
                selectedDate = selectedDate,
                dayEntries = Agenda.sorted(allEntries.filter { it.date == selectedDate }),
                onEditAlerts = { editingAlerts = it },
                onDayClick = { selectedDate = it },
                onToggleDone = { viewModel.toggleDone(it) },
            )

            // JARVIS Drive: la mappa/navigazione interna di JARVIS (motore
            // proprio, traffico live, ricerca destinazioni) — sostituisce i
            // vecchi ingressi separati "Navigazione" (mappa offline semplice)
            // e "Modalità Guida" (overlay su Google Maps). Full-width like
            // Automazioni — an entry point, not a metric.
            Row(Modifier.fillMaxWidth()) {
                StatTile(
                    icon = Icons.Filled.DirectionsCar,
                    label = "JARVIS Drive",
                    value = "Pronta",
                    unit = "mappa interna",
                    footer = "Naviga · traffico live · ricerca",
                    accent = Rose,
                    onClick = { context.startActivity(DrivingModeActivity.intent(context)) },
                    modifier = Modifier.weight(1f),
                    rougeIcon = R.drawable.rouge_ic_drive,
                )
            }

            // Live Translator: a full-width entry point, like Automations. Shows
            // how many of the current pair's offline models are ready so the user
            // knows before they open it whether it works in airplane mode.
            val translatorVm: com.simone.jarvismobile.ui.livetranslate.LiveTranslatorViewModel = hiltViewModel()
            val trPairA by translatorVm.languageA.collectAsStateWithLifecycle()
            val trPairB by translatorVm.languageB.collectAsStateWithLifecycle()
            val trModels by translatorVm.modelStatuses.collectAsStateWithLifecycle()
            val trReady = listOf(trPairA, trPairB).count {
                trModels[it] == com.simone.jarvismobile.translate.ModelStatus.DOWNLOADED
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    icon = Icons.Filled.Translate,
                    label = "Traduttore Live",
                    value = "${trPairA.code.uppercase()}⇄${trPairB.code.uppercase()}",
                    unit = "coppia",
                    footer = if (trReady >= 2) "Pronto offline" else "Scarica i modelli",
                    accent = Cyan,
                    onClick = onOpenTranslator,
                    modifier = Modifier.weight(1f),
                    rougeIcon = R.drawable.rouge_ic_translate,
                )
            }

            // --- Row: Casa + Sistema (2 columns) --------------------------
            // Casa stays DEMO on purpose: no Home Assistant integration exists
            // yet (phase 7, not started) — nothing behind it could be made real
            // without inventing data. Sistema's Sync/Backup rows are no longer
            // DEMO: they read the real BackupRepository/SettingsRepository state
            // that already powers the Backup screen, not a placeholder "Off".
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f), badge = { DemoBadge("F7") }) {
                    CardHeader(Icons.Filled.Home, "CASA")
                    ToggleRow(Icons.Filled.Lightbulb, "Luci", "Soggiorno", on = true)
                    ToggleRow(Icons.Filled.AcUnit, "Clima", "22°C", on = false)
                    ToggleRow(Icons.Filled.Security, "Sicurezza", "Inserito", on = true)
                }
                GlassCard(Modifier.weight(1f)) {
                    CardHeader(Icons.Filled.Memory, "SISTEMA", reserveEnd = false, rougeIcon = R.drawable.rouge_ic_system)
                    val aiOnline = loadState == LlmLoadState.LOADED
                    SystemRow(
                        Icons.Filled.SmartToy, "AI",
                        when {
                            aiOnline -> "Online"
                            loadState == LlmLoadState.LOADING -> "Carico…"
                            else -> "Offline"
                        },
                        if (aiOnline) Green else Muted,
                    )
                    SystemRow(
                        Icons.Filled.Memory, "Note",
                        if (memory.configured) "${memory.noteCount}" else "—",
                        if (memory.configured) Cyan else Muted,
                    )
                    SystemRow(
                        Icons.Filled.Sync, "Sync",
                        if (backupCloudEnabled) "On" else "Off",
                        if (backupCloudEnabled) Green else Muted,
                    )
                    SystemRow(
                        Icons.Filled.CloudUpload, "Backup",
                        relativeBackupLabel(backupState.lastBackupAt),
                        if (backupState.lastBackupAt > 0L) Green else Muted,
                    )
                }
            }

            // --- Obsidian --------------------------------------------------
            GlassCard(badge = { if (!memory.configured) DemoBadge("NO VAULT") }) {
                CardHeader(Icons.Filled.Book, "OBSIDIAN / NOTE")
                NoteRow(Icons.Filled.FolderOpen, "Knowledge Base",
                    if (memory.configured) "${memory.noteCount} note" else "Collega un vault", onOpenMemory)
                NoteRow(Icons.Filled.Description, "Frammenti indicizzati",
                    if (memory.configured) "${memory.chunkCount}" else "—", onOpenMemory)
                NoteRow(Icons.Filled.Star, "Preferiti", "—", onOpenMemory, demo = true)
            }

            Spacer(Modifier.height(72.dp)) // room so the FAB never covers the last card
        }

        // Floating, collapsible written-chat button — now the ONLY way to
        // open the written chat. end 26dp -> 2dp (§ richiesta esplicita:
        // "icona del messaggio molto più a destra" — the opposite direction
        // from the previous round), bottom unchanged.
        ChatFab(
            unread = unread,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 4.dp),
            onClick = { viewModel.markChatSeen(); onOpenChat() },
        )
    }
    }

    editingAlerts?.let { entry ->
        ReminderAlertDialog(
            entry = entry,
            onDismiss = { editingAlerts = null },
            onSave = { alerts ->
                viewModel.updateAlerts(entry.id, alerts)
                editingAlerts = null
            },
        )
    }

}

// --- Building blocks -------------------------------------------------------

@Composable
private fun ChatFab(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Read once here (composable context) — Canvas's draw lambda below is a plain
    // DrawScope block, not @Composable, so it cannot read Cyan (a composable
    // getter) directly; it closes over this already-resolved value instead.
    val cyan = Cyan
    val themeId = LocalJarvisThemeId.current
    val rouge = themeId == JarvisThemeId.ROUGE
    // 112dp non 92dp (§ "icona della chat leggermente più grande" — il primo
    // aumento non è bastato, richiesto ancora più grande).
    Box(modifier.size(if (rouge) 112.dp else 78.dp), contentAlignment = Alignment.Center) {
        // A light HUD ring, drawn for the default/Rosso look only — Rouge's
        // artwork already has its own rings and glow baked in (§ richiesta
        // esplicita dell'utente: "togli il cerchio intorno a icona del
        // messaggio... e rendila più grande"), same reasoning already
        // documented for JarvisOrb: a second ring on top of the art's own
        // reads as a mismatched circle rather than a HUD accent.
        if (!rouge) {
            Canvas(Modifier.size(70.dp)) {
                val r = size.minDimension / 2f - 1.dp.toPx()
                val c = center
                drawCircle(cyan.copy(alpha = 0.18f), radius = r, center = c, style = Stroke(0.8.dp.toPx()))
                // Arcs on the left and bottom only; the badge owns the top-right.
                listOf(110f, 175f, 245f).forEach { start ->
                    drawArc(
                        color = cyan.copy(alpha = 0.5f),
                        startAngle = start,
                        sweepAngle = 38f,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        // Rouge gets real dedicated art (§ richiesta esplicita dell'utente,
        // nuova immagine fornita) instead of a tinted recolour, same pattern
        // already established for JarvisOrb/JarvisCard on this theme — a rich,
        // non-monochrome image would flatten badly under a flat SrcIn tint.
        // Rosso keeps the original shared asset, tinted, as before.
        Image(
            painter = painterResource(if (rouge) R.drawable.rouge_chat_fab else R.drawable.chat_fab),
            contentDescription = "Chat",
            // Clipped to a circle before the ripple is attached (§ richiesta
            // esplicita dell'utente: "non mettere effetto quadrato che esce
            // fuori quando clicco icona per la chat") — without this, the
            // ripple fills the Image's square layout box, visibly spilling
            // past the round artwork into its transparent corners.
            modifier = Modifier
                .size(if (rouge) 104.dp else 66.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
            colorFilter = when (themeId) {
                JarvisThemeId.BLU, JarvisThemeId.ROUGE -> null
                else -> ColorFilter.tint(Cyan)
            },
        )
        if (unread > 0) {
            // A glass ring with a neon numeral, not a red dot: red is an alarm
            // colour and reads as an error next to a cyan HUD.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0x33081521))
                    .border(1.dp, Cyan.copy(alpha = 0.75f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 9) "9+" else "$unread",
                    color = Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Cyan,
                            offset = Offset.Zero,
                            blurRadius = 14f,
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    badge: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    JarvisCard(
        modifier = modifier,
        contentPadding = 20.dp,
        badge = badge?.let { b -> { b() } },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * One of the four tiles across the top: icon + label, a big number, its unit,
 * and a footer line that says what the number means. Tapping opens the screen
 * that owns the data — a tile that shows a count and does nothing is furniture.
 */
@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    footer: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rougeIcon: Int? = null,
) {
    JarvisCard(modifier = modifier.clickable(onClick = onClick), contentPadding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(icon, rougeIcon, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                color = Ink,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = accent, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(unit, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                footer,
                color = accent.copy(alpha = 0.85f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun MiniCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    JarvisCard(modifier = modifier, contentPadding = 14.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            content = content,
        )
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, reserveEnd: Boolean = true, rougeIcon: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = if (reserveEnd) 30.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedIcon(icon, rougeIcon, tint = Cyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            color = Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DemoBadge(text: String = "DEMO") {
    Text(
        text = text,
        color = Amber,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Amber.copy(alpha = 0.14f))
            .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The agenda, in the reference layout: a date block, the week strip with today
 * ringed, and a vertical timeline of appointments and tasks. Everything is the
 * real `Agenda.md` — [today] for the counts, [week] (still-open items) for the
 * strip dots, and [timeline] (today's done items plus everything ahead) for the
 * rows, so the "Completato" badge is real rather than decoration.
 */
@Composable
private fun AgendaBlock(
    today: List<AgendaEntry>,
    week: List<AgendaEntry>,
    allEntries: List<AgendaEntry>,
    timeline: List<AgendaEntry>,
    selectedDate: LocalDate,
    dayEntries: List<AgendaEntry>,
    onEditAlerts: (AgendaEntry) -> Unit,
    onDayClick: (LocalDate) -> Unit = {},
    onToggleDone: (AgendaEntry) -> Unit = {},
) {
    val todayDate = LocalDate.now()
    val isToday = selectedDate == todayDate
    val doneOnDay = dayEntries.count { it.done }

    // Which calendar week the strip shows. 0 = the week containing today; the
    // arrows step it back/forward. A real Monday→Sunday week, not "7 days from
    // today", so it lines up with a paper calendar and rolls over on its own.
    var weekOffset by remember { mutableStateOf(0) }
    val thisMonday = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong())
    val weekStart = thisMonday.plusWeeks(weekOffset.toLong())
    val weekEnd = weekStart.plusDays(6)

    GlassCard {
        CardHeader(Icons.Filled.CalendarMonth, "AGENDA", reserveEnd = false, rougeIcon = R.drawable.rouge_ic_agenda)

        // Week navigator: ‹  10 – 16 agosto  ›
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { weekOffset-- }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Settimana precedente",
                    tint = Cyan,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                weekRangeLabel(weekStart, weekEnd),
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { weekOffset++ }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Settimana successiva",
                    tint = Cyan,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Date block — reflects the SELECTED day, not always today.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    selectedDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALIAN).uppercase(),
                    color = Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "${selectedDate.dayOfMonth}",
                    color = Ink,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(color = Cyan, offset = Offset.Zero, blurRadius = 22f),
                    ),
                )
                Text(
                    selectedDate.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
                        .replaceFirstChar { it.uppercase() },
                    color = Muted,
                    fontSize = 11.sp,
                )
                Text(
                    if (dayEntries.isEmpty()) {
                        if (isToday) "Niente oggi" else "Niente"
                    } else {
                        "${dayEntries.size} impegni · $doneOnDay fatti"
                    },
                    color = Muted,
                    fontSize = 9.sp,
                )
            }

            // Week strip: Monday→Sunday of the shown week; selected day ringed.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (i in 0 until 7) {
                    val date = weekStart.plusDays(i.toLong())
                    WeekDayPip(
                        date = date,
                        isToday = date == todayDate,
                        isSelected = date == selectedDate,
                        hasEntries = allEntries.any { it.date == date },
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(6.dp))

        if (dayEntries.isEmpty()) {
            Text(
                if (isToday) {
                    "Nessun impegno.\nDì «ricordami di … domani alle 15»."
                } else {
                    "Niente in programma per ${selectedDate.dayOfMonth} " +
                        selectedDate.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN) + "."
                },
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        } else {
            val shown = dayEntries.take(8)
            shown.forEachIndexed { i, e ->
                TimelineEntry(
                    entry = e,
                    dot = if (e.done) Green else when (i % 3) { 0 -> Cyan; 1 -> Blue; else -> Violet },
                    last = i == shown.lastIndex,
                    onEditAlerts = { onEditAlerts(e) },
                    onToggleDone = { onToggleDone(e) },
                )
            }
        }
    }
}

/** "10 – 16 agosto", or "28 lug – 3 ago" when the week straddles two months. */
private fun weekRangeLabel(start: LocalDate, end: LocalDate): String {
    fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    return if (start.month == end.month) {
        "${start.dayOfMonth} – ${end.dayOfMonth} " +
            cap(start.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN))
    } else {
        "${start.dayOfMonth} ${start.month.getDisplayName(TextStyle.SHORT, Locale.ITALIAN)} – " +
            "${end.dayOfMonth} ${end.month.getDisplayName(TextStyle.SHORT, Locale.ITALIAN)}"
    }
}

/** One day in the week strip. The selected day is ringed; a dot marks entries. */
@Composable
private fun WeekDayPip(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasEntries: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ITALIAN).uppercase(),
            color = if (isToday) Cyan else Muted,
            fontSize = 9.sp,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .then(
                    if (isSelected) {
                        Modifier
                            .background(Cyan.copy(alpha = 0.16f))
                            .border(1.5.dp, Cyan, androidx.compose.foundation.shape.CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                color = if (isSelected) Cyan else Ink,
                fontSize = 13.sp,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Box(
            Modifier
                .size(5.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (hasEntries) Cyan else Color.Transparent),
        )
    }
}

/**
 * One timeline row: the time (or a dash for an untimed task), a dot on a
 * connecting line, the title and its day, and an "In arrivo"/"Completato" badge.
 * Tapping the bell edits the entry's reminder alerts (Phase 6e).
 */
@Composable
private fun TimelineEntry(
    entry: AgendaEntry,
    dot: Color,
    last: Boolean,
    onEditAlerts: () -> Unit,
    onToggleDone: () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        // Time column.
        Column(
            modifier = Modifier.width(46.dp).padding(top = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                entry.time?.let { Agenda.humanTime(it) } ?: "—",
                color = if (entry.done) Muted else dot,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Dot + connector — tap the dot to complete/uncomplete.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onToggleDone() }
                    .then(if (entry.done) Modifier.background(dot) else Modifier.border(2.dp, dot, androidx.compose.foundation.shape.CircleShape)),
            )
            if (!last) Box(Modifier.width(2.dp).height(30.dp).background(Color(0x1AFFFFFF)))
        }
        Spacer(Modifier.width(10.dp))
        // Title + day + badge.
        Column(Modifier.weight(1f)) {
            Text(
                entry.text,
                color = if (entry.done) Muted else Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(Agenda.fullDate(entry.date, LocalDate.now()), color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            StatusBadge(done = entry.done)
        }
        IconButton(onClick = onEditAlerts, modifier = Modifier.size(30.dp)) {
            Icon(
                if (entry.alerts.isEmpty()) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                contentDescription = "Imposta avvisi",
                tint = if (entry.alerts.isEmpty()) Amber else Green,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** "In arrivo" (cyan) or "Completato" (green) pill. */
@Composable
private fun StatusBadge(done: Boolean) {
    val color = if (done) Green else Cyan
    val label = if (done) "Completato" else "In arrivo"
    Text(
        text = label,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ReminderAlertDialog(
    entry: AgendaEntry,
    onDismiss: () -> Unit,
    onSave: (List<ReminderAlert>) -> Unit,
) {
    var selected by remember(entry.id, entry.alerts) {
        mutableStateOf(entry.alerts.filter { it.type != ReminderAlertType.CUSTOM }.map { it.type }.toSet())
    }
    var custom by remember(entry.id, entry.alerts) {
        mutableStateOf(
            entry.alerts.firstOrNull { it.type == ReminderAlertType.CUSTOM }
                ?.customAt?.toString().orEmpty(),
        )
    }
    var customError by remember(entry.id) { mutableStateOf(false) }
    val options = listOfNotNull(
        ReminderAlertType.AT_TIME.takeIf { entry.time != null }?.let { it to "All'ora dell'impegno" },
        ReminderAlertType.MORNING_OF to "La mattina stessa",
        ReminderAlertType.ONE_DAY_BEFORE to "1 giorno prima",
        ReminderAlertType.TWO_DAYS_BEFORE to "2 giorni prima",
        ReminderAlertType.THREE_DAYS_BEFORE to "3 giorni prima",
        ReminderAlertType.ONE_WEEK_BEFORE to "1 settimana prima",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Avvisi · ${entry.text}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (type, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (type in selected) selected - type else selected + type
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = type in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + type else selected - type
                            },
                        )
                        Text(label)
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; customError = false },
                    label = { Text("Personalizzato (AAAA-MM-GG HH:MM)") },
                    supportingText = if (customError) {
                        { Text("Data o ora non valida") }
                    } else {
                        null
                    },
                    isError = customError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onSave(emptyList()) }) { Text("Nessun avviso") }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val customAt = custom.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDateTime.parse(it.replace(' ', 'T')) }.getOrNull()
                    }
                    if (custom.isNotBlank() && customAt == null) {
                        customError = true
                    } else {
                        val alerts = selected.map { ReminderAlert(it) }.toMutableList()
                        customAt?.let { alerts += ReminderAlert(ReminderAlertType.CUSTOM, it) }
                        onSave(alerts)
                    }
                },
            ) { Text("Salva") }
        },
    )
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, on: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33081521))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Ink, fontSize = 13.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            // Cyan (the theme accent), not a hardcoded Blue — on Rosso/Rouge/
            // Ares this switch was always blue regardless of theme (§
            // richiesta esplicita dell'utente: "lo voglio esattamente
            // identico" al riferimento, dove l'interruttore è rosso).
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Cyan,
                checkedBorderColor = Cyan,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Color(0x33FFFFFF),
                uncheckedBorderColor = Color(0x33FFFFFF),
            ),
        )
    }
}

@Composable
private fun SystemRow(icon: ImageVector, title: String, value: String, valueColor: Color, demo: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Ink, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (demo) { Spacer(Modifier.width(6.dp)); DemoBadge() }
        }
    }
}

@Composable
private fun NoteRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit, demo: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Ink, fontSize = 13.sp)
                Text(value, color = Muted, fontSize = 11.sp)
            }
        }
        if (demo) DemoBadge()
    }
}

@Composable
private fun ListenOrb(
    accent: Color,
    title: String,
    subtitle: String,
    active: Boolean,
    mode: OrbMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    // Breathing halo — faster and stronger while active.
    val glow by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = if (active) 0.85f else 0.5f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 900 else 2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glow",
    )
    // Sweeping arc for the thinking/loading state.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "sweep",
    )
    // Outward ripple while listening.
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "ripple",
    )
    // Waveform bars while speaking.
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave",
    )
    // Gentle scale pulse while listening.
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (mode == OrbMode.LISTENING) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Luminous halo + state animations, drawn around the circular orb.
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = glow * 0.55f), Color.Transparent), center = c, radius = r),
                radius = r, center = c,
            )
            when (mode) {
                OrbMode.LISTENING -> {
                    // Two expanding, fading rings.
                    for (i in 0..1) {
                        val p = ((ripple + i * 0.5f) % 1f)
                        drawCircle(
                            color = accent.copy(alpha = (1f - p) * 0.5f),
                            radius = r * (0.62f + p * 0.36f),
                            center = c,
                            style = Stroke(width = r * 0.022f),
                        )
                    }
                }
                OrbMode.THINKING -> {
                    // Rotating arc chasing around the orb.
                    val inset = r * 0.10f
                    drawArc(
                        color = accent.copy(alpha = 0.95f),
                        startAngle = sweep,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(c.x - r + inset, c.y - r + inset),
                        size = Size((r - inset) * 2f, (r - inset) * 2f),
                        style = Stroke(width = r * 0.035f, cap = StrokeCap.Round),
                    )
                }
                else -> Unit
            }
        }

        // The orb image — perfectly circular, never stretched or clipped.
        Image(
            painter = painterResource(R.drawable.orb),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(pulse),
            contentScale = ContentScale.Fit,
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (mode == OrbMode.SPEAKING) {
                // Animated waveform bars.
                Canvas(Modifier.size(34.dp, 20.dp)) {
                    val bars = 5
                    val bw = size.width / (bars * 2f)
                    for (i in 0 until bars) {
                        val phase = kotlin.math.sin((wave * Math.PI + i).toFloat()).let { kotlin.math.abs(it) }
                        val hgt = size.height * (0.28f + 0.72f * phase)
                        val x = bw + i * bw * 2f
                        drawLine(
                            color = Color.White,
                            start = Offset(x, size.height / 2f - hgt / 2f),
                            end = Offset(x, size.height / 2f + hgt / 2f),
                            strokeWidth = bw * 0.9f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            } else {
                Icon(Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(subtitle, color = Ink.copy(alpha = 0.85f), fontSize = 8.sp)
        }
    }
}

/** What the status line under the wordmark says, and in which colour. */
private data class HeroStatus(val label: String, val color: Color)

/**
 * Brand accent at rest and while listening (both were always meant to track
 * it — see [accentFor]'s equivalent "else -> cyan" branch), violet while
 * thinking, aqua while answering. [accent] is the live theme accent, read
 * once in the caller's composable scope since this function itself is not
 * @Composable.
 */
private fun statusFor(state: ConversationState, hasError: Boolean, accent: Color): HeroStatus = when {
    hasError -> HeroStatus("ERRORE", Color(0xFFFF6B5B))
    state == ConversationState.Listening || state == ConversationState.FollowUpWindow ->
        HeroStatus("ASCOLTO", accent)
    state == ConversationState.Speaking -> HeroStatus("RISPOSTA", Color(0xFF4FE3C1))
    state in setOf(
        ConversationState.PreparingAudio, ConversationState.FinalizingSpeech,
        ConversationState.Transcribing, ConversationState.RetrievingMemory,
        ConversationState.Routing, ConversationState.ThinkingLocal,
        ConversationState.ThinkingRemote, ConversationState.ExecutingTool,
    ) -> HeroStatus("PENSANDO", Color(0xFF9B7BFF))
    else -> HeroStatus("PRONTO", accent)
}

/** Which animation the orb should play. */
private enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Maps the real conversation state onto the orb's visual state. The orb is the
 * status indicator, not decoration, so an error must look like an error rather
 * than like idling.
 */
private fun orbStateFor(state: ConversationState, hasError: Boolean): OrbState = when {
    hasError -> OrbState.ERROR
    state == ConversationState.Listening || state == ConversationState.FollowUpWindow -> OrbState.LISTENING
    state == ConversationState.Speaking -> OrbState.SPEAKING
    state in setOf(
        ConversationState.PreparingAudio, ConversationState.FinalizingSpeech,
        ConversationState.Transcribing, ConversationState.RetrievingMemory,
        ConversationState.Routing, ConversationState.ThinkingLocal,
        ConversationState.ThinkingRemote, ConversationState.ExecutingTool,
    ) -> OrbState.THINKING
    else -> OrbState.IDLE
}

private fun orbModeFor(state: ConversationState): OrbMode = when (state) {
    ConversationState.Listening, ConversationState.FollowUpWindow -> OrbMode.LISTENING
    ConversationState.Speaking -> OrbMode.SPEAKING
    ConversationState.PreparingAudio, ConversationState.FinalizingSpeech, ConversationState.Transcribing,
    ConversationState.RetrievingMemory, ConversationState.Routing,
    ConversationState.ThinkingLocal, ConversationState.ThinkingRemote,
    ConversationState.ExecutingTool -> OrbMode.THINKING
    else -> OrbMode.IDLE
}

// --- Battery ---------------------------------------------------------------

data class BatteryStatus(val percent: Int, val charging: Boolean)

@Composable
fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(BatteryStatus(100, false)) }
    DisposableEffect(Unit) {
        fun read(intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else status.percent
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            status = BatteryStatus(pct, plugged)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        read(sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return status
}

private fun vibrateOnce(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
}

// --- State helpers ---------------------------------------------------------

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle, ConversationState.Cancelled,
    is ConversationState.RecoverableError, is ConversationState.FatalError,
    ConversationState.PermissionRequired, ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable, ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable -> true
    else -> false
}

/** "Mai" / "Oggi" / "Ieri" / "N giorni fa" for the Sistema card's real Backup row. */
private fun relativeBackupLabel(epochMs: Long): String {
    if (epochMs <= 0L) return "Mai"
    val days = (System.currentTimeMillis() - epochMs) / 86_400_000L
    return when {
        days <= 0L -> "Oggi"
        days == 1L -> "Ieri"
        days < 7L -> "$days giorni fa"
        else -> "Oltre una settimana fa"
    }
}

private fun accentFor(state: ConversationState, cyan: Color): Color = when {
    state == ConversationState.Listening || state == ConversationState.FollowUpWindow -> Green
    state == ConversationState.Speaking -> Blue
    state is ConversationState.RecoverableError || state is ConversationState.FatalError -> Color(0xFFE74C3C)
    else -> cyan
}

private fun orbTitle(state: ConversationState): String = when (state) {
    ConversationState.Idle -> "PRONTO"
    ConversationState.Listening, ConversationState.FollowUpWindow -> "ASCOLTO"
    ConversationState.Speaking -> "PARLO"
    ConversationState.ThinkingLocal, ConversationState.ThinkingRemote,
    ConversationState.Transcribing, ConversationState.RetrievingMemory, ConversationState.Routing -> "PENSO"
    is ConversationState.RecoverableError, is ConversationState.FatalError -> "ERRORE"
    else -> "…"
}

private fun orbSubtitle(state: ConversationState): String = when (state) {
    ConversationState.Idle -> "Tocca per parlare"
    ConversationState.Listening, ConversationState.FollowUpWindow -> "Ti ascolto"
    ConversationState.Speaking -> "Tocca per fermare"
    else -> ""
}

// ============================================================================
// --- Tema Ares: Home layout dedicato ---------------------------------------
// ============================================================================
//
// Un layout Home completamente diverso da Classico/Rosso/Rouge (§ richiesta
// esplicita dell'utente: "creiamo un altro tema... cambiamo anche le
// impostazioni dei blocchi, cambiano solo quelli di questo tema senza
// toccare gli altri"), costruito dalle immagini di riferimento fornite
// dall'utente. Riusa AgendaBlock/GlassCard/CardHeader/ToggleRow/Cyan/Ink/
// Muted già definiti sopra in questo file (private ad esso — per questo
// AresHomeScreen vive qui invece che in un file separato) e gli stessi
// ViewModel/stato reale che il layout classico già raccoglie: solo la
// disposizione e gli sfondi dei blocchi sono nuovi, mai una seconda fonte
// dati per lo stesso fatto.

// Banner unico fornito dall'utente (§ "tutte e 4"), 1200x286 reale dopo il
// ritaglio al bounding box alpha. Allargato un po' in altezza (§ richiesta
// esplicita "allarga un po' riquadro dell'orb in altezza") abbassando
// l'aspect ratio: la Image usa FillBounds, quindi l'artwork si allunga
// leggermente invece di lasciare spazio vuoto (Fit lo avrebbe solo
// centrato con margini, non ingrandito davvero).
private const val ARES_ORB_BG_ASPECT_RATIO = 1200f / 340f
// Bug reale segnalato dall'utente da screenshot ("togli assolutamente
// sfondi bianchi dietro i blocchi"): le immagini sorgente di METEO/
// PROMEMORIA/MEMORIA avevano alpha=255 OVUNQUE con un fondo quasi-bianco —
// non trasparenza vera, a differenza di quanto assunto nei giri precedenti
// — quindi il crop-al-bbox-alpha non tagliava nulla e il fondo bianco
// finiva dritto nell'asset. Ricostruita l'alpha reale via flood-fill dai
// bordi dell'immagine (solo il bianco CONNESSO al bordo diventa
// trasparente — mai il testo bianco interno, isolato dal nero della card)
// e ri-misurate le dimensioni reali dopo il ritaglio corretto.
private const val ARES_REMINDERS_ASPECT_RATIO = 659f / 700f
private const val ARES_SHORTCUTS_ASPECT_RATIO = 1100f / 234f
private const val ARES_MEMORY_ASPECT_RATIO = 695f / 700f
private const val ARES_METEO_ASPECT_RATIO = 457f / 700f
// BPM/Sonno: il singolo template a due pannelli è stato separato in due
// asset indipendenti (§ richiesta esplicita: "allarga poco i battiti e il
// sonno, anche distanziandoli un po'" — lo spazio fra i due pannelli era
// cotto nell'unica immagine, senza possibilità di controllarlo da Compose).
private const val ARES_BPM_ASPECT_RATIO = 695f / 345f
private const val ARES_SONNO_ASPECT_RATIO = 695f / 335f
// "poco" = 8% in più di altezza per ciascun pannello rispetto alla propria
// proporzione reale (mild FillBounds stretch, già lo stesso meccanismo
// usato per l'orb qui sopra).
private const val ARES_BPM_SONNO_GROW = 1.08f
private val ARES_BPM_SONNO_GAP = 10.dp

@Composable
internal fun AresHomeScreen(
    name: String,
    state: ConversationState,
    hasError: Boolean,
    today: List<AgendaEntry>,
    upcoming: List<AgendaEntry>,
    allEntries: List<AgendaEntry>,
    timeline: List<AgendaEntry>,
    selectedDate: LocalDate,
    unread: Int,
    proModeActive: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onOrbClick: () -> Unit,
    onToggleDone: (AgendaEntry) -> Unit,
    onEditAlerts: (AgendaEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenDrive: () -> Unit,
    onOpenTranslator: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    val aresViewModel: AresViewModel = hiltViewModel()
    val outlook by aresViewModel.outlook.collectAsStateWithLifecycle()
    val healthGranted by aresViewModel.healthGranted.collectAsStateWithLifecycle()
    val healthAverages by aresViewModel.healthAverages.collectAsStateWithLifecycle()
    val healthContext = LocalContext.current
    // Causa radice reale trovata (§ "premendo concedi accesso non succede
    // niente", confermato dall'utente: nemmeno un popup di sistema appare
    // per un istante): rememberLauncherForActivityResult richiede lo STESSO
    // oggetto contract a ogni ricomposizione — passare
    // aresViewModel.healthPermissionContract() diretto costruiva una nuova
    // istanza di PermissionController.createRequestPermissionResultContract()
    // a ogni ricomposizione (che qui capita spesso: outlook/healthAverages
    // sono StateFlow che ricompongono questo intero schermo). Ogni
    // ricomposizione quindi deregistrava e riregistrava il launcher presso
    // l'ActivityResultRegistry; se il tocco arrivava proprio in quella
    // finestra, launch() falliva silenziosamente senza lanciare eccezione —
    // coerente con "nessun crash, nessun popup". Fissata con remember, così
    // il contract è un'unica istanza stabile per tutta la vita di questo
    // composable.
    val healthPermissionContract = remember(aresViewModel) { aresViewModel.healthPermissionContract() }
    // Bug segnalato di nuovo dall'utente anche DOPO la stabilizzazione del
    // contract qui sopra ("cliccabile ma comunque non apre e non succede
    // nulla") -- quindi quel fix, per quanto reale, non era l'unica causa o
    // non lo era affatto. Senza log di dispositivo in questo ambiente non e'
    // verificabile con certezza se la Activity di Health Connect non si apre
    // proprio, oppure si apre e si richiude all'istante restituendo un
    // risultato vuoto (es. un provider OEM che risponde ma non mostra mai
    // davvero la UI). Il callback ora mostra sempre un Toast col conteggio
    // reale dei permessi concessi: se non compare NEMMENO questo Toast, il
    // round-trip non si completa affatto (causa piu' a monte, fuori da
    // questo composable); se compare con "0 concessi", l'activity si apre e
    // chiude subito senza che l'utente riesca a interagire.
    val healthLauncher = rememberLauncherForActivityResult(
        contract = healthPermissionContract,
    ) { granted ->
        android.widget.Toast.makeText(
            healthContext,
            "Health Connect: ${granted.size} permessi concessi",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        aresViewModel.refreshHealth()
    }
    // Bug reale segnalato dall'utente: "premendo concedi accesso non succede
    // niente" — nessun crash, quindi non un'eccezione non gestita, ma senza
    // log visibile in questo ambiente non è verificabile con certezza se il
    // launcher stesso fallisce silenziosamente o se la richiesta apre e
    // richiude senza aggiornare lo stato. Questo try/catch rende visibile
    // (Toast) un eventuale fallimento reale del lancio invece di lasciarlo
    // silenzioso, così il prossimo tentativo dice qualcosa di concreto.
    val onRequestHealthConnect: () -> Unit = {
        runCatching { healthLauncher.launch(aresViewModel.healthPermissions) }
            .onFailure {
                android.widget.Toast.makeText(
                    healthContext,
                    "Impossibile aprire Health Connect: ${it.message}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    val archiveVm: com.simone.jarvismobile.ui.archive.ArchiveViewModel = hiltViewModel()
    val storageUsage by archiveVm.storageUsage.collectAsStateWithLifecycle()

    val dayEntries = Agenda.sorted(allEntries.filter { it.date == selectedDate })
    // Solo i primi 3 di OGGI non completati (§ richiesta esplicita: "andranno
    // in ogni riga dei promemoria, i primi 3 della giornata che devono
    // ancora essere completati") — [today] è già la lista degli impegni di
    // oggi (stesso parametro usato dal blocco Sistema del tema classico).
    val todayReminders = Agenda.sorted(today.filter { !it.done }).take(3)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        // Sfondo dedicato Atena (§ richiesta esplicita dell'utente, "tutte e
        // 4" le immagini rimaste inutilizzate del giro precedente) — prima
        // condiviso con Rouge su richiesta esplicita di un turno precedente,
        // ora sostituito perché l'utente ha chiesto esplicitamente di usare
        // anche questa immagine.
        Image(
            painter = painterResource(R.drawable.ares_bg_dashboard),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        HudOverlay(Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            // Spaziatura complessiva ridotta (§ richiesta esplicita: "di
            // conseguenza avvicina un po' tutto") 12dp->9dp.
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // --- Titolo JARVIS + "PRONTO", raggruppati in un'unica colonna
            // stretta (§ richiesta esplicita: "la scritta pronto mettila più
            // vicina alla scritta atena") invece di due elementi separati
            // spaziati dalla stessa distanza usata per il resto della
            // pagina — box del titolo abbassato 64dp->46dp (meno spazio
            // vuoto sotto il testo centrato) e i due elementi ravvicinati
            // con spacedBy(0dp) invece della spaziatura generale.
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Wordmark "ATENA" (§ "tutte e 4" — richiesta esplicita
                    // dell'utente di usare anche questa immagine, rimasta
                    // inutilizzata nel giro precedente). Onestà: questa è
                    // un'immagine statica con la scritta "ATENA" fissa, non
                    // più il nome configurabile [name] — se l'utente
                    // rinomina l'assistente da Impostazioni, il titolo del
                    // tema Atena non lo rifletterà più.
                    Image(
                        painter = painterResource(R.drawable.ares_wordmark),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp),
                    )
                    if (proModeActive) {
                        com.simone.jarvismobile.ui.components.ProModeBadge(
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    Image(
                        painter = painterResource(R.drawable.rouge_ic_menu),
                        contentDescription = "Impostazioni",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(32.dp)
                            .clickable(onClick = onOpenSettings),
                    )
                }

                // --- Riga di stato: "•PRONTO•" compatto, un solo pallino per
                // lato (§ richiesta esplicita dell'utente: "intorno a Pronto
                // non devono esserci due pallini a lato, ma uno" — correzione
                // del doppio pallino usato in precedenza), non i due pallini
                // distanziati usati dagli altri temi.
                val status = statusFor(state, hasError, Cyan)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "•${status.label}•",
                        color = status.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp,
                    )
                }
            }

            // --- Orb dentro il suo sfondo rettangolare, tutto il blocco
            // cliccabile (§ richiesta esplicita: "Orb e tutto il blocco deve
            // essere cliccabile") — prima solo l'immagine dell'orb reagiva al
            // tocco, ora l'intero banner (compreso lo sfondo con i circuiti).
            // Banner refinement fornito dall'utente (§ "tutte e 4"): un'unica
            // immagine con cornice+circuiti+orb già integrati, al posto dei
            // due layer precedenti (ares_orb_bg sotto + ares_orb sopra) — il
            // nuovo artwork porta già l'orb disegnato al suo interno.
            BoxWithConstraints(Modifier.fillMaxWidth().clickable(onClick = onOrbClick)) {
                val bannerHeight = maxWidth / ARES_ORB_BG_ASPECT_RATIO
                Image(
                    painter = painterResource(R.drawable.ares_orb_banner),
                    contentDescription = name,
                    // FillBounds, non Fit (§ "allarga un po' riquadro
                    // dell'orb in altezza"): con Fit l'artwork resterebbe
                    // alla sua dimensione naturale centrata con margini
                    // vuoti sopra/sotto in un box più alto, mai
                    // effettivamente più grande.
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().height(bannerHeight),
                )
            }

            // --- Meteo (card a sé) + BPM/Sonno (card a sé), stessa altezza
            // (§ nuovo mockup: griglia 2 colonne, non più un'unica "Sistema")
            var hourlyDayIndex by remember { mutableStateOf<Int?>(null) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columnWidth = (maxWidth - 10.dp) / 2
                // L'altezza di Meteo ora è DERIVATA dalla somma reale di
                // BPM+gap+Sonno, non dalla propria proporzione (§ richiesta
                // esplicita: "devono essere pari: l'inizio del meteo deve
                // combaciare con inizio dei bpm, la fine del meteo deve
                // combaciare con la fine del sonno") — le due colonne
                // condividono sempre lo stesso totale per costruzione,
                // invece di doverlo indovinare per tentativi.
                val bpmHeight = columnWidth / ARES_BPM_ASPECT_RATIO * ARES_BPM_SONNO_GROW
                val sonnoHeight = columnWidth / ARES_SONNO_ASPECT_RATIO * ARES_BPM_SONNO_GROW
                val cardHeight = bpmHeight + ARES_BPM_SONNO_GAP + sonnoHeight
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AresMeteoCard(
                        outlook = outlook,
                        modifier = Modifier.weight(1f),
                        height = cardHeight,
                        onDayClick = { dayIndex ->
                            hourlyDayIndex = dayIndex
                            aresViewModel.loadHourlyForecast(dayIndex)
                        },
                    )
                    AresBpmSonnoCard(
                        healthAvailable = aresViewModel.healthAvailable,
                        healthGranted = healthGranted,
                        healthSdkStatusLabel = aresViewModel.healthSdkStatusLabel(),
                        healthAverages = healthAverages,
                        onRequestHealth = onRequestHealthConnect,
                        bpmHeight = bpmHeight,
                        sonnoHeight = sonnoHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (hourlyDayIndex != null) {
                val hourly by aresViewModel.hourly.collectAsStateWithLifecycle()
                val hourlyLoading by aresViewModel.hourlyLoading.collectAsStateWithLifecycle()
                AresHourlyForecastSheet(
                    dayIndex = hourlyDayIndex!!,
                    forecast = hourly,
                    loading = hourlyLoading,
                    onDismiss = {
                        hourlyDayIndex = null
                        aresViewModel.clearHourlyForecast()
                    },
                )
            }

            // --- Promemoria + Memoria, stessa larghezza (§ bug reale
            // segnalato dall'utente da screenshot: "fallo più piccolo [...]
            // memoria più grande, devono essere grandi uguali" — prima
            // Promemoria aveva il doppio della larghezza di Memoria, quindi
            // anche un'altezza molto maggiore data la propria proporzione;
            // ora entrambe le colonne pesano uguale e le due card, con
            // proporzioni reali vicine (0.9 e 1.0), risultano quasi identiche
            // senza dover forzare un'altezza condivisa che le deformerebbe).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AresRemindersCard(
                    entries = todayReminders,
                    modifier = Modifier.weight(1f),
                    onOpenSection = onOpenAgenda,
                    onToggleDone = onToggleDone,
                )
                AresMemoryCard(
                    usedBytes = storageUsage.usedBytes,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenArchive,
                )
            }

            // --- Impostazioni / Navigazione / Traduttore (solo icone) ------
            AresShortcutsRow(
                onOpenSettings = onOpenSettings,
                onOpenDrive = onOpenDrive,
                onOpenTranslator = onOpenTranslator,
            )

            // --- Calendario, stesso blocco degli altri temi -----------------
            AgendaBlock(
                today = today,
                week = upcoming,
                allEntries = allEntries,
                timeline = timeline,
                selectedDate = selectedDate,
                dayEntries = dayEntries,
                onEditAlerts = onEditAlerts,
                onDayClick = onSelectDate,
                onToggleDone = onToggleDone,
            )

            // --- Domotica, ancora DEMO come negli altri temi (Fase 7 non iniziata) ---
            GlassCard(badge = { DemoBadge("F7") }) {
                CardHeader(Icons.Filled.Home, "DOMOTICA")
                ToggleRow(Icons.Filled.Lightbulb, "Luci", "Soggiorno", on = true)
                ToggleRow(Icons.Filled.AcUnit, "Clima", "22°C", on = false)
                ToggleRow(Icons.Filled.Security, "Sicurezza", "Inserito", on = true)
            }

            Spacer(Modifier.height(56.dp)) // room so the chat button never covers the last card
        }

        // Pulsante chat, arte dedicata Ares (§ prima immagine del secondo
        // messaggio: un'icona a onda sonora, coerente con un assistente
        // vocale) — già un overlay fluttuante fuori dalla Column che
        // scorre (non occupa spazio nel flusso), come richiesto ("in
        // rilievo non occupando spazio nella home"); qui solo ridotto
        // ("un po' più piccolo di prima") 104dp->88dp, contenitore
        // 112dp->96dp, con lo spazio riservato in coda alla Column
        // ridotto di conseguenza.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 2.dp, bottom = 4.dp)
                // Ridotta di nuovo (§ richiesta esplicita: "l'icona in basso
                // a destra della chat deve essere più piccola") 96dp->72dp.
                .size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ares_chat_fab),
                contentDescription = "Chat",
                modifier = Modifier
                    .size(64.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onOpenChat),
                contentScale = ContentScale.Fit,
            )
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0x33081521))
                        .border(1.dp, Cyan.copy(alpha = 0.75f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (unread > 9) "9+" else "$unread",
                        color = Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** "Sereno"/"Parzialmente nuvoloso"/… — the same five buckets the morning digest already uses. */
private fun weatherCategoryLabel(category: WeatherCategory?): String = when (category) {
    WeatherCategory.CLEAR -> "Sereno"
    WeatherCategory.PARTLY_CLOUDY -> "Parzialmente nuvoloso"
    WeatherCategory.CLOUDY -> "Nuvoloso"
    WeatherCategory.RAIN -> "Pioggia"
    WeatherCategory.THUNDERSTORM -> "Temporale"
    null -> "—"
}

/**
 * Real icon art cropped from the user's own reference images. No dedicated
 * thunderstorm glyph exists in the reference pack — the rain cloud is the
 * closest honest stand-in rather than inventing a fifth icon.
 *
 * [isDay] (§ tema Atena, richiesta esplicita: "per ogni situazione
 * meteorologica apparirà la propria immagine") picks a real day/night variant
 * for CLEAR/PARTLY_CLOUDY, from Open-Meteo's own `is_day` flag — never a
 * local sunrise/sunset guess. CLOUDY/RAIN/THUNDERSTORM have only one variant
 * in the reference pack (a plain cloud mass reads the same day or night), so
 * [isDay] is ignored for those rather than inventing a second asset.
 */
private fun weatherIconRes(category: WeatherCategory?, isDay: Boolean?): Int? = when (category) {
    WeatherCategory.CLEAR -> if (isDay == false) R.drawable.ares_wx_night_clear else R.drawable.ares_wx_clear
    WeatherCategory.PARTLY_CLOUDY -> if (isDay == false) R.drawable.ares_wx_night_cloudy else R.drawable.ares_wx_partly_cloudy
    WeatherCategory.CLOUDY -> R.drawable.ares_wx_cloudy
    WeatherCategory.RAIN -> R.drawable.ares_wx_rain
    WeatherCategory.THUNDERSTORM -> R.drawable.ares_wx_rain
    null -> null
}

@Composable
private fun WeatherIcon(
    category: WeatherCategory?,
    size: androidx.compose.ui.unit.Dp,
    isDay: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val res = weatherIconRes(category, isDay)
    val base = if (onClick != null) Modifier.size(size).clickable(onClick = onClick) else Modifier.size(size)
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = base,
        )
    } else {
        Box(base)
    }
}

/**
 * Meteo di oggi (cliccabile -> previsione oraria) + prossimi 3 giorni
 * (ciascuno cliccabile -> previsione oraria di quel giorno), sul template
 * "METEO" fornito dall'utente. Card a sé, non più affiancata a bpm/sonno
 * dentro un'unica "SISTEMA" (§ nuovo mockup: card separate).
 */
@Composable
private fun AresMeteoCard(
    outlook: WeeklyOutlook?,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    onDayClick: (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = height ?: (w / ARES_METEO_ASPECT_RATIO)
        Box(Modifier.fillMaxWidth().height(h)) {
            Image(
                painter = painterResource(R.drawable.ares_meteo_frame),
                contentDescription = "Meteo",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = w * 0.09f, end = w * 0.07f, top = h * 0.15f, bottom = h * 0.04f),
            ) {
                if (outlook?.currentTempC != null) {
                    // Blocco di oggi centrato orizzontalmente nella card
                    // (§ richiesta esplicita: "quello di oggi non è
                    // centrato") — prima era allineato a sinistra/inizio,
                    // ora l'intero blocco (etichetta, icona+gradi, stato+
                    // vento) è una colonna centrata, come nel riferimento.
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onDayClick(0) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // "Oggi" sopra l'icona (§ richiesta esplicita), stesso
                        // stile delle etichette "Domani"/"Dopodom."/"Tra 3gg".
                        Text("Oggi", color = Muted, fontSize = 7.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            WeatherIcon(outlook.currentCategory, 44.dp, outlook.currentIsDay)
                            Spacer(Modifier.width(6.dp))
                            // Gradi più grandi (§ richiesta esplicita "scritti più
                            // in grande"). Onestà: il font geometrico della foto
                            // di riferimento non è riproducibile qui — nessun
                            // file .ttf fornito e nessun accesso di rete in
                            // questo ambiente per recuperarne uno — resta il
                            // font di sistema, solo ingrandito.
                            Text(
                                "${outlook.currentTempC.roundToInt()}°",
                                color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        // Descrizione e vento centrati sotto, non più
                        // indentati sotto i gradi (§ centratura richiesta
                        // esplicitamente in questo giro sostituisce
                        // l'indentazione di un giro precedente).
                        Text(weatherCategoryLabel(outlook.currentCategory), color = Muted, fontSize = 10.sp)
                        val dir = WindDirection.label(outlook.currentWindDirectionDeg)
                        if (outlook.currentWindKmh != null) {
                            Text(
                                "${outlook.currentWindKmh.roundToInt()} km/h" + (dir?.let { " $it" } ?: ""),
                                color = Muted,
                                fontSize = 9.sp,
                                // Avvicinato allo stato del tempo (§ richiesta
                                // esplicita: "avvicina scritta vento vicino
                                // allo stato del tempo") — il line-height di
                                // default lasciava un margine visibile fra
                                // le due righe.
                                modifier = Modifier.offset(y = (-3).dp),
                            )
                        }
                    }
                } else {
                    Text("Meteo non disponibile", color = Muted, fontSize = 10.sp)
                    Text("(attivalo in Impostazioni)", color = Muted, fontSize = 9.sp)
                }
                Spacer(Modifier.weight(1f))
                val dayLabels = listOf("Domani", "Dopodom.", "Tra 3gg")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val days = outlook?.upcoming.orEmpty()
                    for (i in 0 until 3) {
                        val day = days.getOrNull(i)
                        Column(
                            modifier = Modifier.weight(1f).clickable { onDayClick(i + 1) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(dayLabels[i], color = Muted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Icone dei 3 giorni ingrandite 28dp->38dp e
                            // schiarite alla fonte (§ richiesta esplicita:
                            // "quello prossimo non si vede è troppo
                            // piccolo") — misurato via Pillow che gli asset
                            // originali erano rosso scuro (RGB medio
                            // ~80-90 su canale rosso, ancora più bassi su
                            // verde/blu), quasi invisibili sulla card quasi
                            // nera: non solo una questione di dimensione.
                            WeatherIcon(day?.category, 38.dp, isDay = true)
                            val hi = day?.tempMaxC?.roundToInt()?.toString() ?: "—"
                            val lo = day?.tempMinC?.roundToInt()?.toString() ?: "—"
                            Text("$hi°/$lo°", color = Ink, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * BPM medi + sonno medio, ora due card separate (§ richiesta esplicita:
 * "allarga poco i battiti e il sonno, anche distanziandoli un po'" — lo
 * spazio fra i due pannelli era cotto in un'unica immagine, senza modo di
 * controllarlo da qui) invece del singolo template a due pannelli usato
 * finora. [bpmHeight]/[sonnoHeight] arrivano già calcolati dal chiamante
 * (che li usa anche per allineare l'altezza totale di questa colonna con
 * AresMeteoCard) così le due card restano sempre coerenti con quella somma.
 */
@Composable
private fun AresBpmSonnoCard(
    healthAvailable: Boolean,
    healthGranted: Boolean,
    healthSdkStatusLabel: String,
    healthAverages: HealthConnectManager.WeeklyHealthAverages?,
    onRequestHealth: () -> Unit,
    bpmHeight: Dp,
    sonnoHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ARES_BPM_SONNO_GAP)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(bpmHeight)) {
            val w = maxWidth
            Image(
                painter = painterResource(R.drawable.ares_bpm_card),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            val bpm = healthAverages?.avgHeartRateBpm
            AresHealthValue(
                value = bpm?.let { "$it bpm" },
                healthAvailable = healthAvailable,
                healthGranted = healthGranted,
                healthSdkStatusLabel = healthSdkStatusLabel,
                onRequestHealth = onRequestHealth,
                modifier = Modifier.fillMaxSize()
                    .padding(start = w * 0.46f, end = w * 0.18f),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(sonnoHeight)) {
            val w = maxWidth
            Image(
                painter = painterResource(R.drawable.ares_sonno_card),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            val sleep = healthAverages?.avgSleepPerNight
            AresHealthValue(
                value = sleep?.let { "${it.toHours()}h ${it.toMinutesPart()}m" },
                healthAvailable = healthAvailable,
                healthGranted = healthGranted,
                healthSdkStatusLabel = healthSdkStatusLabel,
                onRequestHealth = onRequestHealth,
                modifier = Modifier.fillMaxSize()
                    .padding(start = w * 0.46f, end = w * 0.18f),
            )
        }
    }
}

/**
 * Solo il dato dinamico di una riga BPM/Sonno del template — mai un
 * placeholder: Health Connect non disponibile, non concesso, e "nessun dato
 * scritto lì" restano tre stati onestamente distinti, come nella tile
 * precedente che questo sostituisce.
 */
@Composable
private fun AresHealthValue(
    value: String?,
    healthAvailable: Boolean,
    healthGranted: Boolean,
    healthSdkStatusLabel: String,
    onRequestHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        when {
            !healthAvailable -> Text("Health Connect non disponibile", color = Muted, fontSize = 9.sp)
            !healthGranted -> Column {
                // Plain clickable Text, non TextButton (§ bug reale
                // segnalato dall'utente da screenshot: la prima lettera di
                // "Concedi"/"accesso" appariva mangiata — TextButton avvolge
                // il contenuto in una Surface Material3 con la propria area
                // minima/clip, che qui non serve dato che il tocco copre
                // già l'intera riga). fontSize ridotto e nowrap forzato così
                // il testo resta su una riga sola, mai spezzato a metà
                // parola vicino al bordo dell'anello.
                // Testo su due righe consentito (§ revisione: forzare una
                // riga sola con softWrap=false avrebbe fatto sconfinare il
                // testo fuori dai margini reali misurati sui pixel — 15
                // caratteri a 10sp non ci stanno in ~58dp di larghezza
                // disponibile — quindi il wrap naturale resta la scelta più
                // sicura, il vero difetto era il margine troppo stretto dal
                // bordo/anello, già corretto qui sopra).
                Text(
                    "Concedi accesso",
                    color = Cyan,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onRequestHealth),
                )
                // Diagnostica temporanea (§ "cliccabile ma non succede
                // nulla" segnalato anche dopo il fix del contract): dice
                // se l'SDK di Health Connect si dichiara davvero
                // disponibile su questo dispositivo, cosa non
                // verificabile in questo ambiente senza un device reale.
                Text(
                    "SDK: $healthSdkStatusLabel",
                    color = Muted,
                    fontSize = 8.sp,
                )
            }
            value != null -> Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else -> Text("Nessun dato", color = Muted, fontSize = 10.sp)
        }
    }
}

/**
 * Promemoria di oggi: le prime 3 voci non completate (§ richiesta esplicita:
 * "andranno in ogni riga dei promemoria, i primi 3 della giornata che devono
 * ancora essere completati"), sul template fornito dall'utente. Due zone
 * cliccabili distinte, non un'unica card-click come prima (§ richiesta
 * esplicita: "se clicco su aggiungi promemoria mi apre solo piccola finestra
 * ... mentre se clicco sulla scritta promemoria mi apre la sezione intera"):
 * l'intestazione apre l'Agenda completa, "+ Aggiungi promemoria" apre solo
 * un piccolo dialog di aggiunta rapida.
 */
@Composable
private fun AresRemindersCard(
    entries: List<AgendaEntry>,
    modifier: Modifier = Modifier,
    onOpenSection: () -> Unit,
    onToggleDone: (AgendaEntry) -> Unit,
) {
    var showQuickAdd by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val w = maxWidth
        // "Ingrandisci poco" (§ richiesta esplicita), stesso mild-stretch
        // dell'8% già usato per Memoria qui sotto — le due card restano
        // della stessa proporzione relativa fra loro.
        val h = w / ARES_REMINDERS_ASPECT_RATIO * 1.08f
        Box(Modifier.fillMaxWidth().height(h)) {
            Image(
                painter = painterResource(R.drawable.ares_promemoria_card),
                contentDescription = "Promemoria",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Column(Modifier.fillMaxSize()) {
                // Intestazione "PROMEMORIA" già disegnata nel template — la
                // zona cliccabile combacia con quella riga, apre la sezione
                // intera (Agenda), mai il dialog rapido.
                Box(Modifier.fillMaxWidth().weight(0.18f).clickable(onClick = onOpenSection))
                // Confini di zona ricalcolati sui pixel reali dei 3 cerchi
                // checkbox del NUOVO template (asset rigenerato con alpha
                // reale, dimensioni cambiate da 630x700 a 659x700) — centri
                // misurati a frazione ~0.276/0.478/0.677 dell'altezza totale.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(0.60f),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            "Nessun promemoria per oggi",
                            color = Muted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = w * 0.20f, end = w * 0.08f),
                        )
                    } else {
                        // Sempre 3 righe fisse (§ bug reale segnalato
                        // dall'utente da screenshot: con meno di 3 impegni
                        // SpaceEvenly ridistribuiva 1-2 elementi su tutta
                        // l'altezza disponibile, disallineandoli dai 3
                        // cerchi checkbox a posizione fissa già disegnati
                        // nel template) — una riga vuota per uno slot senza
                        // impegno, mai un elemento in meno da distribuire.
                        (0 until 3).forEach { i ->
                            val entry = entries.getOrNull(i)
                            // Altezza fissa, non fillMaxHeight (§ bug reale
                            // trovato in revisione: dentro una Column con
                            // SpaceEvenly, un figlio non pesato che chiede
                            // fillMaxHeight() reclamerebbe l'intera altezza
                            // disponibile, spingendo le altre righe fuori
                            // schermo o sovrapponendole — mai arrivato a
                            // runtime, corretto prima del push).
                            Row(
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Zona di tocco sul cerchio (§ richiesta
                                // esplicita: "se clicco sul pallino deve
                                // segnarmeli come completati") — l'intera
                                // fascia sinistra, non solo il cerchietto
                                // disegnato, per un bersaglio comodo.
                                Box(
                                    modifier = Modifier
                                        .width(w * 0.20f)
                                        .fillMaxHeight()
                                        .then(
                                            if (entry != null) {
                                                Modifier.clickable { onToggleDone(entry) }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                                Text(
                                    entry?.text.orEmpty(),
                                    color = Ink,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // Distanziato dal cerchio (§ richiesta
                                    // esplicita: "i promemoria sono troppo
                                    // attaccati ai pallini a sinistra").
                                    modifier = Modifier.weight(1f).padding(start = 8.dp, end = w * 0.08f),
                                )
                            }
                        }
                    }
                }
                // "+ Aggiungi promemoria", già disegnato nel template — apre
                // solo il piccolo dialog di aggiunta rapida.
                Box(Modifier.fillMaxWidth().weight(0.22f).clickable { showQuickAdd = true })
            }
        }
    }
    if (showQuickAdd) {
        AresQuickAddReminderDialog(onDismiss = { showQuickAdd = false })
    }
}

/**
 * Dialog minimo per un promemoria veloce (§ "piccola finestra apribile per
 * inserire velocemente un promemoria") — non la schermata intera
 * `AddTaskScreen` già esistente, riusata invece dalla sezione Agenda
 * completa. Riusa [AgendaViewModel.addTask], la stessa funzione che
 * `AddTaskScreen` chiama, quindi un'unica logica di salvataggio.
 */
@Composable
private fun AresQuickAddReminderDialog(onDismiss: () -> Unit) {
    val vm: com.simone.jarvismobile.ui.agenda.AgendaViewModel = hiltViewModel()
    var text by remember { mutableStateOf("") }
    // "Essendo gli stessi della sezione attività... deve farmi inserire
    // anche data orario e note, come il classico promemoria" — stessi campi
    // di AddTaskScreen (data/ora/note), riusa gli stessi picker
    // (com.simone.jarvismobile.ui.agenda.TaskDatePicker/TaskTimePicker, resi
    // internal apposta per non duplicarli) e la stessa AgendaViewModel.addTask,
    // ma resta un piccolo dialog apribile, non una schermata intera.
    var date by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var time by remember { mutableStateOf<java.time.LocalTime?>(null) }
    var notes by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val today = java.time.LocalDate.now()

    AlertDialog(
        onDismissRequest = onDismiss,
        // Stile scuro/ciano coerente con la chat di JARVIS invece del dialog
        // Material chiaro di default (§ "in stile chat").
        containerColor = Color(0xFF0A121C),
        titleContentColor = Color.White,
        textContentColor = Ink,
        title = { Text("Nuovo promemoria") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Cosa devo ricordarti?", color = Muted) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Muted,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        cursorColor = Cyan,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = if (date != null) Cyan else Muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        date?.let { Agenda.fullDate(it, today) } ?: "Aggiungi data",
                        color = if (date != null) Ink else Muted,
                        fontSize = 13.sp,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = if (time != null) Cyan else Muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        time?.let { Agenda.humanTime(it) } ?: "Aggiungi ora",
                        color = if (time != null) Ink else Muted,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Note", color = Muted) },
                    minLines = 2,
                    maxLines = 3,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Muted,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        cursorColor = Cyan,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) {
                    vm.addTask(title = text, due = date, time = time, notes = notes)
                    onDismiss()
                }
            }) { Text("Salva", color = Cyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = Muted) } },
    )

    if (showDatePicker) {
        com.simone.jarvismobile.ui.agenda.TaskDatePicker(
            initial = date,
            onDismiss = { showDatePicker = false },
            onPick = { picked -> date = picked; showDatePicker = false },
        )
    }
    if (showTimePicker) {
        com.simone.jarvismobile.ui.agenda.TaskTimePicker(
            initial = time,
            onDismiss = { showTimePicker = false },
            onPick = { picked -> time = picked; showTimePicker = false },
        )
    }
}

/**
 * Memoria: spazio totale scritto da JARVIS (§ risposta esplicita dell'utente
 * alla domanda di chiarimento: "mostra memoria totale di jarvis interno, non
 * divisa per file"), non la ripartizione per tipo mostrata nell'immagine di
 * esempio. Tap apre Archivio, che espone davvero le cartelle Documenti/Note/
 * Liste/Da vedere/Memoria/TODO — la stessa richiesta dell'utente ("come prima
 * era archivio le varie cartelle"). Numero mostrato sopra l'etichetta
 * "memoria utilizzata" già disegnata nel nuovo template (§ richiesta
 * esplicita: "sopra memoria utilizzata verrà segnata la memoria locale che è
 * stata utilizzata").
 */
@Composable
private fun AresMemoryCard(
    usedBytes: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BoxWithConstraints(modifier.clickable(onClick = onClick)) {
        // "Ingrandisci poco" (§ richiesta esplicita) — 8% di altezza in più
        // rispetto alla proporzione reale, stesso mild-stretch già usato
        // per l'orb qui sopra.
        val h = maxWidth / ARES_MEMORY_ASPECT_RATIO * 1.08f
        Box(Modifier.fillMaxWidth().height(h)) {
            Image(
                painter = painterResource(R.drawable.ares_memoria_card2),
                contentDescription = "Memoria",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Text(
                formatAresBytes(usedBytes),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                // Bug reale segnalato dall'utente da screenshot ("la
                // scritta 0Mb copre la scritta sotto"): l'inset 0.20f era
                // calibrato sul vecchio asset — misurato ora con Pillow che
                // "memoria utilizzata" inizia a frazione ~0.72 dall'alto
                // (0.28 dal basso) nel nuovo template ricostruito.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.30f),
            )
        }
    }
}

/** Abbreviated MB/GB — same convention as Archivio's own storage bar. */
private fun formatAresBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

/**
 * Impostazioni / Navigazione / Traduttore — sostituisce la riga precedente
 * (Automazioni/JARVIS Drive/Traduttore) sul nuovo asset a tre icone fornito
 * dall'utente per il tema Atena, che porta esattamente queste tre etichette
 * disegnate ("il resto già lo sai" — coerente col primo mockup completo
 * inviato, che mostrava già questa stessa terna). "Navigazione" riusa lo
 * stesso ingresso di JARVIS Drive, l'unica funzione di navigazione già
 * cablata su questo schermo.
 */
@Composable
private fun AresShortcutsRow(
    onOpenSettings: () -> Unit,
    onOpenDrive: () -> Unit,
    onOpenTranslator: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val h = maxWidth / ARES_SHORTCUTS_ASPECT_RATIO
        Box(Modifier.fillMaxWidth().height(h)) {
            Image(
                painter = painterResource(R.drawable.ares_shortcuts_row2),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Row(Modifier.matchParentSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpenSettings))
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpenDrive))
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpenTranslator))
            }
        }
    }
}

/** "Oggi"/"Domani"/"Dopodomani"/"Tra 3 giorni" — matches [AresMeteoCard]'s own day index convention. */
private fun aresHourlyDayLabel(dayIndex: Int): String = when (dayIndex) {
    0 -> "Oggi"
    1 -> "Domani"
    2 -> "Dopodomani"
    else -> "Tra 3 giorni"
}

/**
 * Previsione oraria per un giorno (§ tema Atena, richiesta esplicita: "se
 * clicco... deve aprirmi previsione meteo per tutte le 24h di quel giorno").
 * [forecast] è null mentre [loading] è vero (fetch in corso) o se il fetch è
 * fallito — mai un dato inventato quando manca.
 */
@Composable
private fun AresHourlyForecastSheet(
    dayIndex: Int,
    forecast: com.simone.jarvismobile.weather.HourlyForecast?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF060A10))
                .border(1.dp, Cyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            aresHourlyDayLabel(dayIndex).uppercase(),
                            color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                        )
                        val date = forecast?.date
                        if (date != null) {
                            Text(
                                date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.ITALIAN)),
                                color = Muted, fontSize = 10.sp,
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.Close, contentDescription = "Chiudi",
                        tint = Muted,
                        modifier = Modifier.size(22.dp).clickable(onClick = onDismiss),
                    )
                }
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Cyan, modifier = Modifier.size(28.dp))
                    }
                    forecast == null -> Text(
                        "Previsione oraria non disponibile.",
                        color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    else -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        forecast.hours.forEach { reading ->
                            // Bug reale segnalato dall'utente da screenshot
                            // ("è tutto rosso e non si capisce niente"): la
                            // pillola glow (ares_pill_row.png) è un accento
                            // decorativo pensato per un elemento isolato, non
                            // per essere ripetuta come sfondo di 24 righe
                            // impilate — il risultato era un muro di rosso
                            // saturo che affogava il testo. Sostituita con
                            // uno sfondo scuro sobrio (coerente con il resto
                            // dell'app), niente più asset per riga.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF10161F))
                                    .border(1.dp, Color(0x33FF3B30), RoundedCornerShape(10.dp)),
                            ) {
                                Row(
                                    Modifier.matchParentSize().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "%02d:00".format(reading.hour),
                                        color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.width(48.dp),
                                    )
                                    WeatherIcon(reading.category, 22.dp, reading.isDay)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        reading.tempC?.let { "${it.roundToInt()}°" } ?: "—",
                                        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
