package com.simone.jarvismobile.ui.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.backup.drive.GoogleDriveBackupProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Backup e sincronizzazione" (spec). Local-first evening backup: turn it on,
 * pick the time, the run conditions and the retention, and see the last result.
 * The cloud is an optional, clearly-secondary copy. "Esegui backup ora",
 * "Ripristina backup" and "Gestisci backup" all live here.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()

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
                HorizontalDivider()
                Text(
                    "Solo in questi luoghi (opzionale)",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (savedPlaces.isEmpty()) {
                    Text(
                        "Nessun luogo salvato: il backup non è limitato a un posto. " +
                            "Salva un luogo in Automazioni › Regole avanzate › Luoghi per " +
                            "farlo partire solo lì (es. «casa» o «casa Francy»).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedPlaces.forEach { place ->
                            FilterChip(
                                selected = place.id in ui.placeIds,
                                onClick = { viewModel.togglePlace(place.id) },
                                label = { Text(place.displayName) },
                            )
                        }
                    }
                    Text(
                        if (ui.placeIds.isEmpty()) {
                            "Nessuno selezionato: il backup non è limitato a un posto."
                        } else {
                            "Il backup parte solo quando sei in uno dei luoghi selezionati."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                    "Il cloud è solo una copia cifrata aggiuntiva, mai la fonte principale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.cloudEnabled && ui.provider == GoogleDriveBackupProvider.ID) {
                    HorizontalDivider()
                    GoogleDriveSection(ui = ui, viewModel = viewModel)
                }
            }
        }

        // --- Chiave di recupero ---------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chiave di recupero", style = MaterialTheme.typography.titleMedium)
                Text(
                    "I backup sono cifrati con una chiave che vive solo su questo telefono. " +
                        "Per leggerli su un telefono nuovo (o dopo un ripristino da zero) serve " +
                        "questa chiave: mostrala e conservala in un posto sicuro (gestore di " +
                        "password, carta). Non la salviamo da nessuna parte — se la perdi, i " +
                        "backup passati restano cifrati per sempre.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RecoveryKeySection(viewModel)
            }
        }

        // --- Backup completo del telefono (Android/Google One) --------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup completo del telefono", style = MaterialTheme.typography.titleMedium)
                Text(
                    "JARVIS fa il backup solo dei propri dati. Per il backup generale del " +
                        "telefono (foto, app, impostazioni di sistema) usa Google One / Backup " +
                        "Android, una funzione di sistema separata — JARVIS non la controlla né " +
                        "la sostituisce.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apri Impostazioni Android (Google › Backup)") }
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

/**
 * "Collega Google Drive": paste in the OAuth client id/secret from the user's
 * own Google Cloud project (docs/GOOGLE_DRIVE_SETUP.md), then connect/disconnect
 * the account. No Google credential of any kind is ever bundled with JARVIS.
 */
@Composable
private fun GoogleDriveSection(ui: BackupUi, viewModel: BackupViewModel) {
    val context = LocalContext.current
    var clientId by remember(ui.driveClientId) { mutableStateOf(ui.driveClientId) }
    var clientSecret by remember(ui.driveClientSecret) { mutableStateOf(ui.driveClientSecret) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Drive", style = MaterialTheme.typography.titleSmall)
        if (ui.driveConnected) {
            Text(
                "Account collegato" + (ui.driveAccountEmail?.let { ": $it" } ?: "."),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = viewModel::disconnectDrive, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnetti Google Drive")
            }
        } else {
            Text(
                "Serve un client OAuth Android creato nel tuo progetto Google Cloud " +
                    "(guida in docs/GOOGLE_DRIVE_SETUP.md). Nessuna credenziale Google è " +
                    "inclusa in JARVIS: questi due campi restano solo su questo telefono, cifrati.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OAuth Client ID") },
                singleLine = true,
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Client secret (se richiesto)") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { viewModel.saveDriveClientConfig(clientId, clientSecret) },
                enabled = clientId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salva credenziali") }
            OutlinedButton(
                onClick = {
                    val intent = viewModel.driveConnectIntent()
                    if (intent != null) runCatching { context.startActivity(intent) }
                },
                enabled = ui.driveClientId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Collega Google Drive") }
        }
    }
}

/**
 * Reveal-on-demand recovery key (never shown by default) plus an import field
 * for restoring on a new device. The key only ever leaves this screen through
 * the user's own copy action — nothing here writes it anywhere else.
 */
@Composable
private fun RecoveryKeySection(viewModel: BackupViewModel) {
    val clipboard = LocalClipboardManager.current
    val revealed by viewModel.recoveryKey.collectAsStateWithLifecycle()
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val key = revealed
        if (key != null) {
            SelectionContainer {
                Text(key, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text("Copia") }
                TextButton(onClick = viewModel::hideRecoveryKey) { Text("Nascondi") }
            }
        } else {
            OutlinedButton(
                onClick = viewModel::revealRecoveryKey,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mostra la chiave di recupero") }
        }
        HorizontalDivider()
        TextButton(onClick = { showImport = !showImport }) {
            Text(if (showImport) "Nascondi importazione" else "Ho già una chiave di recupero (nuovo telefono)")
        }
        if (showImport) {
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Incolla la chiave di recupero") },
            )
            OutlinedButton(
                onClick = { viewModel.importRecoveryKey(importText); importText = "" },
                enabled = importText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Importa") }
        }
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
