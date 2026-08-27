package com.simone.jarvismobile.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.ui.dashboard.rememberBatteryStatus

private val Muted = Color(0xFF9DB0BC)
private val Good = Color(0xFF2ECC71)
private val Warn = Color(0xFFF3B23C)

/**
 * General, at-a-glance status: everything the dashboard's "Sistema" tile used
 * to have nowhere honest to lead to (it opened the Models screen — useful for
 * managing models, not for "how is JARVIS doing overall"). Every number here
 * is read from a repository some other screen already owns; nothing is
 * computed just for this view, so nothing here can drift from what's real.
 */
@Composable
fun SystemStatusScreen(
    onBack: () -> Unit,
    viewModel: SystemStatusViewModel = hiltViewModel(),
) {
    val loadState by viewModel.llmLoadState.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val memory by viewModel.memoryStatus.collectAsStateWithLifecycle()
    val agendaEntries by viewModel.agendaEntries.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val cloudEnabled by viewModel.backupCloudEnabled.collectAsStateWithLifecycle()
    val cloudProvider by viewModel.backupCloudProvider.collectAsStateWithLifecycle()
    val proactiveEnabled by viewModel.proactiveEnabled.collectAsStateWithLifecycle()
    val automationServiceEnabled by viewModel.automationServiceEnabled.collectAsStateWithLifecycle()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val battery = rememberBatteryStatus()

    val open = agendaEntries.count { !it.done }
    val done = agendaEntries.count { it.done }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Stato sistema", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Panoramica generale — ogni valore qui viene dallo stesso posto della " +
                "schermata che lo gestisce, mai calcolato solo per questa vista.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        StatusCard("Assistente") {
            StatusRow(
                "Modello locale",
                when (loadState) {
                    LlmLoadState.LOADED -> loadedModel ?: "Caricato"
                    LlmLoadState.LOADING -> "In caricamento…"
                    else -> "Non caricato"
                },
                if (loadState == LlmLoadState.LOADED) Good else Muted,
            )
            StatusRow(
                "Parola d'attivazione",
                if (wakeWordEnabled) "Attiva" else "Disattivata",
                if (wakeWordEnabled) Good else Muted,
            )
            StatusRow(
                "Suggerimenti proattivi",
                if (proactiveEnabled) "Attivi" else "Disattivati",
                if (proactiveEnabled) Good else Muted,
            )
        }

        StatusCard("Memoria") {
            StatusRow("Ricordi salvati", memory.noteCount.toString(), Good)
            StatusRow("Frammenti indicizzati", memory.chunkCount.toString(), Good)
            StatusRow(
                "Vault Obsidian",
                if (memory.configured) "Collegato" else "Non collegato",
                if (memory.configured) Good else Muted,
            )
        }

        StatusCard("Agenda") {
            StatusRow("Impegni totali", agendaEntries.size.toString(), Good)
            StatusRow("Da fare", open.toString(), if (open > 0) Warn else Good)
            StatusRow("Completati", done.toString(), Good)
        }

        StatusCard("Automazioni in background") {
            StatusRow(
                "Servizio eventi",
                if (automationServiceEnabled) "Attivo" else "Disattivato",
                if (automationServiceEnabled) Good else Muted,
            )
        }

        StatusCard("Backup e sincronizzazione") {
            StatusRow(
                "Ultimo backup",
                relativeLabel(backupState.lastBackupAt),
                if (backupState.lastBackupAt > 0L) Good else Muted,
            )
            StatusRow("Backup salvati", backupState.count.toString(), Good)
            StatusRow(
                "Sincronizzazione cloud",
                if (cloudEnabled) cloudProvider.ifBlank { "Attiva" } else "Off",
                if (cloudEnabled) Good else Muted,
            )
        }

        StatusCard("Dispositivo") {
            StatusRow("Batteria", "${battery.percent}%", if (battery.charging) Good else Muted)
            StatusRow("In carica", if (battery.charging) "Sì" else "No", Muted)
            StatusRow("Versione app", BuildConfig.VERSION_NAME, Muted)
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
    }
}

@Composable
private fun StatusCard(title: String, content: @Composable Column.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/** "Mai" / "Oggi" / "Ieri" / "N giorni fa" — same rule the dashboard tile uses. */
private fun relativeLabel(epochMs: Long): String {
    if (epochMs <= 0L) return "Mai"
    val days = (System.currentTimeMillis() - epochMs) / 86_400_000L
    return when {
        days <= 0L -> "Oggi"
        days == 1L -> "Ieri"
        days < 7L -> "$days giorni fa"
        else -> "Oltre una settimana fa"
    }
}
