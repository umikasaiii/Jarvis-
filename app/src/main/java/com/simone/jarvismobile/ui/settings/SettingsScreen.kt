package com.simone.jarvismobile.ui.settings

import com.simone.jarvismobile.core.speech.SpeechStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
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
import com.simone.jarvismobile.core.engine.JarvisEngineMode
import com.simone.jarvismobile.core.engine.ReasoningMode
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.ui.commands.CommandsScreen
import com.simone.jarvismobile.ui.components.ProModeBadge
import com.simone.jarvismobile.ui.theme.JarvisThemeId

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
    onOpenAutomations: () -> Unit = {},
    onOpenTranslator: () -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    onOpenMaps: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val persona by viewModel.personaPrompt.collectAsStateWithLifecycle()
    val autoMemoryCapture by viewModel.autoMemoryCapture.collectAsStateWithLifecycle()
    val neuralVoice by viewModel.neuralTts.collectAsStateWithLifecycle()
    val seconds by viewModel.recordSeconds.collectAsStateWithLifecycle()
    val useBluetooth by viewModel.useBluetooth.collectAsStateWithLifecycle()
    val followUpEnabled by viewModel.followUpEnabled.collectAsStateWithLifecycle()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val wakeWord by viewModel.wakeWord.collectAsStateWithLifecycle()
    val automationServiceEnabled by viewModel.automationServiceEnabled.collectAsStateWithLifecycle()
    val autoExpressive by viewModel.autoExpressive.collectAsStateWithLifecycle()
    val expressiveIntensity by viewModel.expressiveIntensity.collectAsStateWithLifecycle()
    val expressiveManualStyle by viewModel.expressiveManualStyle.collectAsStateWithLifecycle()
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
    val proModeActive by viewModel.proModeActive.collectAsStateWithLifecycle()
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()

    var nameField by remember(name) { mutableStateOf(name) }
    var personaField by remember(persona) { mutableStateOf(persona) }
    var wakeField by remember(wakeWord) { mutableStateOf(wakeWord) }
    var sliderValue by remember(seconds) { mutableStateOf(seconds.toFloat()) }
    var morningSlider by remember(reminderMorningHour) { mutableStateOf(reminderMorningHour.toFloat()) }
    var rateSlider by remember(ttsRate) { mutableStateOf(ttsRate) }
    var pitchSlider by remember(ttsPitch) { mutableStateOf(ttsPitch) }
    val ttsPause by viewModel.ttsPauseScale.collectAsStateWithLifecycle()
    val ttsExpr by viewModel.ttsExpressiveness.collectAsStateWithLifecycle()
    var pauseSlider by remember(ttsPause) { mutableStateOf(ttsPause) }
    var exprSlider by remember(ttsExpr) { mutableStateOf(ttsExpr) }

    val knowledgeStatus by viewModel.knowledgeStatus.collectAsStateWithLifecycle()
    val knowledgeName by viewModel.knowledgeName.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshKnowledgeName() }
    val knowledgeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.setKnowledgeFolder(uri) }
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

        CollapsibleSection("Modalità Pro") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Modalità Pro", style = MaterialTheme.typography.titleMedium)
                        if (proModeActive) {
                            Spacer(Modifier.size(8.dp))
                            ProModeBadge()
                        }
                    }
                    Switch(checked = proModeActive, onCheckedChange = viewModel::setProModeActive)
                }
                Text(
                    "Quando è attiva, ogni messaggio va direttamente al modello locale " +
                        "(niente scorciatoie né alias): puoi parlare liberamente di appunti, " +
                        "note, cose da fare e cose da vedere. Nessuna richiesta esce mai online. " +
                        "Si attiva/disattiva anche a voce: «attiva/esci dalla modalità pro».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        }

        CollapsibleSection("Temi") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tema grafico", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Classico è l'originale. Rosso ricolora bordi, glow e icone " +
                        "attive in rosso. Rouge fa lo stesso ma con l'orb, lo " +
                        "sfondo e le card sostituiti da grafica vera, non solo " +
                        "ricolorata. Atena ha anche una Home con blocchi propri, " +
                        "diversa dagli altri temi. Il resto (verde/ambra/viola " +
                        "di stato) resta fisso.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JarvisThemeId.entries.forEach { option ->
                        FilterChip(
                            selected = themeId == option,
                            onClick = { viewModel.setThemeId(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }
        }

        CollapsibleSection("Assistente e personalità") {
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

        // --- Personalità -----------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Personalità di JARVIS", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Come deve comportarsi e che tono usare. Vale dalla conversazione successiva " +
                        "(quella in corso mantiene il carattere con cui è iniziata). Tienilo breve: " +
                        "un testo troppo lungo toglie spazio alla memoria del modello.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PERSONA_PRESETS.forEach { (label, text) ->
                        OutlinedButton(onClick = { personaField = text }) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = personaField,
                    onValueChange = { personaField = it },
                    label = { Text("Istruzione di personalità") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.setPersonaPrompt(personaField) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Salva personalità") }
                    TextButton(onClick = { viewModel.resetPersonaPrompt() }) { Text("Predefinita") }
                }
                Text(
                    "Suggerimento: dopo aver salvato, tocca «Nuova conversazione» per applicarla subito.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Memoria contestuale --------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Ricorda i fatti utili", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = autoMemoryCapture, onCheckedChange = viewModel::setAutoMemoryCapture)
                }
                Text(
                    "Quando dici qualcosa di stabile su di te («mi chiamo…», «abito a…», " +
                        "«sono allergico a…»), JARVIS ti propone di salvarlo nella memoria. " +
                        "Chiede sempre conferma prima di scrivere; non salva mai password o " +
                        "codici, e i dati di salute restano marcati come sensibili 🔒. " +
                        "Tutto resta sul dispositivo, nessun vault richiesto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        }

        CollapsibleSection("Voce di JARVIS") {
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
                Text("Stile di voce", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Un preset muove insieme velocità, tono e ritmo. Il ritmo è ciò che " +
                        "distingue una voce parlata da una recitata.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpeechStyle.PRESETS.forEach { (label, preset) ->
                        OutlinedButton(
                            onClick = { viewModel.applySpeechPreset(preset) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
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
                // The neural graph has no pitch input, so this control would do
                // nothing there. Saying so beats a slider that quietly lies.
                if (neuralVoice.engineId.isNotBlank()) {
                    Text(
                        "Il tono vale solo per la voce Android: ${neuralVoice.engineLabel} " +
                            "non ha un parametro di tono. Velocità, pause ed espressività " +
                            "valgono per entrambe.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Durata delle pause: %.2f×".format(pauseSlider))
                Slider(
                    value = pauseSlider,
                    onValueChange = { pauseSlider = it },
                    onValueChangeFinished = { viewModel.setTtsPauseScale(pauseSlider) },
                    valueRange = 0f..2f,
                )
                Text("Espressività: %d%%".format((exprSlider * 100).toInt()))
                Slider(
                    value = exprSlider,
                    onValueChange = { exprSlider = it },
                    onValueChangeFinished = { viewModel.setTtsExpressiveness(exprSlider) },
                    valueRange = 0f..1f,
                )
                Text(
                    "A zero JARVIS legge di seguito, come una macchina. Al massimo respira " +
                        "fra le frasi e stacca sugli elenchi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::previewVoice, modifier = Modifier.fillMaxWidth()) {
                    Text("Ascolta un esempio")
                }
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

        // --- Voce JARVIS (external neural TTS) --------------------------------
        VoiceSection(viewModel)

        }

        CollapsibleSection("Memoria e conoscenza") {
        // --- Offline library ------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conoscenza offline", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Una cartella con guide, manuali ed esportazioni wiki in .md o .txt. " +
                        "JARVIS cerca qui PRIMA di rispondere e cita la fonte; se non trova " +
                        "nulla di pertinente te lo dice, invece di inventare.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "È separata dal vault: una voce di enciclopedia non è un tuo appunto " +
                        "e non deve finire nella memoria personale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        !knowledgeStatus.configured -> "Nessuna cartella collegata."
                        knowledgeStatus.indexing -> "Indicizzazione in corso…"
                        knowledgeStatus.lastError != null ->
                            "Errore di lettura: ${knowledgeStatus.lastError}"
                        else -> "${knowledgeName ?: "cartella"} · " +
                            "${knowledgeStatus.documentCount} documenti, " +
                            "${knowledgeStatus.passageCount} passaggi"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { knowledgeLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (knowledgeStatus.configured) "Cambia cartella" else "Scegli cartella")
                }
                if (knowledgeStatus.configured) {
                    OutlinedButton(
                        onClick = viewModel::reindexKnowledge,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Reindicizza")
                    }
                    TextButton(
                        onClick = viewModel::clearKnowledgeFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Scollega la libreria")
                    }
                }
            }
        }

        SemanticMemorySection()
        }

        CollapsibleSection("Audio e conversazione") {
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Parola di attivazione", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = wakeWordEnabled, onCheckedChange = viewModel::setWakeWordEnabled)
                }
                OutlinedTextField(
                    value = wakeField,
                    onValueChange = { wakeField = it },
                    label = { Text("Parola") },
                    singleLine = true,
                    enabled = wakeWordEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { viewModel.setWakeWord(wakeField) },
                    enabled = wakeWordEnabled && wakeField.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Salva parola") }
                Text(
                    "Quando è attiva e l'app è aperta, JARVIS ascolta questa parola e avvia " +
                        "l'ascolto da solo. Funziona SOLO ad app aperta e in primo piano: si " +
                        "ferma appena esci o metti l'app in secondo piano. Nessun microfono in " +
                        "background, nessun permesso aggiuntivo oltre a quello del microfono. " +
                        "Tutto offline sul dispositivo.",
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
                    Text("Espressività automatica", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = autoExpressive, onCheckedChange = viewModel::setAutoExpressive)
                }
                Text(
                    "JARVIS adatta tono, ritmo, pause ed enfasi al senso della risposta e al " +
                        "contesto, mantenendo sempre la stessa voce. In conversazione normale " +
                        "resta naturale e poco marcata, non teatrale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (autoExpressive) {
                    Text("Intensità", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("bassa" to "Bassa", "media" to "Media", "alta" to "Alta").forEach { (key, label) ->
                            OutlinedButton(
                                onClick = { viewModel.setExpressiveIntensity(key) },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (expressiveIntensity == key) "● $label" else label) }
                        }
                    }
                } else {
                    Text("Stile fisso", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "naturale" to "Naturale", "calma" to "Calma", "allegra" to "Allegra",
                            "seria" to "Seria", "energica" to "Energica", "delicata" to "Delicata",
                            "autorevole" to "Autorevole",
                        ).forEach { (key, label) ->
                            OutlinedButton(onClick = { viewModel.setExpressiveManualStyle(key) }) {
                                Text(if (expressiveManualStyle == key) "● $label" else label)
                            }
                        }
                    }
                }
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

        }

        CollapsibleSection("Widget, notifiche e documenti") {
        InterfaceSettingsSection()

        DocumentSettingsSection(onOpenArchive = onOpenDocuments)

        }

        CollapsibleSection("Navigazione e traduttore") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Navigazione offline", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Navigatore GPS che funziona senza Internet una volta scaricata la " +
                        "regione. Mappa e posizione sono attive; routing vocale e ricerca " +
                        "arrivano con i dati offline della regione.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenNavigation, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri navigazione")
                }
                OutlinedButton(onClick = onOpenMaps, modifier = Modifier.fillMaxWidth()) {
                    Text("Mappe offline")
                }
                OutlinedButton(onClick = onOpenFavorites, modifier = Modifier.fillMaxWidth()) {
                    Text("Preferiti e cronologia")
                }
            }
        }

        TranslatorSettingsSection(onOpenTranslator = onOpenTranslator)

        }

        // La barra di navigazione in basso (Home/Chat/Comandi/Attività/
        // Impostazioni) è stata rimossa (§ richiesta esplicita dell'utente:
        // "la home resta fissa... comandi puoi mettere una sezione apribile
        // nelle impostazioni") — Comandi non è più uno schermo a sé, ma una
        // sezione apribile qui, stesso componente riusato tale e quale.
        CollapsibleSection("Comandi") {
        CommandsScreen()
        }

        CollapsibleSection("Automazioni in background") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Automazioni attive in background", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = automationServiceEnabled,
                        onCheckedChange = viewModel::setAutomationServiceEnabled,
                    )
                }
                Text(
                    "Se attivo, le automazioni legate a eventi del telefono (sblocco, cuffie, " +
                        "modalità aereo, Wi-Fi, dati) possono scattare anche ad app chiusa. " +
                        "Android richiede una notifica fissa: JARVIS la tiene al minimo, " +
                        "silenziosa e senza icona nella barra di stato. Consumo basso: nessun " +
                        "GPS, nessun microfono, si attiva solo quando l'evento accade.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenAutomations, modifier = Modifier.fillMaxWidth()) {
                    Text("Gestisci le automazioni")
                }
            }
        }
        }

        CollapsibleSection("Motore JARVIS") {
        EngineSettingsSection()
        }

        CollapsibleSection("Proattività") {
        ProactiveSettingsSection()
        }

        CollapsibleSection("Backup") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup e sincronizzazione", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Backup serale automatico, locale e cifrato (AES-256), dei soli dati di " +
                        "JARVIS. Funziona offline; il cloud è una copia opzionale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri Backup e sincronizzazione")
                }
            }
        }

        }

        CollapsibleSection("Altro e sistema") {
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
            Text("Memoria")
        }
        OutlinedButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
            Text("Archivio (note e da vedere)")
        }
        OutlinedButton(onClick = onOpenAutomations, modifier = Modifier.fillMaxWidth()) {
            Text("Automazioni")
        }
        OutlinedButton(onClick = viewModel::newConversation, modifier = Modifier.fillMaxWidth()) {
            Text("Nuova conversazione (svuota memoria)")
        }
        OutlinedButton(onClick = viewModel::resetAudio, modifier = Modifier.fillMaxWidth()) {
            Text("Reset audio")
        }
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

