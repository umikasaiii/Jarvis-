package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.core.driving.DrivingNavigationState

/**
 * Compact turn/distance/street panel (spec §5). Only shown once a route is
 * prepared or started; anything it cannot honestly know (turn-by-turn detail —
 * Google Maps does not hand that back to the launching app) is simply left
 * blank rather than guessed.
 */
@Composable
fun DrivingNavPanel(navigation: DrivingNavigationState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 320.dp)
            .background(DrivingSportColors.Panel, RoundedCornerShape(20.dp))
            .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(20.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(DrivingSportColors.AccentSoft, RoundedCornerShape(14.dp))
                .border(1.dp, DrivingSportColors.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("↱", color = DrivingSportColors.Accent2, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = navigation.distanceToNextTurn ?: "In corso",
                color = DrivingSportColors.TextMain,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = navigation.nextInstruction ?: (navigation.destinationLabel ?: "Navigazione su Google Maps"),
                color = DrivingSportColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        navigation.etaMinutes?.let { eta ->
            Column(horizontalAlignment = Alignment.End) {
                Text("$eta min", color = DrivingSportColors.TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("ARRIVO", color = DrivingSportColors.Muted, fontSize = 10.sp)
            }
        }
    }
}
