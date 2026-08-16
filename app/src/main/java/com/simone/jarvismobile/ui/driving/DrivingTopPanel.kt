package com.simone.jarvismobile.ui.driving

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.core.driving.DrivingVoiceState
import kotlinx.coroutines.delay
import java.time.LocalTime

/** Top bar (clock/JARVIS state/GPS+battery) + compact notification line + its expand sheet. */
@Composable
fun DrivingTopPanel(
    voiceState: DrivingVoiceState,
    hasLocationPermission: Boolean,
    notifications: List<DrivingNotification>,
    expandedPanel: DrivingExpandedPanel,
    onToggleMessages: () -> Unit,
    onRead: (DrivingNotification) -> Unit,
    onReply: (DrivingNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The window itself draws under the status bar (spec follow-up: "coprisse
    // tutta la barra delle notifiche") — the dark background reaches the true
    // top edge, the content insets itself below the real status bar icons so
    // nothing collides with them.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DrivingSportColors.Bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(10.dp),
    ) {
        TopBarRow(voiceState, hasLocationPermission)
        Spacer(Modifier.height(8.dp))
        if (notifications.isNotEmpty()) {
            NotifLine(notifications = notifications, onClick = onToggleMessages)
            AnimatedVisibility(
                visible = expandedPanel == DrivingExpandedPanel.MESSAGES,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Spacer(Modifier.height(8.dp))
                NotifSheet(notifications = notifications, onRead = onRead, onReply = onReply)
            }
        }
    }
}

@Composable
private fun TopBarRow(voiceState: DrivingVoiceState, hasLocationPermission: Boolean) {
    var clock by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalTime.now()
            delay(15_000)
        }
    }
    val battery = rememberBatteryPercent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrivingSportColors.Panel, RoundedCornerShape(24.dp))
            .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d:%02d".format(clock.hour, clock.minute),
            color = DrivingSportColors.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        JarvisStatePill(voiceState)
        Spacer(Modifier.weight(1f))
        val gps = if (hasLocationPermission) "GPS" else "GPS assente"
        val batteryText = battery?.let { "$it%" } ?: "--%"
        Text(
            text = "$gps • $batteryText",
            color = DrivingSportColors.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun JarvisStatePill(voiceState: DrivingVoiceState) {
    val (label, color) = when (voiceState) {
        DrivingVoiceState.READY -> "pronto" to DrivingSportColors.VoiceReady
        DrivingVoiceState.LISTENING -> "in ascolto" to DrivingSportColors.VoiceListening
        DrivingVoiceState.PROCESSING -> "elaborazione" to DrivingSportColors.VoiceProcessing
        DrivingVoiceState.REPLYING -> "risposta" to DrivingSportColors.VoiceReplying
    }
    Row(
        modifier = Modifier
            .background(DrivingSportColors.AccentSoft, RoundedCornerShape(999.dp))
            .border(1.dp, DrivingSportColors.LineStrong, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("JARVIS", color = DrivingSportColors.TextMain, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NotifLine(notifications: List<DrivingNotification>, onClick: () -> Unit) {
    val firstApp = notifications.first().app
    // notifications is already grouped one-card-per-sender (DrivingNotificationController),
    // so size() is the contact count directly; the badge is the real total message
    // count across senders, not the number of cards.
    val totalMessages = notifications.sumOf { it.count }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(DrivingSportColors.PanelStrong, RoundedCornerShape(20.dp))
            .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Notifiche · $firstApp · ${notifications.size} contatti",
                color = DrivingSportColors.TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "\"mostra dettagli messaggi\" per espandere",
                color = DrivingSportColors.Muted,
                fontSize = 11.sp,
            )
        }
        Box(
            modifier = Modifier
                .background(DrivingSportColors.AccentSoft, RoundedCornerShape(12.dp))
                .border(1.dp, DrivingSportColors.LineStrong, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(totalMessages.toString(), color = DrivingSportColors.TextMain, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

/**
 * Not `private`: also reused as-is by [MessageCard] in the new
 * `DrivingModeActivity` UI (spec §17 "non duplicare... message card").
 */
@Composable
fun NotifSheet(
    notifications: List<DrivingNotification>,
    onRead: (DrivingNotification) -> Unit,
    onReply: (DrivingNotification) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrivingSportColors.PanelStrong, RoundedCornerShape(22.dp))
            .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(22.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "MESSAGGI",
            color = DrivingSportColors.TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
        )
        notifications.take(4).forEach { n ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.025f), RoundedCornerShape(18.dp))
                    .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(18.dp))
                    .padding(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(n.app, color = DrivingSportColors.Muted, fontSize = 11.sp)
                    Text(relativeTime(n.postedAtEpochMs), color = DrivingSportColors.Muted, fontSize = 11.sp)
                }
                Text(
                    text = if (n.count > 1) "${n.sender} · ${n.count} messaggi" else n.sender,
                    color = DrivingSportColors.TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = n.preview,
                    color = Color(0xFFF4DFDC),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniActionButton("LEGGI", Modifier.weight(1f)) { onRead(n) }
                    if (n.supportsReply) MiniActionButton("RISPONDI", Modifier.weight(1f)) { onReply(n) }
                }
            }
        }
    }
}

@Composable
private fun MiniActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(DrivingSportColors.AccentSoft, RoundedCornerShape(12.dp))
            .border(1.dp, DrivingSportColors.LineStrong, RoundedCornerShape(12.dp))
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = DrivingSportColors.TextMain, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(readBattery(context)) }
    // A sticky-intent poll is simplest and cheap: no registered receiver to leak,
    // and the overlay does not need sub-minute battery precision.
    LaunchedEffect(Unit) {
        while (true) {
            percent = readBattery(context)
            delay(60_000)
        }
    }
    return percent
}

/** "ora" / "N min" / "N h" — a real elapsed time, never a fabricated one. */
private fun relativeTime(postedAtEpochMs: Long): String {
    val minutes = (System.currentTimeMillis() - postedAtEpochMs) / 60_000
    return when {
        minutes < 1 -> "ora"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h"
    }
}

private fun readBattery(context: Context): Int? {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return level * 100 / scale
}
