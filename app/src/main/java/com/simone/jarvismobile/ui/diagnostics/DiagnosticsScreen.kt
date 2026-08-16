package com.simone.jarvismobile.ui.diagnostics

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.driving.DrivingNavigationMode

/**
 * Minimal diagnostics screen (docs/ARCHITECTURE.md §8 / task §10): shows the
 * *real* audio route and lets the user run the mic/voice tests with clear
 * feedback. The mic test requests the RECORD_AUDIO permission if it's missing.
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
    val micStatus by viewModel.micStatus.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val sttStatus by viewModel.sttStatus.collectAsStateWithLifecycle()
    val sttPartial by viewModel.sttPartial.collectAsStateWithLifecycle()
    val drivingNavigationMode by viewModel.drivingNavigationMode.collectAsStateWithLifecycle()
    val perms = viewModel.permissions()

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.runMicTest()
    }

    fun requestMicPermissions() {
        val toRequest = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        micPermLauncher.launch(toRequest)
    }

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
                Line("STT on-device", yesNo(viewModel.sttAvailable()))
                Line("Ultimo errore", error ?: "—")
            }
        }

        // Speech-to-text test (Phase 2).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Riconoscimento vocale", style = MaterialTheme.typography.titleMedium)
                if (sttPartial.isNotEmpty()) {
                    Text("“$sttPartial”", style = MaterialTheme.typography.bodyMedium)
                }
                if (sttStatus.isNotEmpty()) {
                    Text(sttStatus, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { if (viewModel.hasMicPermission()) viewModel.runSttTest() else requestMicPermissions() },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (testing) "In ascolto…" else "Test riconoscimento vocale")
                }
            }
        }

        // Live microphone level.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Livello microfono: ${(level * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { level.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (micStatus.isNotEmpty()) {
                    Text(micStatus, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = { if (viewModel.hasMicPermission()) viewModel.runMicTest() else requestMicPermissions() },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "Test in corso…" else "Test microfono (registra 3 s)")
        }

        Button(
            onClick = viewModel::runVoiceTest,
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test voce")
        }
        if (voiceStatus.isNotEmpty()) {
            Text(voiceStatus, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(onClick = viewModel::onResetAudio, modifier = Modifier.fillMaxWidth()) {
            Text("Reset audio")
        }

        // Driving Mode V2 (sviluppo): la modalità overlay su Google Maps resta il
        // default finché la nuova DrivingModeActivity interna non è completa.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Modalità Guida (sviluppo)", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (drivingNavigationMode == DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION) {
                            "JARVIS Drive interno (beta)"
                        } else {
                            "Overlay su Google Maps (predefinito)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = drivingNavigationMode == DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION,
                        onCheckedChange = {
                            viewModel.setDrivingNavigationMode(
                                if (it) DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION
                                else DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY,
                            )
                        },
                    )
                }
            }
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
