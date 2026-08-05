package com.simone.jarvismobile.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.llm.LlmLoadState
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// --- Palette ---------------------------------------------------------------
private val Cyan = Color(0xFF35D0EA)
private val Blue = Color(0xFF3B9EFF)
private val Green = Color(0xFF2ECC71)
private val Amber = Color(0xFFF3B23C)
private val Violet = Color(0xFF9B7BFF)
private val Ink = Color(0xFFDCE7EE)
private val Muted = Color(0xFF7C8B95)
private val CardBg = Color(0xB30E1A24)
private val CardBorder = Color(0x2635D0EA)

/**
 * The full JARVIS dashboard (Home tab). Tiles backed by real data — the listen
 * orb, battery, local AI/model, and the indexed Obsidian note count — are live;
 * tiles for features that don't exist yet (weather, agenda, home automation,
 * automations, sync/backup, productivity) are shown with a clear DEMO badge so
 * nothing fake is presented as real.
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
    val battery = rememberBatteryStatus()

    val accent = accentFor(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF061019), Color(0xFF0A1622), Color(0xFF050C12)))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Wordmark.
            Text(
                text = name.uppercase(),
                color = Cyan,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            // --- Hero: battery · orb · weather ----------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniCard(Modifier.weight(1f)) {
                    Text("BATTERIA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text("${battery.percent}%", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                }

                ListenOrb(
                    accent = accent,
                    title = orbTitle(state),
                    subtitle = orbSubtitle(state),
                    active = state.isActive(),
                    onClick = onOpenChat,
                    modifier = Modifier.weight(1.3f),
                )

                MiniCard(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("— °", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Meteo", color = Muted, fontSize = 10.sp)
                        }
                    }
                    DemoBadge()
                }
            }

            // --- Panoramica + Agenda --------------------------------------
            GlassCard {
                CardHeader(Icons.Filled.Dashboard, "PANORAMICA", accent = Cyan, trailing = { DemoBadge() })
                Text(todayLabel(), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatLine("—", "Eventi")
                        StatLine("—", "Attività")
                        StatLine("—", "Promemoria")
                    }
                    DonutRing(percent = 0, label = "Produttività", accent = Blue, modifier = Modifier.size(96.dp))
                }
            }

            GlassCard {
                CardHeader(Icons.Filled.CalendarMonth, "AGENDA", accent = Cyan, trailing = { DemoBadge() })
                Text(
                    "Nessun calendario collegato. L'agenda comparirà qui quando " +
                        "collegheremo il calendario del telefono o gli appunti.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }

            // --- Casa + Sistema -------------------------------------------
            GlassCard {
                CardHeader(Icons.Filled.Home, "CASA", accent = Cyan, trailing = { DemoBadge("FASE 7") })
                ToggleRow(Icons.Filled.Lightbulb, "Luci", "Soggiorno")
                ToggleRow(Icons.Filled.AcUnit, "Clima", "22°C")
                ToggleRow(Icons.Filled.Security, "Sicurezza", "Inserito")
            }

            GlassCard {
                CardHeader(Icons.Filled.Memory, "SISTEMA", accent = Cyan)
                val aiOnline = loadState == LlmLoadState.LOADED
                SystemRow(
                    Icons.Filled.SmartToy, "AI Locale",
                    if (aiOnline) "Online" else if (loadState == LlmLoadState.LOADING) "Carico…" else "Offline",
                    if (aiOnline) Green else Muted,
                )
                SystemRow(
                    Icons.Filled.Memory, "Memoria",
                    if (memory.configured) "${memory.noteCount} note" else "—",
                    if (memory.configured) Cyan else Muted,
                )
                SystemRow(Icons.Filled.Sync, "Sincronizzazione", "Non attiva", Muted, demo = true)
                SystemRow(Icons.Filled.CloudUpload, "Backup", "Non attivo", Muted, demo = true)
            }

            // --- Obsidian -------------------------------------------------
            GlassCard {
                CardHeader(Icons.Filled.Book, "OBSIDIAN / NOTE", accent = Cyan, trailing = {
                    if (!memory.configured) DemoBadge("NO VAULT")
                })
                NoteRow(
                    Icons.Filled.FolderOpen, "Knowledge Base",
                    if (memory.configured) "${memory.noteCount} note" else "Collega un vault",
                    onClick = onOpenMemory,
                )
                NoteRow(Icons.Filled.Description, "Frammenti indicizzati",
                    if (memory.configured) "${memory.chunkCount}" else "—", onClick = onOpenMemory)
                NoteRow(Icons.Filled.Star, "Preferiti", "—", onClick = onOpenMemory, demo = true)
            }

            // --- Automazioni ----------------------------------------------
            GlassCard {
                CardHeader(Icons.Filled.Bolt, "AUTOMAZIONI", accent = Cyan, trailing = { DemoBadge() })
                Text(
                    "Modalità Lavoro · Routine Mattutina · Modalità Relax — in arrivo.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }

            // --- Calendario settimana -------------------------------------
            GlassCard {
                CardHeader(Icons.Filled.CalendarMonth, "CALENDARIO", accent = Cyan, trailing = { DemoBadge("EVENTI") })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val today = LocalDate.now()
                    for (i in 0 until 7) {
                        DayCell(today.plusDays(i.toLong()), isToday = i == 0, modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Building blocks -------------------------------------------------------

@Composable
private fun GlassCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun MiniCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun CardHeader(
    icon: ImageVector,
    title: String,
    accent: Color,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        trailing?.invoke()
    }
}

@Composable
private fun DemoBadge(text: String = "DEMO") {
    Text(
        text = text,
        color = Amber,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Amber.copy(alpha = 0.14f))
            .border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatLine(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DonutRing(percent: Int, label: String, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.12f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color(0x33FFFFFF),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f, sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f), useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x400B141B))
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
            checked = false,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
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
private fun DayCell(date: LocalDate, isToday: Boolean, modifier: Modifier = Modifier) {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALIAN).uppercase().take(3)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isToday) Cyan.copy(alpha = 0.16f) else Color(0x330B141B))
            .border(1.dp, if (isToday) Cyan.copy(alpha = 0.5f) else Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(dow, color = Muted, fontSize = 9.sp)
        Text("${date.dayOfMonth}", color = if (isToday) Cyan else Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(if (isToday) "Oggi" else "·", color = Muted, fontSize = 8.sp)
    }
}

@Composable
private fun ListenOrb(
    accent: Color,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.aspectRatio(1f).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), center = c, radius = r),
                radius = r, center = c,
            )
            drawCircle(accent.copy(alpha = 0.85f), radius = r * 0.82f, center = c, style = Stroke(width = r * 0.03f))
            drawCircle(accent.copy(alpha = 0.30f), radius = r * 0.66f, center = c, style = Stroke(width = r * 0.015f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(subtitle, color = Muted, fontSize = 9.sp)
        }
    }
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

// --- State helpers ---------------------------------------------------------

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle, ConversationState.Cancelled,
    is ConversationState.RecoverableError, is ConversationState.FatalError,
    ConversationState.PermissionRequired, ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable, ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable -> true
    else -> false
}

private fun ConversationState.isActive(): Boolean = !isRestingLike()

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
