package com.simone.jarvismobile.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.simone.jarvismobile.llm.EmbeddingLoadState
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LocalModel

/**
 * Models screen (Phase 3): import an on-device LLM model file (LiteRT-LM
 * .litertlm) and load it into memory. No model is bundled; the file is copied
 * into app-private storage (docs/MODELS.md).
 */
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val loadedName by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val advancedName by viewModel.advancedModelName.collectAsStateWithLifecycle()
    val classifierName by viewModel.classifierModelName.collectAsStateWithLifecycle()
    val semanticState by viewModel.semanticClassifierLoadState.collectAsStateWithLifecycle()
    val semanticName by viewModel.semanticClassifierModelName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val heavyPrompt by viewModel.heavyPrompt.collectAsStateWithLifecycle()
    val experimental by viewModel.experimentalHeavy.collectAsStateWithLifecycle(initialValue = false)
    val reports by viewModel.loadReports.collectAsStateWithLifecycle()
    val parked by viewModel.parkedSlots.collectAsStateWithLifecycle()
    val autoNote by viewModel.autoLoadNote.collectAsStateWithLifecycle()
    val interrupted by viewModel.interruptedLoads.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshMemory() }

    heavyPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissHeavy() },
            title = { Text("Memoria libera insufficiente (stima)") },
            text = {
                Text(
                    p.message + "\n\nPuoi tentare comunque: se il modello è compatibile potrebbe funzionare; altrimenti potrebbe essere lento, " +
                        "fallire con un errore o far chiudere l'app. Il modello non viene mai eliminato.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmHeavy() }) { Text("Carica comunque") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissHeavy() }) { Text("Annulla") } },
        )
    }

    // § FASE 2A.11 — EmbeddingGemma needs TWO files at once (`.tflite` +
    // SentencePiece tokenizer); this two-step pick mirrors the existing
    // per-row assignment buttons instead of a second import flow.
    var pendingTflite by remember { mutableStateOf<LocalModel?>(null) }
    var pendingTokenizer by remember { mutableStateOf<LocalModel?>(null) }

    // GetContent (ACTION_GET_CONTENT) is more permissive than OpenDocument on
    // OEM ROMs (MagicOS/EMUI grey out unknown types like .litertlm in the
    // document picker). It lets the user pick the model file from any file manager.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    val sileroStatus by viewModel.sileroStatus.collectAsStateWithLifecycle()
    val sileroLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.importSilero(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Modelli (AI locale)", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stato: ${loadStateLabel(loadState)}", style = MaterialTheme.typography.titleMedium)
                Text("Rapido: ${loadedName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Avanzato: ${advancedName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Classificatore: ${classifierName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Comprensione semantica: ${semanticName ?: "—"} (${semanticLoadStateLabel(semanticState)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // --- experimental heavy-model loading + what happened to the last loads -------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Consenti caricamento sperimentale di modelli pesanti", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Acceso: JARVIS tenta sempre il caricamento, anche se la RAM libera stimata è inferiore al consigliato, senza richiedere conferma. " +
                                "Il modello può risultare lento, fallire o far chiudere l'app. Spento (predefinito): con una stima insufficiente chiede «Carica comunque» ogni volta.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = experimental, onCheckedChange = { viewModel.setExperimentalHeavy(it) })
                }
                autoNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                interrupted.forEach { i ->
                    Text(
                        (if (i.phase == "generation") "Risposta interrotta: l'app si è fermata mentre «${i.name}» (${i.fileBytes / (1024 * 1024)} MiB) stava generando — la causa non è nota " +
                            "(memoria, crash nativo, blocco o altro: vedi i dati di diagnostica). "
                        else "Caricamento interrotto: «${i.name}» (${i.fileBytes / (1024 * 1024)} MiB) non è terminato — Android potrebbe aver chiuso l'app. ") +
                            "Il modello è ancora installato e non viene ricaricato da solo; puoi usare un modello più piccolo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { viewModel.dismissInterrupted(i.path) }) { Text("Ho capito, consenti di nuovo il caricamento automatico") }
                }
                if (parked.isNotEmpty()) {
                    Text("In pausa per fare spazio (selezionati, non eliminati): ${parked.joinToString { it.name.lowercase() }}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Diagnostica memoria", style = MaterialTheme.typography.titleSmall)
                Text(
                    "RAM fisica ${memory.totalBytes.mibOrNa()} · disponibile secondo Android ${memory.availBytes.mibOrNa()}" +
                        (if (memory.lowMemory) " · Android segnala memoria bassa" else "") + " · memoria del processo (PSS) ${memory.pssBytes.mibOrNa()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "«Disponibile» è un dato di sistema, non la quantità che l'app può allocare; la stima di consumo è un'euristica, non un limite hardware.",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (reports.isEmpty()) Text("Nessun caricamento in questa sessione.", style = MaterialTheme.typography.bodySmall)
                reports.forEach { (slot, r) ->
                    HorizontalDivider()
                    r.describe().forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = { viewModel.refreshMemory() }) { Text("Aggiorna misure") }
            }
        }

        Button(
            onClick = { importLauncher.launch("*/*") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Attendi…" else "Importa modello (.litertlm)")
        }

        if (models.isEmpty()) {
            Text(
                "Nessun modello importato. Tocca “Importa modello” e scegli un file .litertlm.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            models.forEach { model ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                        Text("${model.sizeBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            Button(onClick = { viewModel.load(model) }, enabled = !busy) { Text("Rapido") }
                            Button(onClick = { viewModel.loadAdvanced(model) }, enabled = !busy) { Text("Avanzato") }
                            Button(onClick = { viewModel.loadClassifier(model) }, enabled = !busy) { Text("Classificatore") }
                            OutlinedButton(
                                onClick = { pendingTflite = model },
                                enabled = !busy,
                            ) { Text(if (pendingTflite == model) "✓ Sem. modello" else "Sem. modello") }
                            OutlinedButton(
                                onClick = { pendingTokenizer = model },
                                enabled = !busy,
                            ) { Text(if (pendingTokenizer == model) "✓ Sem. tokenizer" else "Sem. tokenizer") }
                            OutlinedButton(onClick = { viewModel.delete(model) }, enabled = !busy) { Text("Elimina") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::unload, modifier = Modifier.weight(1f)) {
                    Text("Scarica rapido")
                }
                OutlinedButton(onClick = viewModel::unloadAdvanced, modifier = Modifier.weight(1f)) {
                    Text("Scarica avanzato")
                }
            }
            OutlinedButton(onClick = viewModel::unloadClassifier, modifier = Modifier.fillMaxWidth()) {
                Text("Scarica classificatore")
            }
            val tflite = pendingTflite
            val tokenizer = pendingTokenizer
            Button(
                onClick = { if (tflite != null && tokenizer != null) viewModel.loadSemanticClassifier(tflite, tokenizer) },
                enabled = !busy && tflite != null && tokenizer != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (tflite != null && tokenizer != null) {
                        "Carica comprensione semantica (${tflite.name} + ${tokenizer.name})"
                    } else {
                        "Scegli sopra il modello .tflite e il tokenizer, poi tocca qui"
                    },
                )
            }
            OutlinedButton(onClick = viewModel::unloadSemanticClassifier, modifier = Modifier.fillMaxWidth()) {
                Text("Scarica comprensione semantica")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tre modelli: rapido, avanzato, classificatore", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Assegna un modello piccolo (es. Gemma 1B) allo slot «Rapido»: gestisce " +
                        "comandi e risposte brevi, sempre reattivo. Assegnane uno grande " +
                        "(es. Gemma 4 E4B) allo slot «Avanzato»: verrà usato solo per le domande " +
                        "che richiedono ragionamento, spiegazioni o consigli. Assegnane uno " +
                        "minuscolo (es. Qwen 0.5B/0.8B quantizzato) allo slot «Classificatore»: " +
                        "entra in gioco SOLO quando dici qualcosa che gli alias rapidi non " +
                        "riconoscono subito, per capire quale comando intendi — non risponde mai " +
                        "in chat, non tocca la conversazione normale. Se imposti solo il rapido, " +
                        "funziona tutto come prima (il rapido fa anche da classificatore). Nota: " +
                        "ogni modello caricato in più occupa memoria.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                Text("Come ottenere un modello", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Serve un modello in formato LiteRT-LM (.litertlm). Con 8 GB di RAM va bene un " +
                        "Gemma piccolo (es. Gemma-3 1B quantizzato int4/int8), circa 0,5–2 GB. " +
                        "Il modo più semplice: installa l'app “Google AI Edge Gallery”, scarica lì un " +
                        "modello .litertlm (download verificato, niente file corrotti), poi con un file " +
                        "manager copialo e qui tocca “Importa modello”. In alternativa scaricalo dalla " +
                        "community LiteRT su Hugging Face. Nessun modello è incluso nell'app; tutto resta " +
                        "sul dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Comprensione semantica (EmbeddingGemma)", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Un quarto modello, diverso dai tre sopra: NON risponde mai, capisce solo il " +
                        "significato di quello che dici (agenda, meteo, salute, informazioni sul " +
                        "telefono, conoscenza generale) prima ancora che il modello che risponde " +
                        "entri in gioco. Servono due file importati insieme: " +
                        "embeddinggemma-300M_seq256_mixed-precision.tflite e sentencepiece.model. " +
                        "Senza questi due file, JARVIS passa direttamente al ciclo di ragionamento " +
                        "completo per capire ogni richiesta — funziona comunque, solo un po' più lento.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Silero VAD (rilevamento voce)", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Stato: " + when (sileroStatus.state) {
                        com.simone.jarvismobile.core.voice.silero.SileroModelState.NOT_IMPORTED -> "Non importato"
                        com.simone.jarvismobile.core.voice.silero.SileroModelState.LOADING -> "Caricamento…"
                        com.simone.jarvismobile.core.voice.silero.SileroModelState.READY -> "Pronto"
                        com.simone.jarvismobile.core.voice.silero.SileroModelState.INCOMPATIBLE,
                        com.simone.jarvismobile.core.voice.silero.SileroModelState.ERROR -> "Errore"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Modello di sola diagnostica per ora: non controlla ancora la conversazione. Scarica a mano " +
                        "il file ufficiale silero_vad.onnx (MIT, snakers4/silero-vad) e importalo qui: viene accettato " +
                        "solo se SHA-256, dimensione e grafo coincidono, poi resta sul telefono e funziona offline.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { sileroLauncher.launch("*/*") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Importa") }
                    OutlinedButton(onClick = viewModel::removeSilero, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Rimuovi") }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Indietro")
        }

        Text(
            "L'inferenza gira offline sul telefono. La prima risposta può essere lenta.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun loadStateLabel(s: LlmLoadState): String = when (s) {
    LlmLoadState.UNLOADED -> "Nessun modello"
    LlmLoadState.LOADING -> "Caricamento…"
    LlmLoadState.LOADED -> "Pronto"
    LlmLoadState.ERROR -> "Errore"
}

private fun semanticLoadStateLabel(s: EmbeddingLoadState): String = when (s) {
    EmbeddingLoadState.UNLOADED -> "non caricata"
    EmbeddingLoadState.LOADING -> "caricamento…"
    EmbeddingLoadState.LOADED -> "pronta"
    EmbeddingLoadState.ERROR -> "errore"
}

private fun Long.mibOrNa(): String = if (this < 0) "n/d" else "${this / (1024 * 1024)} MiB"
