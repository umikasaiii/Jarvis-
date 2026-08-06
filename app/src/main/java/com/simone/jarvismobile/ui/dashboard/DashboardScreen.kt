package com.simone.jarvismobile.ui.dashboard

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.R
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.core.agenda.ReminderAlertType
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.llm.LlmLoadState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

// --- Palette ---------------------------------------------------------------
private val Cyan = Color(0xFF3FD8F0)
private val Blue = Color(0xFF3B9EFF)
private val Green = Color(0xFF2ECC71)
private val Amber = Color(0xFFF3B23C)
private val Violet = Color(0xFF9B7BFF)
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
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val loadState by viewModel.llmLoadState.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val memory by viewModel.memoryStatus.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val battery = rememberBatteryStatus()
    val context = LocalContext.current
    var editingAlerts by remember { mutableStateOf<AgendaEntry?>(null) }

    // Buzz once when a new message lands (e.g. a voice reply) while on the dashboard.
    var prevUnread by remember { mutableStateOf(unread) }
    LaunchedEffect(unread) {
        if (unread > prevUnread) runCatching { vibrateOnce(context) }
        prevUnread = unread
    }

    val accent = accentFor(state)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF02060B))) {
        Image(
            painter = painterResource(R.drawable.bg_dashboard),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = name.uppercase(),
                color = Color(0xFF8FEBFF),
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 10.sp,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Cyan,
                        offset = Offset.Zero,
                        blurRadius = 28f,
                    ),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
            )

            // --- Hero: battery · orb · weather ----------------------------
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniCard(Modifier.weight(1f)) {
                    Text("BATTERIA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text("${battery.percent}%", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (battery.charging) "In carica" else "In uso",
                            color = if (battery.charging) Green else Muted,
                            fontSize = 11.sp,
                        )
                        if (battery.charging) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Bolt, null, tint = Green, modifier = Modifier.size(14.dp))
                        }
                    }
                    ProgressBar(fraction = battery.percent / 100f, color = if (battery.charging) Green else Cyan)
                }

                ListenOrb(
                    accent = accent,
                    title = orbTitle(state),
                    subtitle = orbSubtitle(state),
                    active = !state.isRestingLike(),
                    mode = orbModeFor(state),
                    onClick = {
                        when {
                            state == ConversationState.Speaking -> viewModel.onInterruptAndTalk()
                            state.isRestingLike() -> viewModel.onTalkPressed()
                            else -> viewModel.onCancel()
                        }
                    },
                    modifier = Modifier.weight(1.35f),
                )

                MiniCard(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("18°", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Roma", color = Muted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    DemoBadge()
                }
            }

            // --- Row: Panoramica + Agenda (2 columns) ---------------------
            // Both are backed by the real agenda file and the real note index.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f)) {
                    CardHeader(Icons.Filled.Dashboard, "PANORAMICA", reserveEnd = false)
                    Text(todayLabel(), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    StatLine(today.count { it.time != null }.toString(), "Eventi oggi", Cyan)
                    StatLine(today.count { it.time == null }.toString(), "Attività oggi", Blue)
                    StatLine(upcoming.size.toString(), "In programma", Violet)
                    Spacer(Modifier.height(8.dp))
                    val doneToday = today.count { it.done }
                    val pct = if (today.isEmpty()) 0 else doneToday * 100 / today.size
                    DonutRing(percent = pct, label = "Completato", modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally))
                }
                GlassCard(Modifier.weight(1f)) {
                    CardHeader(Icons.Filled.CalendarMonth, "AGENDA", reserveEnd = false)
                    if (upcoming.isEmpty()) {
                        Text(
                            "Nessun impegno.\nDì «ricordami di … domani alle 15».",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    } else {
                        val shown = upcoming.take(3)
                        shown.forEachIndexed { i, e ->
                            AgendaRow(
                                time = e.time?.let { Agenda.humanTime(it) } ?: "—",
                                title = e.text,
                                place = Agenda.humanDate(e.date, java.time.LocalDate.now()),
                                alerts = e.alerts,
                                onAlerts = { editingAlerts = e },
                                dot = when (i) { 0 -> Cyan; 1 -> Green; else -> Violet },
                                last = i == shown.lastIndex,
                            )
                        }
                    }
                }
            }

            // --- Row: Casa + Sistema (2 columns) --------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f), badge = { DemoBadge("F7") }) {
                    CardHeader(Icons.Filled.Home, "CASA")
                    ToggleRow(Icons.Filled.Lightbulb, "Luci", "Soggiorno", on = true)
                    ToggleRow(Icons.Filled.AcUnit, "Clima", "22°C", on = false)
                    ToggleRow(Icons.Filled.Security, "Sicurezza", "Inserito", on = true)
                }
                GlassCard(Modifier.weight(1f)) {
                    CardHeader(Icons.Filled.Memory, "SISTEMA", reserveEnd = false)
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
                    SystemRow(Icons.Filled.Sync, "Sync", "Off", Muted, demo = true)
                    SystemRow(Icons.Filled.CloudUpload, "Backup", "Off", Muted, demo = true)
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

            // --- Automazioni -----------------------------------------------
            GlassCard(badge = { DemoBadge() }) {
                CardHeader(Icons.Filled.Bolt, "AUTOMAZIONI")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutoChip("Modalità Lavoro", "Giorni feriali", Modifier.weight(1f))
                    AutoChip("Routine Mattutina", "07:30", Modifier.weight(1f))
                    AutoChip("Modalità Relax", "Dopo le 22:00", Modifier.weight(1f))
                }
            }

            // --- Calendario personale: next seven days from Agenda.md ------
            GlassCard {
                CardHeader(Icons.Filled.CalendarMonth, "CALENDARIO PERSONALE", reserveEnd = false)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val weekStart = LocalDate.now()
                    for (i in 0 until 7) {
                        val date = weekStart.plusDays(i.toLong())
                        DayCell(
                            date = date,
                            isToday = i == 0,
                            entries = upcoming.filter { it.date == date },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CalendarLegend(Cyan, "Appuntamenti")
                    CalendarLegend(Violet, "Attività")
                }
            }

            Spacer(Modifier.height(72.dp)) // room so the FAB never covers the last card
        }

        // Floating, collapsible written-chat button (bottom-right).
        ChatFab(
            unread = unread,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp),
            onClick = { viewModel.markChatSeen(); onOpenChat() },
        )
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
    Box(modifier.size(78.dp), contentAlignment = Alignment.Center) {
        // Soft outward glow only — no rim/border, the image's own luminous ring
        // is the edge of the button.
        Canvas(Modifier.size(78.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.Transparent, Cyan.copy(alpha = 0.30f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = center,
            )
        }
        // The user-provided glowing chat button (cropped exactly to its ring).
        Image(
            painter = painterResource(R.drawable.chat_fab),
            contentDescription = "Chat",
            modifier = Modifier.size(66.dp).clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
        )
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFE74C3C)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 9) "9+" else "$unread",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
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
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.bg_card),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
        if (badge != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 20.dp)) { badge() }
        }
    }
}

