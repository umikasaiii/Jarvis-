package com.simone.jarvismobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The "piccolo segnale" the spec asks for (§ "Ricorda di aggiungere piccolo
 * segnale che indica se modalità pro è attiva o no"): a small, unobtrusive
 * pill, not a redesign of the screen it sits on. Shown only while Pro Mode is
 * active — the absence of the badge already says "NORMAL", so there is
 * nothing to render for the off state.
 */
@Composable
fun ProModeBadge(modifier: Modifier = Modifier) {
    Text(
        "PRO",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