/**
 * A collapsible group of settings. The header toggles the content open/closed so
 * the long settings list stays tidy; each section remembers its own state.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/**
 * Live Translator settings: the offline language models (download/delete),
 * Wi-Fi-only downloads, spoken output, and a shortcut into the translator itself.
 * Self-contained (its own ViewModel) so it doesn't bloat [SettingsViewModel].
 */
@Composable
private fun TranslatorSettingsSection(
    onOpenTranslator: () -> Unit,
    viewModel: com.simone.jarvismobile.ui.livetranslate.LiveTranslatorViewModel = hiltViewModel(),
) {
    val statuses by viewModel.modelStatuses.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Traduttore Live", style = MaterialTheme.typography.titleMedium)
            Text(
                "Traduzione vocale offline tra due lingue. Scarica una volta i modelli, " +
                    "poi funziona anche in modalità aereo.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Lingue offline", style = MaterialTheme.typography.titleSmall)
            com.simone.jarvismobile.core.translate.TranslationLanguage.entries.forEach { lang ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(lang.display, modifier = Modifier.weight(1f))
                    when (statuses[lang]) {
                        com.simone.jarvismobile.translate.ModelStatus.DOWNLOADED ->
                            TextButton(onClick = { viewModel.deleteModel(lang) }) { Text("Rimuovi") }
                        com.simone.jarvismobile.translate.ModelStatus.DOWNLOADING ->
                            Text("Scarico…", style = MaterialTheme.typography.labelMedium)
                        else ->
                            TextButton(onClick = { viewModel.downloadModel(lang) }) { Text("Scarica") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                Text("  Scarica solo su Wi-Fi")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = ttsEnabled, onCheckedChange = viewModel::setTtsEnabled)
                Text("  Leggi la traduzione ad alta voce")
            }
            OutlinedButton(onClick = onOpenTranslator, modifier = Modifier.fillMaxWidth()) {
                Text("Apri il Traduttore Live")
            }
        }
    }
}

