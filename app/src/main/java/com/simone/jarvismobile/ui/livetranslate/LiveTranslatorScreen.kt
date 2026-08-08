package com.simone.jarvismobile.ui.livetranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.translate.ConversationSegment
import com.simone.jarvismobile.core.translate.LiveTranslationState
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.translate.ModelStatus

private val Cyan = Color(0xFF3FD8F0)
private val Muted = Color(0xFF7C8B95)

/**
 * The Live Translator screen: pick a language pair and mode, make sure the offline
 * models are present, then start the interpreter loop. The conversation appears as
 * paired source/translation lines; nothing here is stored beyond the session.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LiveTranslatorScreen(
    onBack: () -> Unit,
    viewModel: LiveTranslatorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val langA by viewModel.languageA.collectAsStateWithLifecycle()
    val langB by viewModel.languageB.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val statuses by viewModel.modelStatuses.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val listening by viewModel.listeningLanguage.collectAsStateWithLifecycle()

    // "Active" for the controls means a session is running or spinning up — any
    // state other than the two at-rest ones (Idle, and Error you can restart from).
    val active = state != LiveTranslationState.Idle && state !is LiveTranslationState.Error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Cyan)
            }
            Text("Traduttore Live", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Traduzione vocale offline, in tempo reale, tra due lingue. Non usa il " +
                "modello dell'assistente: una volta scaricati i modelli funziona anche " +
                "in modalità aereo.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        // --- Language pair + swap -------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Lingue", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LanguagePicker(
                        selected = langA,
                        enabled = !active,
                        onSelect = viewModel::setLanguageA,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.swap() }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Scambia", tint = Cyan)
                    }
                    LanguagePicker(
                        selected = langB,
                        enabled = !active,
                        onSelect = viewModel::setLanguageB,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Per-language offline model status + download.
                ModelRow(langA, statuses[langA], onDownload = { viewModel.downloadModel(langA) })
                ModelRow(langB, statuses[langB], onDownload = { viewModel.downloadModel(langB) })
            }
        }

        // --- Mode ------------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modalità", fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TranslationMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { if (!active) viewModel.setMode(m) },
                            enabled = !active,
                            label = { Text(m.label()) },
                        )
                    }
                }
                Text(mode.help(), style = MaterialTheme.typography.bodySmall, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ttsEnabled, onCheckedChange = viewModel::setTtsEnabled)
                    Spacer(Modifier.height(0.dp))
                    Text("  Leggi ad alta voce la traduzione")
                }
            }
        }

        // --- Controls --------------------------------------------------------
        StatusLine(state, listening)
        if (!active) {
            Button(onClick = { viewModel.start() }, modifier = Modifier.fillMaxWidth()) {
                Text("Avvia traduzione")
            }
        } else {
            if (mode == TranslationMode.PUSH_TO_TALK) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.pushToTalk(langA) }, modifier = Modifier.weight(1f)) {
                        Text("Parlo ${langA.display}")
                    }
                    OutlinedButton(onClick = { viewModel.pushToTalk(langB) }, modifier = Modifier.weight(1f)) {
                        Text("Parla ${langB.display}")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.clear() }, modifier = Modifier.weight(1f)) {
                    Text("Pulisci")
                }
                Button(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) {
                    Text("Ferma")
                }
            }
        }

        if (partial.isNotBlank()) {
            Text("… $partial", color = Cyan, style = MaterialTheme.typography.bodyMedium)
        }

        // --- Conversation ----------------------------------------------------
        if (segments.isNotEmpty()) {
            Text("Conversazione", fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(segments.asReversed(), key = { it.id }) { seg -> SegmentCard(seg) }
            }
        }
    }
}

@Composable
private fun StatusLine(state: LiveTranslationState, listening: TranslationLanguage?) {
    val text = when (state) {
        LiveTranslationState.Idle -> "Pronto ad avviare"
        LiveTranslationState.CheckingModels -> "Controllo modelli…"
        is LiveTranslationState.DownloadingModels -> "Scarico ${state.language?.display ?: "modelli"}…"
        LiveTranslationState.Ready -> "In attesa"
        LiveTranslationState.Listening -> "In ascolto${listening?.let { " (${it.display})" } ?: ""}…"
        LiveTranslationState.Transcribing -> "Trascrizione…"
        LiveTranslationState.Translating -> "Traduco…"
        LiveTranslationState.Speaking -> "Riproduco…"
        LiveTranslationState.Paused -> "In pausa"
        LiveTranslationState.Stopping -> "Arresto…"
        is LiveTranslationState.Error -> "Errore: ${state.reason}"
    }
    val color = if (state is LiveTranslationState.Error) Color(0xFFF3776B) else Cyan
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SegmentCard(seg: ConversationSegment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                seg.detectedLanguage.heading,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Text(seg.sourceText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                seg.targetLanguage.heading,
                style = MaterialTheme.typography.labelSmall,
                color = Cyan,
            )
            Text(seg.translatedText, style = MaterialTheme.typography.bodyLarge, color = Cyan)
        }
    }
}

@Composable
private fun ModelRow(
    lang: TranslationLanguage,
    status: ModelStatus?,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(lang.display, modifier = Modifier.weight(1f))
        when (status) {
            ModelStatus.DOWNLOADED -> Text("Pronto", color = Color(0xFF2ECC71), style = MaterialTheme.typography.labelMedium)
            ModelStatus.DOWNLOADING -> Text("Scarico…", color = Cyan, style = MaterialTheme.typography.labelMedium)
            ModelStatus.ERROR -> AssistChip(onClick = onDownload, label = { Text("Riprova") })
            else -> AssistChip(onClick = onDownload, label = { Text("Scarica") })
        }
    }
}

@Composable
private fun LanguagePicker(
    selected: TranslationLanguage,
    enabled: Boolean,
    onSelect: (TranslationLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x223FD8F0))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(selected.display, color = if (enabled) Cyan else Muted)
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationLanguage.entries.forEach { lang ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(lang.display) },
                    onClick = { onSelect(lang); expanded = false },
                )
            }
        }
    }
}

private fun TranslationMode.label(): String = when (this) {
    TranslationMode.AUTO -> "Automatico"
    TranslationMode.CONVERSAZIONE -> "Conversazione"
    TranslationMode.PUSH_TO_TALK -> "Push-to-talk"
    TranslationMode.SOLO_TESTO -> "Solo testo"
    TranslationMode.VOCE_TESTO -> "Voce + testo"
    TranslationMode.SOLO_ASCOLTO -> "Solo ascolto"
}

private fun TranslationMode.help(): String = when (this) {
    TranslationMode.AUTO -> "Rileva chi parla e traduce in entrambe le direzioni, ad alta voce."
    TranslationMode.CONVERSAZIONE -> "Come Automatico, ottimizzato per un dialogo a due."
    TranslationMode.PUSH_TO_TALK -> "Scegli tu chi parla con i due pulsanti. Nessun rilevamento automatico."
    TranslationMode.SOLO_TESTO -> "Mostra solo il testo tradotto, senza voce."
    TranslationMode.VOCE_TESTO -> "Mostra originale e traduzione, e legge la traduzione."
    TranslationMode.SOLO_ASCOLTO -> "Ascolta l'altra persona e traduce nella tua lingua, senza parlare."
}
