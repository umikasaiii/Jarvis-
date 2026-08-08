package com.simone.jarvismobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.tts.NeuralTtsEngines
import com.simone.jarvismobile.tts.NeuralTtsState
import com.simone.jarvismobile.tts.NeuralTtsStatus
import com.simone.jarvismobile.tts.TtsAsset
import com.simone.jarvismobile.tts.TtsAssetKind

/**
 * "Voce JARVIS" — the external local TTS section.
 *
 * Everything here is about files the user supplies: no model is bundled and none
 * is downloaded. The section shows what is selected, how big it is and whether
 * it actually loaded, because the failure mode of a hand-imported model is a
 * silent one and the user needs somewhere to look.
 */
@Composable
fun VoiceSection(viewModel: SettingsViewModel) {
    val state by viewModel.neuralTts.collectAsStateWithLifecycle()
    val speechEnabled by viewModel.ttsSpeechEnabled.collectAsStateWithLifecycle()
    val streaming by viewModel.ttsStreaming.collectAsStateWithLifecycle()
    val volume by viewModel.ttsVolume.collectAsStateWithLifecycle()
    val rate by viewModel.ttsSpeechRate.collectAsStateWithLifecycle()
    val imported by viewModel.importedTtsAssets.collectAsStateWithLifecycle()
    val busy by viewModel.ttsBusy.collectAsStateWithLifecycle()
    val message by viewModel.ttsMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshNeuralTts() }

    var pendingKind by remember { mutableStateOf(TtsAssetKind.MODEL) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importTtsAsset(it, pendingKind) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Voce JARVIS", style = MaterialTheme.typography.titleMedium)
            Text(
                "Voce neurale locale, opzionale. I file restano sul telefono e " +
                    "l'inferenza è offline; nessun modello è incluso nell'app.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()

            // --- on/off -----------------------------------------------------
            SwitchRow("Risposta vocale", speechEnabled, viewModel::setTtsSpeechEnabled)

            // --- engine -----------------------------------------------------
            EnginePicker(
                current = state.engineId,
                engines = viewModel.availableTtsEngines(),
                enabled = !busy,
                onPick = viewModel::setTtsEngine,
            )

            if (state.engineId != NeuralTtsEngines.NONE) {
                Text("Stato: ${statusLabel(state.status)}", style = MaterialTheme.typography.bodyMedium)
                if (state.vocabularySource.isNotBlank()) {
                    Text(
                        "Vocabolario fonemi: ${state.vocabularySource}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.detail.isNotBlank()) {
                    Text(state.detail, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                AssetRow(
                    title = "Modello (.onnx)",
                    asset = state.model,
                    enabled = !busy,
                    onImport = { pendingKind = TtsAssetKind.MODEL; picker.launch("*/*") },
                    onRemove = { viewModel.clearTtsAsset(TtsAssetKind.MODEL, deleteFile = false) },
                    onDelete = { viewModel.clearTtsAsset(TtsAssetKind.MODEL, deleteFile = true) },
                )
                AssetRow(
                    title = "Voci (voices-v1.0.bin)",
                    asset = state.voicesFile,
                    enabled = !busy,
                    onImport = { pendingKind = TtsAssetKind.VOICES; picker.launch("*/*") },
                    onRemove = { viewModel.clearTtsAsset(TtsAssetKind.VOICES, deleteFile = false) },
                    onDelete = { viewModel.clearTtsAsset(TtsAssetKind.VOICES, deleteFile = true) },
                )
                AssetRow(
                    title = "Vocabolario (tokens.txt o config.json) — opzionale",
                    asset = state.vocabulary,
                    enabled = !busy,
                    onImport = { pendingKind = TtsAssetKind.VOCABULARY; picker.launch("*/*") },
                    onRemove = { viewModel.clearTtsAsset(TtsAssetKind.VOCABULARY, deleteFile = false) },
                    onDelete = { viewModel.clearTtsAsset(TtsAssetKind.VOCABULARY, deleteFile = true) },
                )

                // Files already copied in can be re-assigned without picking again.
                if (imported.isNotEmpty()) {
                    HorizontalDivider()
                    Text("File già importati", style = MaterialTheme.typography.titleSmall)
                    imported.forEach { asset ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(asset.name, style = MaterialTheme.typography.bodyMedium)
                                Text(asset.sizeLabel, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.selectTtsAsset(asset.path, asset.kind) },
                                enabled = !busy,
                            ) { Text("Usa") }
                        }
                    }
                }

                HorizontalDivider()

                // --- voice --------------------------------------------------
                VoicePicker(
                    state = state,
                    enabled = !busy,
                    onPick = viewModel::setNeuralVoice,
                )

                // --- parameters ---------------------------------------------
                var volumeSlider by remember(volume) { mutableStateOf(volume) }
                Text("Volume: %d%%".format((volumeSlider * 100).toInt()))
                Slider(
                    value = volumeSlider,
                    onValueChange = { volumeSlider = it },
                    onValueChangeFinished = { viewModel.setTtsVolume(volumeSlider) },
                    valueRange = 0f..1f,
                )

                var rateSlider by remember(rate) { mutableStateOf(rate) }
                Text("Velocità: %.2f×".format(rateSlider))
                Slider(
                    value = rateSlider,
                    onValueChange = { rateSlider = it },
                    onValueChangeFinished = { viewModel.setTtsSpeechRate(rateSlider) },
                    valueRange = 0.6f..1.4f,
                )

                SwitchRow(
                    "Streaming frase per frase",
                    streaming,
                    viewModel::setTtsStreaming,
                )
                Text(
                    "Con lo streaming JARVIS inizia a parlare dopo la prima frase " +
                        "invece di sintetizzare tutta la risposta.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Button(
                    onClick = viewModel::testNeuralVoice,
                    enabled = !busy && state.voices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Attendi…" else "Prova voce") }
                Text(
                    "Sintetizza davvero una frase con il modello importato e la " +
                        "voce selezionata. Il primo avvio carica il modello e può " +
                        "richiedere qualche secondo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::previewVoice,
                        enabled = !busy && speechEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Anteprima stile") }
                    OutlinedButton(
                        onClick = viewModel::reloadNeuralTts,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Ricarica") }
                }
            }

            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EnginePicker(
    current: String,
    engines: List<Pair<String, String>>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = engines.firstOrNull { it.first == current }?.second ?: "Voce Android (predefinita)"
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Motore: $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Voce Android (predefinita)") },
                onClick = { open = false; onPick(NeuralTtsEngines.NONE) },
            )
            engines.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

@Composable
private fun VoicePicker(state: NeuralTtsState, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (state.voices.isEmpty()) {
        Text(
            "Nessuna voce: importa il file voci (voices-v1.0.bin).",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text("Voci disponibili: ${state.voices.size}", style = MaterialTheme.typography.bodySmall)
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Voce: ${state.selectedVoice.ifBlank { "—" }}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("$voice${voiceHint(voice)}") },
                    onClick = { open = false; onPick(voice) },
                )
            }
        }
    }
}

/**
 * Kokoro encodes language and gender in the prefix: first letter the language
 * (a/b English, i Italian, e Spanish, f French, p Portuguese, h Hindi, j
 * Japanese, z Chinese), second the gender. Spelling it out saves the user
 * guessing what "af_bella" is.
 */
private fun voiceHint(voice: String): String {
    if (voice.length < 3 || voice[2] != '_') return ""
    val language = when (voice[0]) {
        'a' -> "inglese US"
        'b' -> "inglese UK"
        'i' -> "italiano"
        'e' -> "spagnolo"
        'f' -> "francese"
        'p' -> "portoghese"
        'h' -> "hindi"
        'j' -> "giapponese"
        'z' -> "cinese"
        else -> return ""
    }
    val gender = when (voice[1]) {
        'f' -> "F"
        'm' -> "M"
        else -> return " · $language"
    }
    return " · $language $gender"
}

@Composable
private fun AssetRow(
    title: String,
    asset: TtsAsset?,
    enabled: Boolean,
    onImport: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (asset == null) {
            Text("Nessun file selezionato.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(asset.name, style = MaterialTheme.typography.bodyMedium)
            Text(asset.path, style = MaterialTheme.typography.bodySmall)
            Text(
                "${asset.sizeLabel} · ${if (asset.exists) "presente" else "MANCANTE"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = enabled) {
                Text(if (asset == null) "Importa" else "Cambia")
            }
            if (asset != null) {
                OutlinedButton(onClick = onRemove, enabled = enabled) { Text("Rimuovi") }
                OutlinedButton(onClick = onDelete, enabled = enabled) { Text("Elimina file") }
            }
        }
    }
}

private fun statusLabel(status: NeuralTtsStatus): String = when (status) {
    NeuralTtsStatus.OFF -> "voce Android"
    NeuralTtsStatus.INCOMPLETE -> "file mancanti"
    NeuralTtsStatus.READY_TO_LOAD -> "pronto (si carica alla prima risposta)"
    NeuralTtsStatus.LOADING -> "caricamento…"
    NeuralTtsStatus.LOADED -> "caricato"
    NeuralTtsStatus.ERROR -> "errore"
}