/**
 * «Memoria semantica»: import of an optional on-device embedding model so recall
 * works by meaning, not just words. Without a model, retrieval stays lexical.
 */
@Composable
private fun SemanticMemorySection(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val status by viewModel.embeddingStatus.collectAsStateWithLifecycle()
    val modelPath by viewModel.embeddingModelPath.collectAsStateWithLifecycle()
    val vocabPath by viewModel.embeddingVocabPath.collectAsStateWithLifecycle()
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importEmbeddingModel(uri)
    }
    val vocabLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importEmbeddingVocab(uri)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Memoria semantica", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ritrova i tuoi ricordi per significato, non solo per parole. Importa un " +
                    "modello di embedding ONNX più il suo vocab.txt (nulla è incluso, tutto " +
                    "resta sul dispositivo). Senza modello, la ricerca resta per parole.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    status.ready -> "Pronto · dimensione ${status.dimension}"
                    modelPath.isBlank() || vocabPath.isBlank() -> "Manca il modello o il vocabolario."
                    status.error != null -> "Modello non caricato: file non compatibile."
                    else -> "Importato · verrà caricato alla prossima ricerca."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { modelLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (modelPath.isBlank()) "Importa modello (.onnx)" else "Cambia modello (.onnx)")
            }
            OutlinedButton(onClick = { vocabLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (vocabPath.isBlank()) "Importa vocab.txt" else "Cambia vocab.txt")
            }
            if (modelPath.isNotBlank() || vocabPath.isNotBlank()) {
                TextButton(onClick = viewModel::clearEmbeddingModel, modifier = Modifier.fillMaxWidth()) {
                    Text("Rimuovi il modello")
                }
            }
        }
    }
}

