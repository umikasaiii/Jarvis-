package com.simone.jarvismobile.ui.diagnostics

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.driving.DrivingNavigationMode
import com.simone.jarvismobile.core.tts.SupertonicQuality
import com.simone.jarvismobile.driving.DrivingModeActivity
import com.simone.jarvismobile.navigation.debug.DebugGpsSimulator

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
    val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()
    val healthStatus by viewModel.healthStatus.collectAsStateWithLifecycle()
    val perms = viewModel.permissions()
    val context = LocalContext.current

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

        // Supertonic FAST/BALANCED/QUALITY A/B comparison (spec §"TEST"). Debug-only
        // like the GPS simulator below: not something a shipped build should expose.
        if (BuildConfig.DEBUG) {
            val supertonicBusy by viewModel.supertonicBusy.collectAsStateWithLifecycle()
            val supertonicStatus by viewModel.supertonicStatus.collectAsStateWithLifecycle()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Supertonic (debug)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sintetizza “Ciao. Sono JARVIS. Il nuovo sistema vocale locale è attivo.” " +
                            "con ciascun profilo, per confrontarli a orecchio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SupertonicQuality.entries.forEach { profile ->
                            OutlinedButton(
                                onClick = { viewModel.runSupertonicProfile(profile) },
                                enabled = !supertonicBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text(profile.name) }
                        }
                    }
                    if (supertonicStatus.isNotEmpty()) {
                        Text(supertonicStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Motore JARVIS › Conversazionale: per-turn telemetry (spec §12),
            // debug-only like the panels above — never the reply text itself,
            // only counts/booleans/tool names (see EngineTurnDiagnostics).
            val engineTurns by viewModel.engineDiagnostics.collectAsStateWithLifecycle()
            if (engineTurns.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Motore conversazionale (debug)", style = MaterialTheme.typography.titleMedium)
                        engineTurns.takeLast(5).reversed().forEach { turn ->
                            Text(
                                "fastPath=${turn.fastPathHit} · fallback=${turn.fallbackOccurred} · " +
                                    "parseError=${turn.parseError} · memorie=${turn.memoriesRetrieved} · " +
                                    "tool=${turn.toolsExecuted.size}/${turn.toolsRequested.size} · " +
                                    "primo=${turn.timeToFirstEmitMs}ms · totale=${turn.totalTurnMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
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
                if (drivingNavigationMode == DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION) {
                    Button(
                        onClick = { context.startActivity(DrivingModeActivity.intent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Apri JARVIS Drive (beta)")
                    }
                }
                // Debug-only, in-memory: never compiled into a meaningful state in
                // release (DebugGpsSimulator.setEnabled is a no-op there) — lets a
                // developer exercise the map/camera without moving (spec §13).
                if (BuildConfig.DEBUG) {
                    val simulatorEnabled by DebugGpsSimulator.enabled.collectAsStateWithLifecycle()
                    val gpxRoute by DebugGpsSimulator.gpxRoute.collectAsStateWithLifecycle()
                    val gpxStatus by viewModel.gpxStatus.collectAsStateWithLifecycle()
                    val gpxPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(viewModel::loadGpxDebugRoute) }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GPS simulato (solo debug)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = simulatorEnabled, onCheckedChange = DebugGpsSimulator::setEnabled)
                    }
                    Text(
                        if (gpxRoute != null) "Percorso: registrazione GPX caricata" else "Percorso: giro sintetico (nessun GPX)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { gpxPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Carica GPX") }
                        if (gpxRoute != null) {
                            OutlinedButton(
                                onClick = viewModel::clearGpxDebugRoute,
                                modifier = Modifier.weight(1f),
                            ) { Text("Rimuovi GPX") }
                        }
                    }
                    if (gpxStatus.isNotEmpty()) {
                        Text(gpxStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // § segnalazioni ripetute dell'utente di avvisi pioggia sbagliati —
        // dopo tre giri di fix "alla cieca" (fetch/decisione/refresh/staleness)
        // senza un modo per verificarli davvero, questo mostra esattamente
        // cosa sa JARVIS in questo momento, invece di dover indovinare di
        // nuovo se il prossimo avviso sbagliato è un bug o una regola senza
        // condizione collegata.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Meteo", style = MaterialTheme.typography.titleMedium)
                Button(onClick = viewModel::refreshWeatherDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Controlla adesso")
                }
                if (weatherStatus.isNotEmpty()) {
                    Text(weatherStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // § "BPM non si aggiorna, manca ieri, manca il sonno della notte
        // appena trascorsa" — stesso principio della card Meteo sopra:
        // forza un refresh reale e mostra conteggi/range/tentato-vs-riuscito
        // grezzi invece di indovinare a quale stadio della pipeline si è
        // fermato (mai un valore bpm/sonno qui, solo conteggi e istanti).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Salute (Health Connect)", style = MaterialTheme.typography.titleMedium)
                Button(onClick = viewModel::refreshHealthDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Controlla adesso")
                }
                if (healthStatus.isNotEmpty()) {
                    Text(healthStatus, style = MaterialTheme.typography.bodySmall)
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