@Composable
private fun MiniCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.bg_card),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            content = content,
        )
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, reserveEnd: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = if (reserveEnd) 30.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
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

@Composable
private fun StatLine(value: String, label: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x22FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))),
        )
    }
}

@Composable
private fun DonutRing(percent: Int, label: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.13f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color(0x2AFFFFFF),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Cyan, Blue, Violet, Cyan)),
                startAngle = -90f, sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f), useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun AgendaRow(
    time: String,
    title: String,
    place: String,
    alerts: List<ReminderAlert>,
    onAlerts: () -> Unit,
    dot: Color,
    last: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 3.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(5.dp)).background(dot))
            if (!last) Box(Modifier.width(2.dp).height(28.dp).background(Color(0x22FFFFFF)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(time, color = dot, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = Ink, fontSize = 13.sp)
            Text(place, color = Muted, fontSize = 10.sp)
            Text(
                if (alerts.isEmpty()) "Avviso non impostato" else "${alerts.size} avvis${if (alerts.size == 1) "o" else "i"}",
                color = if (alerts.isEmpty()) Amber else Green,
                fontSize = 9.sp,
            )
        }
        IconButton(onClick = onAlerts, modifier = Modifier.size(32.dp)) {
            Icon(
                if (alerts.isEmpty()) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                contentDescription = "Imposta avvisi",
                tint = if (alerts.isEmpty()) Amber else Green,
                modifier = Modifier.size(18.dp),
            )
        }
    }
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Blue,
                checkedBorderColor = Blue,
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
private fun AutoChip(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33081521))
            .border(1.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Filled.Bolt, null, tint = Cyan, modifier = Modifier.size(16.dp))
        Text(title, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    entries: List<AgendaEntry>,
    modifier: Modifier = Modifier,
) {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALIAN).uppercase().take(3)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isToday) Cyan.copy(alpha = 0.16f) else Color(0x33081521))
            .border(1.dp, if (isToday) Cyan.copy(alpha = 0.55f) else Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(dow, color = Muted, fontSize = 9.sp)
        Text("${date.dayOfMonth}", color = if (isToday) Cyan else Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (entries.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entries.any { it.time != null }) {
                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(Cyan))
                }
                if (entries.any { it.time == null }) {
                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(Violet))
                }
                if (entries.size > 1) {
                    Text(entries.size.toString(), color = Muted, fontSize = 8.sp)
                }
            }
        } else {
            Text(if (isToday) "Oggi" else "·", color = Muted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun CalendarLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 9.sp)
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

/** Which animation the orb should play. */
private enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

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

private fun accentFor(state: ConversationState): Color = when {
    state == ConversationState.Listening || state == ConversationState.FollowUpWindow -> Green
    state == ConversationState.Speaking -> Blue
    state is ConversationState.RecoverableError || state is ConversationState.FatalError -> Color(0xFFE74C3C)
    else -> Cyan
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

private fun todayLabel(): String {
    val d = LocalDate.now()
    val month = d.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN).replaceFirstChar { it.uppercase() }
    return "Oggi, ${d.dayOfMonth} $month"
}
