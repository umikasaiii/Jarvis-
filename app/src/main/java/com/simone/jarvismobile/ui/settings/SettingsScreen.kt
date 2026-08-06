package com.simone.jarvismobile.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.data.SettingsRepository

/**
 * Settings screen. Phase-1 exposes the preferences that actually take effect:
 * the assistant name (shown on the dashboard) and the recording-window length.
 * Items belonging to later phases are shown as clearly-disabled placeholders,
 * never as if they were active.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val seconds by viewModel.recordSeconds.collectAsStateWithLifecycle()
    val useBluetooth by viewModel.useBluetooth.collectAsStateWithLifecycle()
    val followUpEnabled by viewModel.followUpEnabled.collectAsStateWithLifecycle()
    val responseNotifications by viewModel.responseNotifications.collectAsStateWithLifecycle()
    val showResponsePreview by viewModel.showResponsePreview.collectAsStateWithLifecycle()
    val reminderNotifications by viewModel.reminderNotifications.collectAsStateWithLifecycle()
    val reminderMorningHour by viewModel.reminderMorningHour.collectAsStateWithLifecycle()
    val configuredVoice by viewModel.ttsVoiceName.collectAsStateWithLifecycle()
    val resolvedVoice by viewModel.selectedVoiceName.collectAsStateWithLifecycle()
    val voices by viewModel.availableVoices.collectAsStateWithLifecycle()
    val ttsRate by viewModel.ttsSpeechRate.collectAsStateWithLifecycle()
    val ttsPitch by viewModel.ttsPitch.collectAsStateWithLifecycle()
    val speakBackground by viewModel.speakBackgroundResponses.collectAsStateWithLifecycle()

    var nameField by remember(name) { mutableStateOf(name) }
    var sliderValue by remember(seconds) { mutableStateOf(seconds.toFloat()) }
    var morningSlider by remember(reminderMorningHour) { mutableStateOf(reminderMorningHour.toFloat()) }
    var rateSlider by remember(ttsRate) { mutableStateOf(ttsRate) }
    var pitchSlider by remember(ttsPitch) { mutableStateOf(ttsPitch) }
    var voiceMenuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val roleManager = remember(context) { context.getSystemService(RoleManager::class.java) }
    var assistantActive by remember {
        mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true)
    }
    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        assistantActive = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Assistente", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { viewModel.setAssistantName(nameField) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Salva nome")
                }
                OutlinedButton(
                    onClick = {
                        val roleIntent = roleManager
                            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }
                            ?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                            ?: Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        assistantRoleLauncher.launch(roleIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (assistantActive) "JARVIS è l'assistente Android" else "Imposta JARVIS come assistente Android")
                }
                Text(
                    "Usa il gesto o tasto dell'assistente configurato da MagicOS. " +
                        "JARVIS apre una schermata visibile e ascolta solo allora; non usa un microfono nascosto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Voce offline", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { voiceMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val selectedLabel = voices.firstOrNull { it.name == configuredVoice }?.label
                            ?: if (configuredVoice.isBlank()) "Automatica (offline)" else configuredVoice
                        Text(selectedLabel, maxLines = 2)
                    }
                    DropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Automatica (offline)") },
                            onClick = {
                                voiceMenuOpen = false
                                viewModel.setTtsVoice("")
                            },
                        )
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice.label) },
                                onClick = {
                                    voiceMenuOpen = false
                                    viewModel.setTtsVoice(voice.name)
                                },
                            )
                        }
                    }
                }
                Text("Velocità: %.2f×".format(rateSlider))
                Slider(
                    value = rateSlider,
                    onValueChange = { rateSlider = it },
                    onValueChangeFinished = { viewModel.setTtsSpeechRate(rateSlider) },
                    valueRange = SettingsRepository.MIN_TTS_RATE..SettingsRepository.MAX_TTS_RATE,
                )
                Text("Tono: %.2f×".format(pitchSlider))
                Slider(
                    value = pitchSlider,
                    onValueChange = { pitchSlider = it },
                    onValueChangeFinished = { viewModel.setTtsPitch(pitchSlider) },
                    valueRange = SettingsRepository.MIN_TTS_PITCH..SettingsRepository.MAX_TTS_PITCH,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Parla per risposte in background", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = speakBackground, onCheckedChange = viewModel::setSpeakBackgroundResponses)
                }
                Text(
                    "Disattivato per impostazione predefinita, per non leggere contenuti privati ad alta voce. " +
                        "Voce attiva: ${resolvedVoice ?: "nessuna voce offline pronta"}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::refreshVoices, modifier = Modifier.fillMaxWidth()) {
                    Text("Aggiorna voci installate")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Usa AirPods / Bluetooth", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = useBluetooth, onCheckedChange = viewModel::setUseBluetooth)
                }
                Text(
                    "Se attivo, instrada l'audio sugli AirPods quando disponibili. " +
                        "Su alcuni telefoni (MagicOS) serve la Posizione attiva per il " +
                        "Bluetooth: se la Posizione è spenta, JARVIS usa comunque " +
                        "microfono e altoparlante del telefono senza chiederla. " +
                        "Disattiva per restare sempre su telefono.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Promemoria agenda", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = reminderNotifications,
                        onCheckedChange = viewModel::setReminderNotifications,
                    )
                }
                Text("Orario «mattina stessa»: %02d:00".format(morningSlider.toInt()))
                Slider(
                    value = morningSlider,
                    onValueChange = { morningSlider = it },
                    onValueChangeFinished = { viewModel.setReminderMorningHour(morningSlider.toInt()) },
                    valueRange = 5f..11f,
                    steps = 5,
                    enabled = reminderNotifications,
                )
                Text(
                    "Gli avvisi scelti nell'Agenda vengono conservati e ripristinati anche dopo il riavvio.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Notifica risposta pronta", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = responseNotifications,
                        onCheckedChange = viewModel::setResponseNotifications,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Mostra anteprima", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showResponsePreview,
                        enabled = responseNotifications,
                        onCheckedChange = viewModel::setShowResponsePreview,
                    )
                }
                Text(
                    "Le richieste scritte continuano anche cambiando app o spegnendo lo schermo. " +
                        "L'anteprima è disattivata per proteggere i contenuti nella schermata di blocco.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Conversazione a mani libere", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = followUpEnabled, onCheckedChange = viewModel::setFollowUpEnabled)
                }
                Text(
                    "Se attivo, dopo la risposta il microfono si riapre da solo per qualche " +
                        "secondo: puoi rispondere o incalzare senza ripremere. Se resti in " +
                        "silenzio, si chiude da solo. Nessun microfono sempre acceso: la " +
                        "finestra è breve e legata alla conversazione appena avvenuta.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Registrazione", style = MaterialTheme.typography.titleMedium)
                Text("Durata finestra: ${sliderValue.toInt()} s", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { viewModel.setRecordSeconds(sliderValue.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Text(
                    "Usata per il test microfono. Nel parlato normale la fine-frase è " +
                        "automatica (il riconoscitore chiude da solo al silenzio).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("In arrivo (fasi successive)", style = MaterialTheme.typography.titleMedium)
                PlaceholderRow("Home Assistant", "Fase 7")
                PlaceholderRow("Companion PC", "Fase 8")
            }
        }

        OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
            Text("Modelli (AI locale)")
        }
        OutlinedButton(onClick = onOpenMemory, modifier = Modifier.fillMaxWidth()) {
            Text("Memoria (appunti Obsidian)")
        }
        OutlinedButton(onClick = viewModel::newConversation, modifier = Modifier.fillMaxWidth()) {
            Text("Nuova conversazione (svuota memoria)")
        }
        OutlinedButton(onClick = viewModel::resetAudio, modifier = Modifier.fillMaxWidth()) {
            Text("Reset audio")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Indietro")
        }

        Text(
            "JARVIS Mobile · offline-first · nessun account, nessun cloud",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlaceholderRow(label: String, phase: String) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(phase, style = MaterialTheme.typography.bodySmall)
    }
}
