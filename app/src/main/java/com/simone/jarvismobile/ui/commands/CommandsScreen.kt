package com.simone.jarvismobile.ui.commands

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Cyan = Color(0xFF3FD8F0)
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)

/** One example phrase per tool, so the user knows what to actually say. */
private data class Hint(val icon: ImageVector, val title: String, val example: String)

private val HINTS = mapOf(
    "get_time" to Hint(Icons.Filled.Schedule, "Ora e data", "«Che ore sono?»"),
    "battery_status" to Hint(Icons.Filled.BatteryFull, "Batteria", "«Quanta batteria ho?»"),
    "set_timer" to Hint(Icons.Filled.HourglassTop, "Timer", "«Timer 10 minuti»"),
    "set_alarm" to Hint(Icons.Filled.Alarm, "Sveglia", "«Sveglia alle 7:30»"),
    "flashlight" to Hint(Icons.Filled.FlashlightOn, "Torcia", "«Accendi la torcia»"),
    "remember" to Hint(Icons.Filled.EditNote, "Ricorda", "«Ricorda che domani ho la revisione»"),
    "list_memories" to Hint(Icons.Filled.EditNote, "Cosa devo fare", "«Che impegni ho?»"),
    "calculate" to Hint(Icons.Filled.Calculate, "Calcolo", "«Quanto fa 12 * 8?»"),
)

/**
 * Commands tab (Phase 6): the real capabilities registered in the tool registry,
 * each with the phrase that triggers it. These run offline and deterministically
 * — no model needed — so they work even before a model is loaded.
 */
@Composable
fun CommandsScreen(viewModel: CommandsViewModel = hiltViewModel()) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Comandi", style = MaterialTheme.typography.headlineSmall, color = Cyan)
        Text(
            "Puoi dirli a voce toccando l'orb, oppure scriverli in chat. " +
                "Funzionano offline e senza modello caricato.",
            color = Muted,
            fontSize = 13.sp,
        )

        tools.forEach { (name, description) ->
            val hint = HINTS[name]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x660A1826))
                    .border(1.dp, Cyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    hint?.icon ?: Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(hint?.title ?: name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(description, color = Muted, fontSize = 12.sp)
                    hint?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(it.example, color = Cyan.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
        }

        Text(
            "Le azioni delicate chiedono conferma prima di essere eseguite.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
