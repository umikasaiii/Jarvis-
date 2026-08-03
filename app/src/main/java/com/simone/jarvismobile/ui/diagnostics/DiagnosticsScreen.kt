package com.simone.jarvismobile.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Minimal diagnostics screen (docs/ARCHITECTURE.md §8 / task §10): shows the
 * *real* audio route and lets the user run the mic/voice tests. Nothing here
 * claims a device is active unless the underlying state says so.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val route by viewModel.routeState.collectAsStateWithLifecycle()
    val tts by viewModel.ttsState.collectAsStateWithLifecycle()
    val voice by viewModel.selectedVoiceName.collectAsStateWithLifecycle()
    val level by viewModel.micLevel.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val perms = viewModel.permissions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostica audio", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line("Permesso microfono", yesNo(perms.microphone))
                Line("Permesso notifiche", yesNo(perms.notifications))
                Line("Permesso Bluetooth", yesNo(perms.bluetooth))
                HorizontalDivider()
                Line("Bluetooth connesso", yesNo(route.bluetoothConnected))
                Line("AirPods rilevati", yesNo(route.airPodsDetected))
                Line("Input richiesto", route.input?.kind?.name ?: "—")
                Line("Output richiesto", route.output?.kind?.name ?: "—")
                Line("Comm. device applicato", yesNo(route.communicationDeviceApplied))
                Line("Input via Bluetooth", yesNo(route.usingBluetoothInput))
                Line("Audio focus", yesNo(route.hasAudioFocus))
                Line("Sample rate", if (route.sampleRate > 0) "${route.sampleRate} Hz" else "—")
                HorizontalDivider()
                Line("Stato TTS", tts.name)
                Line("Voce offline", voice ?: "—")
                Line("Livello microfono", "%.2f".format(level))
                Line("Ultimo errore", error ?: "—")
            }
        }

        Button(onClick = viewModel::onTestMicrophone, modifier = Modifier.fillMaxWidth()) {
            Text("Test microfono")
        }
        Button(onClick = viewModel::onTestVoice, modifier = Modifier.fillMaxWidth()) {
            Text("Test voce")
        }
        OutlinedButton(onClick = viewModel::onResetAudio, modifier = Modifier.fillMaxWidth()) {
            Text("Reset audio")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Indietro")
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

private fun yesNo(b: Boolean): String = if (b) "Sì" else "No"
