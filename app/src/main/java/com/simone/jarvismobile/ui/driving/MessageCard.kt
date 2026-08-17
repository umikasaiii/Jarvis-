package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
private fun MessagePeek(notifications: List<DrivingNotification>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val latest = notifications.first()
    val total = notifications.sumOf { it.count }
    Row(
        modifier
            .clickable(onClick = onClick)
            .clip(JarvisDriveShapes.Card)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, JarvisDriveShapes.Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(DrivingSportColors.Accent))
                Spacer(Modifier.width(6.dp))
                Text("MESSAGGI", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
            }
            Text(
                if (total > 1) "${latest.sender} · $total" else latest.sender,
                color = DrivingSportColors.TextMain,
                style = JarvisDriveTypography.Body,
            )
        }
    }
}
