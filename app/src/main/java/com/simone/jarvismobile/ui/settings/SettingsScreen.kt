package com.simone.jarvismobile.ui.settings

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val seconds by viewModel.recordSeconds.collectAsStateWithLifecycle()
    val useBluetooth by viewModel.useBluetooth.collectAsStateWithLifecycle()
    val followUpEnabled by viewModel.followUpEnabled.collectAsStateWithLifecycle()

    var nameField by remember(name) { mutableStateOf(name) }
    var sliderValue by remember(seconds) { mutableStateOf(seconds.toFloat()) }

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
                PlaceholderRow("Memoria Obsidian", "Fase 5")
                PlaceholderRow("Strumenti / azioni", "Fase 6")
                PlaceholderRow("Home Assistant", "Fase 7")
                PlaceholderRow("Companion PC", "Fase 8")
            }
        }

        OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
            Text("Modelli (AI locale)")
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
