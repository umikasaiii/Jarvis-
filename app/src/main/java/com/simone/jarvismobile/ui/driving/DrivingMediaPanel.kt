package com.simone.jarvismobile.ui.driving

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingMediaState

/** Compact Spotify-style dock + its expand sheet (spec §10-12). Real media session only. */
@Composable
fun DrivingMediaPanel(
    media: DrivingMediaState?,
    art: Bitmap?,
    queue: List<String>,
    expandedPanel: DrivingExpandedPanel,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onToggleTransport: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(10.dp)) {
        AnimatedVisibility(
            visible = expandedPanel == DrivingExpandedPanel.MEDIA,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                MediaSheet(queue)
                Spacer(Modifier.height(8.dp))
            }
        }
        MediaDock(media, art, onToggle, onPrevious, onToggleTransport, onNext)
    }
}

/**
 * Reference "07_MUSIC_CARD" over the real `drive_hud_music` frame asset (an
 * album-art well on the left, a controls well on the right) instead of a
 * Compose-redrawn panel — Compose only lays the dynamic title/artist/art and
 * the transport buttons on top of it.
 */
@Composable
private fun MediaDock(
    media: DrivingMediaState?,
    art: Bitmap?,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onToggleTransport: () -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Image(
            painter = painterResource(JarvisDriveAssets.MusicCard),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
        Text(
            "SPOTIFY · " + (if (media == null) "nessuna riproduzione" else if (media.playing) "in riproduzione" else "in pausa"),
            color = DrivingSportColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoverArt(art)
            Column(Modifier.weight(1f)) {
                Text(
                    text = media?.title ?: "—",
                    color = DrivingSportColors.TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text(
                    text = media?.artist ?: "Nessuna sessione multimediale attiva",
                    color = DrivingSportColors.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaIconButton("⏮", onClick = onPrevious)
                MediaIconButton(if (media?.playing == true) "⏸" else "▶", main = true, onClick = onToggleTransport)
                MediaIconButton("⏭", onClick = onNext)
            }
        }
        val position = media?.positionMs
        val duration = media?.durationMs
        if (position != null && duration != null && duration > 0) {
            Spacer(Modifier.height(8.dp))
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(listOf(DrivingSportColors.Accent, DrivingSportColors.Accent2)),
                            RoundedCornerShape(999.dp),
                        ),
                )
            }
        }
        }
    }
}

@Composable
private fun CoverArt(art: Bitmap?) {
    if (art != null) {
        Image(
            bitmap = art.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(52.dp).background(Color(0xFF000000), RoundedCornerShape(15.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    Brush.radialGradient(listOf(DrivingSportColors.Accent2.copy(alpha = 0.7f), Color(0xFF170A0C))),
                    RoundedCornerShape(15.dp),
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(15.dp)),
        )
    }
}

@Composable
private fun MediaIconButton(symbol: String, main: Boolean = false, onClick: () -> Unit) {
    val size = if (main) 48.dp else 40.dp
    Box(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick)
            .background(if (main) DrivingSportColors.AccentSoft else DrivingSportColors.PanelStrong, RoundedCornerShape(if (main) 16.dp else 14.dp))
            .border(1.5.dp, JarvisDriveBrushes.Edge, RoundedCornerShape(if (main) 16.dp else 14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = DrivingSportColors.TextMain, fontSize = 17.sp)
    }
}

/**
 * PLAYLIST/CODA/RECENTI, all provisioned as the spec asks (§12) — but PLAYLIST
 * and RECENTI have no equivalent in the plain `MediaController` API most
 * sessions expose, so they honestly say so rather than showing invented
 * entries. Only CODA can ever have real rows, when the session publishes one.
 */
@Composable
private fun MediaSheet(queue: List<String>) {
    var tab by remember { mutableIntStateOf(1) }
    val labels = listOf("PLAYLIST", "CODA", "RECENTI")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrivingSportColors.PanelStrong, RoundedCornerShape(24.dp))
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, RoundedCornerShape(24.dp))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                MediaTab(label, selected = tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            1 -> if (queue.isEmpty()) {
                EmptySectionMessage("Nessuna coda disponibile da questa sessione multimediale.")
            } else {
                queue.take(6).forEach { title -> QueueRow(title) }
            }
            else -> EmptySectionMessage(
                "Questa sezione non è disponibile dalla sessione multimediale corrente.",
            )
        }
    }
}

@Composable
private fun MediaTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) DrivingSportColors.AccentSoft else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .border(1.dp, DrivingSportColors.LineStrong, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) DrivingSportColors.TextMain else DrivingSportColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun QueueRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(title, color = DrivingSportColors.TextMain, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun EmptySectionMessage(text: String) {
    Text(text, color = DrivingSportColors.Muted, fontSize = 12.sp)
}
