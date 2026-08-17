package com.simone.jarvismobile.ui.driving

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.DrivingVoiceState

/**
 * The bottom voice dock (spec §13) — bound directly to the app's one real
 * voice pipeline via [DrivingVoiceState] ([SessionCoordinator][com.simone.jarvismobile.audio.SessionCoordinator]'s
 * `ConversationState`, mapped by the same `toDrivingVoiceState()` the
 * existing overlay uses). No separate STT/TTS state of its own.
 *
 * [compact] shrinks the dock to a small mic-only pill during an incoming
 * call (spec §15) — the label and Navigate/Places actions disappear rather
 * than animate, keeping the safe zone above genuinely free.
 */
@Composable
fun VoiceDock(
    voiceState: DrivingVoiceState,
    compact: Boolean,
    onMicClick: () -> Unit,
    onNavigateClick: () -> Unit = {},
    onPlacesClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = voiceDockAccent(voiceState)
    val transition = rememberInfiniteTransition(label = "voice-dock-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "voice-dock-pulse-alpha",
    )
    val glowAlpha = if (voiceState == DrivingVoiceState.READY) 0.55f else pulse

    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) 64.dp else JarvisDriveDimensions.VoiceDockHeight)
            .clip(JarvisDriveShapes.Dock)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.Edge, JarvisDriveShapes.Dock),
    ) {
        if (compact) {
            MicButton(accent = accent, glowAlpha = glowAlpha, size = 44.dp, onClick = onMicClick, modifier = Modifier.align(Alignment.Center))
        } else {
            Column(
                Modifier.fillMaxWidth().padding(PaddingValues(vertical = 10.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DockNotch()
                Text(
                    voiceStateLabel(voiceState),
                    color = accent,
                    style = JarvisDriveTypography.CardTitle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockSideAction(icon = Icons.Filled.Navigation, label = "Navigate", onClick = onNavigateClick)
                    MicButton(accent = accent, glowAlpha = glowAlpha, size = JarvisDriveDimensions.VoiceDockMicSize, onClick = onMicClick)
                    DockSideAction(icon = Icons.Filled.Place, label = "Places", onClick = onPlacesClick)
                }
            }
        }
    }
}

@Composable
private fun MicButton(accent: Color, glowAlpha: Float, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size + 20.dp).clip(CircleShape)
                .background(accent.copy(alpha = 0.18f * glowAlpha)),
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(size).clip(CircleShape)
                .background(DrivingSportColors.Bg)
                .border(2.dp, accent.copy(alpha = glowAlpha), CircleShape),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Microfono", tint = accent)
        }
    }
}

/**
 * The circular "layers" button next to the voice dock (reference kit's
 * 10_LAYERS_BUTTON). Wired to a real action — opening Mappe offline, where
 * the offline/online map and TomTom key live — rather than a decorative
 * no-op. The reference's 09_APP_DRAWER_BUTTON is deliberately not added yet:
 * it has no obvious real action in this app without inventing one.
 */
@Composable
fun LayersButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(JarvisDriveShapes.Dock)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.Edge, JarvisDriveShapes.Dock),
    ) {
        Icon(Icons.Filled.Layers, contentDescription = "Mappe", tint = DrivingSportColors.TextMain)
    }
}

/** The three small dots above the "Pronto"/"In ascolto" label (reference kit's dock notch). */
@Composable
private fun DockNotch() {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(DrivingSportColors.Muted.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun DockSideAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = DrivingSportColors.Muted)
        }
        Text(label, color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
    }
}

private fun voiceDockAccent(state: DrivingVoiceState): Color = when (state) {
    DrivingVoiceState.READY -> DrivingSportColors.VoiceReady
    DrivingVoiceState.LISTENING -> DrivingSportColors.Accent
    DrivingVoiceState.PROCESSING -> DrivingSportColors.VoiceProcessing
    DrivingVoiceState.REPLYING -> DrivingSportColors.Accent2
}

private fun voiceStateLabel(state: DrivingVoiceState): String = when (state) {
    DrivingVoiceState.READY -> "Pronto"
    DrivingVoiceState.LISTENING -> "In ascolto"
    DrivingVoiceState.PROCESSING -> "Elaborazione"
    DrivingVoiceState.REPLYING -> "Risposta"
}