/**
 * «Motore JARVIS»: Classico (oggi, invariato) vs Conversazionale AI (il nuovo
 * orchestratore LLM-first). Un solo switch, esattamente come Modalità Pro —
 * quando è su Conversazionale, Modalità Pro (sotto, nella sezione Modello AI)
 * resta comunque raggiungibile solo passando a Classico: le due cose sono
 * indipendenti per design (vedi `docs/CONVERSATIONAL_ENGINE.md`).
 * Self-contained (its own ViewModel).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineSettingsSection(
    viewModel: EngineSettingsViewModel = hiltViewModel(),
) {
    val engineMode by viewModel.engineMode.collectAsStateWithLifecycle()
    val reasoningMode by viewModel.reasoningMode.collectAsStateWithLifecycle()
    val memoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val fastPathEnabled by viewModel.fastPathEnabled.collectAsStateWithLifecycle()
    val autoContextEnabled by viewModel.autoContextEnabled.collectAsStateWithLifecycle()
    val diagnosticsVerbose by viewModel.diagnosticsVerbose.collectAsStateWithLifecycle()
    val toolLoopCap by viewModel.toolLoopCap.collectAsStateWithLifecycle()
    val memoryTopN by viewModel.memoryTopN.collectAsStateWithLifecycle()
    val conversationalSlot by viewModel.conversationalModelSlot.collectAsStateWithLifecycle()
    val conversational = engineMode == JarvisEngineMode.CONVERSAZIONALE
    var confirmClearMemory by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                "Motore conversazionale AI",
                conversational,
                { on -> viewModel.setEngineMode(if (on) JarvisEngineMode.CONVERSAZIONALE else JarvisEngineMode.CLASSICO) },
            )
            Text(
                "Classico (predefinito): comandi deterministici, con l'AI dove già la usi oggi " +
                    "(classificatore, Modalità Pro). Conversazionale: il modello locale ragiona " +
                    "prima, con memoria e più turni — usa comunque gli stessi strumenti. Se il " +
                    "modello non è caricato, JARVIS torna automaticamente al motore Classico.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (conversational) {
                HorizontalDivider()
                Text("Modalità di ragionamento", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = mapOf(
                        ReasoningMode.FAST to "Veloce",
                        ReasoningMode.AUTO to "Automatica",
                        ReasoningMode.DEEP to "Approfondita",
                    )
                    labels.forEach { (mode, label) ->
                        FilterChip(
                            selected = reasoningMode == mode,
                            onClick = { viewModel.setReasoningMode(mode) },
                            label = { Text(label) },
                        )
                    }
                }

                HorizontalDivider()
                SwitchRow("Memoria conversazionale", memoryEnabled, viewModel::setMemoryEnabled)
                SwitchRow("Risposta a frasi (streaming)", streamingEnabled, viewModel::setStreamingEnabled)
                SwitchRow("Comandi rapidi (fast path)", fastPathEnabled, viewModel::setFastPathEnabled)
                SwitchRow("Contesto automatico dalla memoria", autoContextEnabled, viewModel::setAutoContextEnabled)
                SwitchRow("Diagnostica dettagliata in Diagnostica", diagnosticsVerbose, viewModel::setDiagnosticsVerbose)

                HorizontalDivider()
                Text("Slot modello per il motore conversazionale", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = conversationalSlot == "fast",
                        onClick = { viewModel.setConversationalModelSlot("fast") },
                        label = { Text("Rapido") },
                    )
                    FilterChip(
                        selected = conversationalSlot == "advanced",
                        onClick = { viewModel.setConversationalModelSlot("advanced") },
                        label = { Text("Avanzato") },
                    )
                }

                HorizontalDivider()
                var loopSlider by remember(toolLoopCap) { mutableStateOf(toolLoopCap.toFloat()) }
                Text("Massimo operazioni per turno: ${loopSlider.toInt()}")
                Slider(
                    value = loopSlider,
                    onValueChange = { loopSlider = it },
                    onValueChangeFinished = { viewModel.setToolLoopCap(loopSlider.toInt()) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
                var memorySlider by remember(memoryTopN) { mutableStateOf(memoryTopN.toFloat()) }
                Text("Memorie recuperate per turno: ${memorySlider.toInt()}")
                Slider(
                    value = memorySlider,
                    onValueChange = { memorySlider = it },
                    onValueChangeFinished = { viewModel.setMemoryTopN(memorySlider.toInt()) },
                    valueRange = 0f..20f,
                    steps = 19,
                    enabled = memoryEnabled,
                )

                HorizontalDivider()
                Text("Gestisci memoria conversazionale", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Cancella solo la memoria episodica del motore conversazionale (operazioni in " +
                        "corso, richiami recenti). I ricordi permanenti in Memoria non sono toccati.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        if (confirmClearMemory) {
                            viewModel.clearConversationalMemory()
                            confirmClearMemory = false
                        } else {
                            confirmClearMemory = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (confirmClearMemory) "Conferma cancellazione" else "Cancella memoria conversazionale",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * «Proattività»: JARVIS che parla per primo, con giudizio. Master switch, budget,
 * ore di silenzio e un interruttore per categoria; i tipi silenziati dal messaggio
 * si riattivano da qui. Self-contained (its own ViewModel).
 */
