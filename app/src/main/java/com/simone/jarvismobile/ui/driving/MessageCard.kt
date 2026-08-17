package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.DrivingNotification

/**
 * Bottom-left message card (spec §10/§11). Not shown at all with no
 * notifications — never an empty card. [expanded] reuses [NotifSheet] as-is
 * (the same list JARVIS's overlay already renders, LEGGI/RISPONDI included)
 * instead of a second implementation; collapsed shows only a one-line peek.
 */
@Composable
fun MessageCard(
    notifications: List<DrivingNotification>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRead: (DrivingNotification) -> Unit,
    onReply: (DrivingNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notifications.isEmpty()) return
    if (expanded) {
        Column(modifier.width(260.dp)) {
            NotifSheet(notifications = notifications, onRead = onRead, onReply = onReply)
        }
    } else {
        MessagePeek(notifications = notifications, onClick = onToggle, modifier = modifier)
    }
}

/**
 * Reference "06_MESSAGES_CARD": title + up to two messages + an unread pill,
 * drawn over the real reference frame asset (`drive_hud_messages`) rather
 * than a Compose-redrawn panel — the frame's row dividers and avatar circles
 * come from the artwork itself, Compose only lays dynamic text on top.
 */
@Composable
private fun MessagePeek(notifications: List<DrivingNotification>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val total = notifications.sumOf { it.count }
    val shown = notifications.take(2)
    Box(modifier.width(230.dp).clickable(onClick = onClick)) {
        Image(
            painter = painterResource(JarvisDriveAssets.MessagesCard),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(DrivingSportColors.Accent))
                Spacer(Modifier.width(6.dp))
                Text("MESSAGGI", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
            }
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { n ->
                    Column {
                        Text(n.sender, color = DrivingSportColors.TextMain, style = JarvisDriveTypography.Body)
                        Text(
                            n.preview,
                            color = DrivingSportColors.Muted,
                            style = JarvisDriveTypography.CardLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text("$total messaggi ›", color = DrivingSportColors.Accent, style = JarvisDriveTypography.CardLabel)
        }
    }
}
