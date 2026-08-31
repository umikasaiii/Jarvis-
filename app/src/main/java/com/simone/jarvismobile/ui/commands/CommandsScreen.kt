package com.simone.jarvismobile.ui.commands

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sms
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
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette

// The brand accent (§ Impostazioni › Temi).
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
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
    "time_until" to Hint(Icons.Filled.HourglassTop, "Quanto manca", "«Quanto manca alle 16?»"),
    "add_reminder" to Hint(
        Icons.Filled.CalendarMonth,
        "Metti in agenda",
        "«Ricordami la revisione domani alle 15»",
    ),
    "list_agenda" to Hint(Icons.Filled.CalendarMonth, "Agenda", "«Cosa devo fare oggi pomeriggio?»"),
    "query_agenda" to Hint(Icons.Filled.CalendarMonth, "Quando è", "«Quando ho il dentista?»"),
    "complete_agenda" to Hint(
        Icons.Filled.CalendarMonth,
        "Completa attività",
        "«Segna comprare il latte come completato»",
    ),
    "search_knowledge" to Hint(
        Icons.Filled.MenuBook,
        "Cerca nelle guide",
        "«Come si cambia la gomma della moto?»",
    ),
    "remember" to Hint(Icons.Filled.EditNote, "Appunto", "«Prendi nota: la moto è una CB500»"),
    "list_memories" to Hint(Icons.Filled.EditNote, "Appunti salvati", "«Cosa hai annotato?»"),
    "calculate" to Hint(Icons.Filled.Calculate, "Calcolo", "«Quanto fa 12 * 8?»"),
    "open_app" to Hint(Icons.Filled.Bolt, "Apri app", "«Apri Spotify»"),
    "open_settings" to Hint(Icons.Filled.Settings, "Impostazioni", "«Apri le impostazioni Bluetooth»"),
    "create_calendar_event" to Hint(
        Icons.Filled.CalendarMonth,
        "Esporta evento",
        "«Esporta su Google Calendar dentista domani alle 15»",
    ),
    "prepare_call" to Hint(Icons.Filled.Phone, "Prepara chiamata", "«Chiama il 061234567»"),
    "compose_sms" to Hint(Icons.Filled.Sms, "Prepara SMS", "«Prepara un SMS al 333…: arrivo»"),
    "navigate" to Hint(Icons.Filled.Navigation, "Navigazione", "«Portami a Piazza Navona»"),
    "play_media" to Hint(Icons.Filled.MusicNote, "Riproduci", "«Riproduci Kind of Blue»"),
    "media_control" to Hint(Icons.Filled.SkipNext, "Controlli media", "«Brano successivo»"),
    "list_notifications" to Hint(Icons.Filled.Notifications, "Notifiche", "«Leggi le notifiche di WhatsApp»"),
    "search_vault" to Hint(Icons.Filled.FolderOpen, "Cerca nel vault", "«Cerca nel vault fattura moto»"),
)

/**
 * The real capabilities registered in the tool registry, each with the phrase
 * that triggers it. These run offline and deterministically — no model needed
 * — so they work even before a model is loaded. Lives inside a collapsible
 * "Comandi" section in Impostazioni (§ richiesta esplicita dell'utente:
 * rimossa la barra di navigazione in basso, "comandi puoi mettere una sezione
 * apribile nelle impostazioni") rather than its own screen — no outer
 * fillMaxSize/scroll of its own any more, since it now sits inside
 * Impostazioni's already-scrolling column and a second unbounded scroll
 * nested inside the first would crash on measurement.
 */
@Composable
fun CommandsScreen(viewModel: CommandsViewModel = hiltViewModel()) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))))
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
            "Le azioni delicate chiedono conferma. Eventi, numeri e SMS si aprono come bozze: " +
                "devi ancora premere Salva, Chiama o Invia nell'app di sistema.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
