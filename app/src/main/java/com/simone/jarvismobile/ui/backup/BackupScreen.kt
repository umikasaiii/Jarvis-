package com.simone.jarvismobile.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Backup e sincronizzazione" (spec). Local-first evening backup: turn it on,
 * pick the time, the run conditions and the retention, and see the last result.
 * The cloud is an optional, clearly-secondary copy. "Esegui backup ora",
 * "Ripristina backup" and "Gestisci backup" all live here.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showManage by remember { mutableStateOf(false) }

    // System folder picker (Storage Access Framework). The returned tree URI is
    // where backups will be saved and, later, restored from.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.setDestination(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Backup e sincronizzazione", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Backup serale locale, cifrato sul dispositivo (AES-256). Copia solo i dati " +
                "di JARVIS — vault, memoria, database, impostazioni, agenda e automazioni — " +
                "mai i modelli AI o gli altri file riscaricabili. Funziona anche offline.",
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                }
            }
        }

        // --- Auto backup ---------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow("Backup automatico", ui.enabled, viewModel::setEnabled)
                var hourSlider by remember(ui.hour) { mutableStateOf(ui.hour.toFloat()) }
                var minuteSlider by remember(ui.minute) { mutableStateOf(ui.minute.toFloat()) }
                Text("Orario: %02d:%02d".format(hourSlider.toInt(), minuteSlider.toInt()))
                Slider(
                    value = hourSlider,
                    onValueChange = { hourSlider = it },
                    onValueChangeFinished = { viewModel.setTime(hourSlider.toInt(), minuteSlider.toInt()) },
                    valueRange = 0f..23f,
                    steps = 22,
                    enabled = ui.enabled,
                )
                Slider(
                    value = minuteSlider,
                    onValueChange = { minuteSlider = it },
                    onValueChangeFinished = { viewModel.setTime(hourSlider.toInt(), minuteSlider.toInt()) },
                    valueRange = 0f..59f,
                    enabled = ui.enabled,
                )
            }
        }

        // --- Run conditions ------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Condizioni di esecuzione", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Solo su Wi-Fi", ui.wifiOnly, viewModel::setWifiOnly)
                SwitchRow("Solo in carica", ui.chargingOnly, viewModel::setChargingOnly)
                var batterySlider by remember(ui.minBattery) { mutableStateOf(ui.minBattery.toFloat()) }
                Text("Batteria minima: %d%%".format(batterySlider.toInt()))
                Slider(
                    value = batterySlider,
                    onValueChange = { batterySlider = it },
                    onValueChangeFinished = { viewModel.setMinBattery(batterySlider.toInt()) },
                    valueRange = 0f..80f,
                )
                Text(
                    "Il Wi-Fi conta soprattutto per la copia sul cloud; il backup locale " +
                        "resta sempre disponibile senza rete.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Retention -----------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conservazione", style = MaterialTheme.typography.titleMedium)
                var d by remember(ui.retentionDaily) { mutableStateOf(ui.retentionDaily.toFloat()) }
                var w by remember(ui.retentionWeekly) { mutableStateOf(ui.retentionWeekly.toFloat()) }
                var m by remember(ui.retentionMonthly) { mutableStateOf(ui.retentionMonthly.toFloat()) }
                Text("Giornalieri: %d".format(d.toInt()))
                Slider(d, onValueChange = { d = it }, valueRange = 0f..30f,
                    onValueChangeFinished = { viewModel.setRetention(d.toInt(), w.toInt(), m.toInt()) })
                Text("Settimanali: %d".format(w.toInt()))
                Slider(w, onValueChange = { w = it }, valueRange = 0f..12f,
                    onValueChangeFinished = { viewModel.setRetention(d.toInt(), w.toInt(), m.toInt()) })
                Text("Mensili: %d".format(m.toInt()))
                Slider(m, onValueChange = { m = it }, valueRange = 0f..12f,
                    onValueChangeFinished = { viewModel.setRetention(d.toInt(), w.toInt(), m.toInt()) })
                Text(
                    "I backup più vecchi vengono eliminati automaticamente tenendo gli " +
                        "ultimi giornalieri, settimanali e mensili.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Destinazione (cartella locale) --------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Destinazione backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.destinationName != null) {
                        "Cartella scelta: ${ui.destinationName}"
                    } else {
                        "Attualmente: solo memoria interna dell'app. " +
                            "I backup vengono persi se disinstalli JARVIS."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Scegli una cartella del telefono (o SD): i backup vengono salvati lì, " +
                        "sopravvivono alla disinstallazione e il ripristino li rilegge dalla " +
                        "stessa cartella se sono presenti. Restano sempre cifrati.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (ui.destinationName != null) "Cambia cartella" else "Scegli cartella")
                }
                if (ui.destinationName != null) {
                    TextButton(
                        onClick = viewModel::clearDestination,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Usa solo memoria interna")
                    }
                }
            }
        }

        // --- Cloud ---------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Sincronizza sul cloud", ui.cloudEnabled, viewModel::setCloudEnabled)
                var menuOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = ui.cloudEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val label = viewModel.providers.firstOrNull { it.id == ui.provider }?.label ?: "Scegli provider"
                    Text(label)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    viewModel.providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { menuOpen = false; viewModel.setProvider(p.id) },
                        )
                    }
                }
                Text(
                    "Il cloud è solo una copia cifrata aggiuntiva, mai la fonte principale. " +
                        "Google Drive richiede il collegamento dell'account (in arrivo); finché " +
                        "non è collegato, i backup restano in coda in attesa.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Status + actions ---------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stato", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.lastBackupAt > 0L) {
                        "Ultimo backup: ${formatTime(state.lastBackupAt)} · ${formatSize(state.lastSizeBytes)}"
                    } else {
                        "Nessun backup ancora eseguito."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Backup conservati: ${state.count}", style = MaterialTheme.typography.bodySmall)
                state.lastError?.let {
                    Text("Ultimo errore: $it", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = viewModel::runNow,
                    enabled = !busy && !state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy || state.running) "Backup in corso…" else "Esegui backup ora")
                }
                OutlinedButton(
                    onClick = { showManage = !showManage; if (showManage) viewModel.refreshBackups() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showManage) "Nascondi backup" else "Gestisci backup / Ripristina")
                }
            }
        }

        if (showManage) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup disponibili", style = MaterialTheme.typography.titleMedium)
                    if (backups.isEmpty()) {
                        Text("Nessun backup presente.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Prima di un ripristino viene sempre creato un backup di sicurezza dello " +
                            "stato attuale.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    backups.forEach { b ->
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(formatTime(b.createdAt), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${formatSize(b.sizeBytes)} · ${b.entryCount} file · ${b.status.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { viewModel.restore(b.id) }, enabled = !busy) { Text("Ripristina") }
                                TextButton(onClick = { viewModel.verify(b.id) }) { Text("Verifica") }
                                TextButton(onClick = { viewModel.delete(b.id) }) { Text("Elimina") }
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Indietro")
        }
        Text(
            "JARVIS Mobile · backup local-first · il cloud è solo una copia",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