@Composable
private fun ProactiveSettingsSection(
    viewModel: ProactiveSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val maxPerDay by viewModel.maxPerDay.collectAsStateWithLifecycle()
    val quietStart by viewModel.quietStart.collectAsStateWithLifecycle()
    val quietEnd by viewModel.quietEnd.collectAsStateWithLifecycle()
    val disabled by viewModel.disabledKinds.collectAsStateWithLifecycle()
    val muted by viewModel.mutedKinds.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow("Proattività", enabled, viewModel::setEnabled)
            Text(
                "Quando ha senso, JARVIS ti avvisa per primo (riepiloghi, promemoria utili) " +
                    "con giudizio: mai più del limite al giorno, mai nelle ore di silenzio, " +
                    "e ogni tipo è disattivabile. Tutto sul dispositivo, nessun permesso nuovo.",
                style = MaterialTheme.typography.bodySmall,
            )

            var maxSlider by remember(maxPerDay) { mutableStateOf(maxPerDay.toFloat()) }
            Text("Massimo al giorno: ${maxSlider.toInt()}")
            Slider(
                value = maxSlider,
                onValueChange = { maxSlider = it },
                onValueChangeFinished = { viewModel.setMaxPerDay(maxSlider.toInt()) },
                valueRange = 1f..6f,
                steps = 4,
                enabled = enabled,
            )

            var startSlider by remember(quietStart) { mutableStateOf(quietStart.toFloat()) }
            var endSlider by remember(quietEnd) { mutableStateOf(quietEnd.toFloat()) }
            Text("Silenzio dalle %02d:00 alle %02d:00".format(startSlider.toInt(), endSlider.toInt()))
            Slider(
                value = startSlider,
                onValueChange = { startSlider = it },
                onValueChangeFinished = { viewModel.setQuietHours(startSlider.toInt(), endSlider.toInt()) },
                valueRange = 0f..23f,
                enabled = enabled,
            )
            Slider(
                value = endSlider,
                onValueChange = { endSlider = it },
                onValueChangeFinished = { viewModel.setQuietHours(startSlider.toInt(), endSlider.toInt()) },
                valueRange = 0f..23f,
                enabled = enabled,
            )

            HorizontalDivider()
            Text("Tipi di intervento", style = MaterialTheme.typography.titleSmall)
            viewModel.categories.forEach { (kind, label) ->
                val on = kind.name !in disabled
                SwitchRow(label, on) { viewModel.setCategoryEnabled(kind, it) }
                if (kind.name in muted && on) {
                    Text(
                        "Silenziato da un messaggio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Interfaccia → Widget e notifiche. Self-contained (its own ViewModel). */
@Composable
private fun InterfaceSettingsSection(
    viewModel: InterfaceSettingsViewModel = hiltViewModel(),
) {
    val showStatus by viewModel.widgetShowStatus.collectAsStateWithLifecycle()
    val style by viewModel.widgetStyle.collectAsStateWithLifecycle()
    val transparency by viewModel.widgetTransparency.collectAsStateWithLifecycle()
    val respNotif by viewModel.responseNotifications.collectAsStateWithLifecycle()
    val remNotif by viewModel.reminderNotifications.collectAsStateWithLifecycle()
    val sound by viewModel.notifSound.collectAsStateWithLifecycle()
    val vibration by viewModel.notifVibration.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Interfaccia — Widget e notifiche", style = MaterialTheme.typography.titleMedium)

            Text("Widget", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Mostra stato nel widget", showStatus, viewModel::setWidgetShowStatus)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Stile widget:")
                Spacer(Modifier.size(8.dp))
                listOf("compatto", "standard").forEach { opt ->
                    val on = style == opt
                    TextButton(onClick = { viewModel.setWidgetStyle(opt) }) {
                        Text(if (on) "● $opt" else opt)
                    }
                }
            }
            Text("Trasparenza widget: ${(transparency * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = transparency,
                onValueChange = viewModel::setWidgetTransparency,
                valueRange = 0.2f..1f,
            )

            HorizontalDivider()
            Text("Notifiche", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Notifiche risposte", respNotif, viewModel::setResponseNotifications)
            SwitchRow("Notifiche promemoria", remNotif, viewModel::setReminderNotifications)
            SwitchRow("Suono notifiche", sound, viewModel::setNotifSound)
            SwitchRow("Vibrazione", vibration, viewModel::setNotifVibration)
        }
    }
}

/** Memoria & Conoscenza → Documenti. Shares the interface settings ViewModel. */
@Composable
private fun DocumentSettingsSection(
    onOpenArchive: () -> Unit,
    viewModel: InterfaceSettingsViewModel = hiltViewModel(),
) {
    val saveDefault by viewModel.docSaveToVaultDefault.collectAsStateWithLifecycle()
    val autoIndex by viewModel.docAutoIndex.collectAsStateWithLifecycle()
    val dedup by viewModel.docDedup.collectAsStateWithLifecycle()
    val ocr by viewModel.docOcrImages.collectAsStateWithLifecycle()
    val vaultConfigured by viewModel.vaultConfigured.collectAsStateWithLifecycle()
    val vaultName by viewModel.vaultName.collectAsStateWithLifecycle()
    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::pickVault) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Memoria & Conoscenza — Documenti", style = MaterialTheme.typography.titleMedium)
            Text(
                "I documenti allegati dalla chat («+») vengono copiati in privato e, se " +
                    "scegli l'archivio, anche nel vault Obsidian in 20_KNOWLEDGE/Documents. " +
                    "L'originale non viene mai modificato.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow("Salva nell'archivio per default", saveDefault, viewModel::setDocSaveToVaultDefault)
            SwitchRow("Indicizza per la ricerca (RAG)", autoIndex, viewModel::setDocAutoIndex)
            SwitchRow("Rileva duplicati (SHA-256)", dedup, viewModel::setDocDedup)
            SwitchRow("OCR immagini (più lento, opzionale)", ocr, viewModel::setDocOcrImages)
            OutlinedButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
                Text("Archivio documenti")
            }
            HorizontalDivider()
            Text("Vault Obsidian (facoltativo)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (vaultConfigured) "Collegato: ${vaultName ?: "—"}" else "Nessun vault collegato",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Usato solo da Documenti (copia opzionale) e da Agenda/Automazioni (mirror " +
                    "facoltativo, non richiesto). Memoria non lo usa più: è un archivio " +
                    "interamente locale.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { pickVault.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (vaultConfigured) "Cambia cartella vault" else "Collega un vault")
            }
            if (vaultConfigured) {
                OutlinedButton(onClick = viewModel::disconnectVault, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnetti vault")
                }
            }
        }
    }
}

/** Ready-made personalities the user can start from and then tweak. */
private val PERSONA_PRESETS: List<Pair<String, String>> = listOf(
    "Ironico (Stark)" to (
        "Hai un carattere asciutto e sicuro, con un tocco di ironia british alla Tony Stark. " +
            "Dai del tu, vai dritto al punto, niente frasi di circostanza o servili. " +
            "Sei competente e leale; se qualcosa non si può fare lo dici con garbo, senza girarci intorno."
        ),
    "Formale" to (
        "Ti esprimi in modo cortese e professionale, con frasi complete e precise. " +
            "Dai del Lei, eviti battute e resti misurato, ma sempre chiaro e diretto."
        ),
    "Essenziale" to (
        "Rispondi nel modo più breve possibile: una o due frasi, nessun preambolo, " +
            "nessuna ripetizione della domanda. Vai solo al risultato."
        ),
    "Amichevole" to (
        "Sei caldo, incoraggiante e alla mano, come un amico competente. Dai del tu, " +
            "usi un tono positivo e rassicurante, ma resti concreto e onesto."
        ),
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
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
