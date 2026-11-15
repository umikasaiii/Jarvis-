package com.simone.jarvismobile.ui.models

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.engine.semantic.EmbeddingSemanticClassifier
import com.simone.jarvismobile.llm.EmbeddingLoadState
import com.simone.jarvismobile.llm.ImportResult
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.LocalModel
import com.simone.jarvismobile.llm.ModelManager
import com.simone.jarvismobile.llm.SemanticEmbeddingEngine
import com.simone.jarvismobile.audio.silero.SileroImportOutcome
import com.simone.jarvismobile.audio.silero.SileroModelStatus
import com.simone.jarvismobile.audio.silero.SileroVadModelManager
import com.simone.jarvismobile.core.voice.silero.SileroFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val llm: LlmEngine,
    private val router: LlmRouter,
    private val loader: com.simone.jarvismobile.llm.ModelLoader,
    private val settings: SettingsRepository,
    private val semanticEmbeddingEngine: SemanticEmbeddingEngine,
    private val semanticClassifier: EmbeddingSemanticClassifier,
    private val sileroManager: SileroVadModelManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val loadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName
    val advancedLoadState: StateFlow<LlmLoadState> = router.advancedLoadState
    val advancedModelName: StateFlow<String?> = router.advancedModelName
    val classifierLoadState: StateFlow<LlmLoadState> = router.classifierLoadState
    val classifierModelName: StateFlow<String?> = router.classifierModelName

    /** § FASE 2A.11 — EmbeddingGemma (Semantic Understanding Layer), a DIFFERENT model from the three above. */
    val semanticClassifierLoadState: StateFlow<EmbeddingLoadState> = semanticEmbeddingEngine.loadState
    val semanticClassifierModelName: StateFlow<String?> = semanticEmbeddingEngine.loadedModelName

    // ---- memory-aware loading: the estimate informs, the user decides (see ModelLoadPolicy) ----------------------
    data class HeavyPrompt(val slot: com.simone.jarvismobile.core.llm.SlotId, val model: LocalModel, val message: String)

    private val _heavyPrompt = MutableStateFlow<HeavyPrompt?>(null)
    /** Set when the default (cautious) mode meets an insufficient estimate: «Carica comunque» / «Annulla». */
    val heavyPrompt: StateFlow<HeavyPrompt?> = _heavyPrompt.asStateFlow()
    val experimentalHeavy = settings.experimentalHeavyModels
    fun setExperimentalHeavy(on: Boolean) { viewModelScope.launch { settings.setExperimentalHeavyModels(on) } }
    val loadReports = loader.reports
    val parkedSlots = loader.parked
    val autoLoadNote = loader.autoLoadNote
    val interruptedLoads = loader.interrupted
    fun dismissInterrupted(path: String) = loader.dismissInterrupted(path)
    private val _memory = MutableStateFlow(loader.memory())
    val memory: StateFlow<com.simone.jarvismobile.llm.MemorySnapshot> = _memory.asStateFlow()
    fun refreshMemory() { _memory.value = loader.memory() }
    fun dismissHeavy() { _heavyPrompt.value = null; _status.value = "Caricamento annullato: nessun modello è stato modificato." }
    fun confirmHeavy() {
        val p = _heavyPrompt.value ?: return
        _heavyPrompt.value = null
        attempt(p.slot, p.model, confirmed = true)
    }

    /** One attempt in one slot, through the single serialised loader. Never deletes or replaces anything on failure. */
    private fun attempt(slot: com.simone.jarvismobile.core.llm.SlotId, model: LocalModel, confirmed: Boolean) {
        if (_busy.value) return
        _busy.value = true
        _status.value = when (slot) {
            com.simone.jarvismobile.core.llm.SlotId.FAST -> "Caricamento del modello in memoria… (un modello pesante può richiedere minuti e l'interfaccia resta utilizzabile)"
            com.simone.jarvismobile.core.llm.SlotId.ADVANCED -> "Caricamento del modello avanzato… (può richiedere più tempo)"
            com.simone.jarvismobile.core.llm.SlotId.CLASSIFIER -> "Caricamento del modello classificatore…"
        }
        viewModelScope.launch {
            val r = loader.load(slot, model.path, model.name, confirmed)
            _status.value = when (r) {
                is com.simone.jarvismobile.llm.ModelLoader.Result.NeedsConfirmation -> {
                    _heavyPrompt.value = HeavyPrompt(slot, model, r.message)
                    "Serve una conferma per caricare ${model.name}."
                }
                is com.simone.jarvismobile.llm.ModelLoader.Result.Loaded -> buildString {
                    append(when (slot) {
                        com.simone.jarvismobile.core.llm.SlotId.FAST -> "Modello caricato: ${model.name}"
                        com.simone.jarvismobile.core.llm.SlotId.ADVANCED -> "Modello avanzato pronto: ${model.name}. Verrà usato per le domande che richiedono ragionamento."
                        com.simone.jarvismobile.core.llm.SlotId.CLASSIFIER -> "Modello classificatore pronto: ${model.name}. Verrà usato solo per capire quale comando intendi, mai per rispondere."
                    })
                    if (r.parked.isNotEmpty()) append(". Per fare spazio ho messo in pausa: ${r.parked.joinToString { it.name.lowercase() }} (resta selezionato, non è stato eliminato)")
                    r.warning?.let { append(". Avviso: $it") }
                }
                is com.simone.jarvismobile.llm.ModelLoader.Result.Failed ->
                    "Caricamento fallito. Errore del runtime: ${r.detail}. Il modello è ancora installato: puoi riprovare o scegliere un modello più piccolo."
            }
            refreshMemory()
            _busy.value = false
        }
    }

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Live Voice Phase 0.8 — Silero VAD (user-imported, SHA-pinned; never bundled). */
    val sileroStatus: StateFlow<SileroModelStatus> = sileroManager.status

    init {
        refresh()
        viewModelScope.launch { sileroManager.refresh() }
    }

    fun importSilero(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Verifica del modello Silero VAD in corso…"
        viewModelScope.launch {
            val stream = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            _status.value = if (stream == null) {
                sileroFailureMessage(SileroFailure.UNREADABLE_FILE)
            } else when (val r = sileroManager.import(stream)) {
                is SileroImportOutcome.Ok -> "Silero VAD importato e verificato (SHA-256, dimensione, grafo). Funziona offline."
                is SileroImportOutcome.Rejected -> sileroFailureMessage(r.failure)
            }
            _busy.value = false
        }
    }

    fun removeSilero() {
        viewModelScope.launch {
            _status.value = if (sileroManager.remove()) "Silero VAD rimosso" else "Rimozione di Silero VAD non riuscita"
        }
    }

    private fun sileroFailureMessage(f: SileroFailure): String = when (f) {
        SileroFailure.SHA_MISMATCH -> "File non riconosciuto: l'SHA-256 non coincide con il modello Silero VAD ufficiale atteso. Il modello precedente (se presente) è invariato."
        SileroFailure.SIZE_MISMATCH -> "File non riconosciuto: dimensione diversa dal modello Silero VAD atteso. Il modello precedente (se presente) è invariato."
        SileroFailure.GRAPH_MISMATCH -> "Modello incompatibile: la struttura del grafo non corrisponde. Il modello precedente (se presente) è invariato."
        SileroFailure.ORT_LOAD_FAILED -> "Modello incompatibile: ONNX Runtime non riesce a caricarlo. Il modello precedente (se presente) è invariato."
        else -> "Importazione di Silero VAD non riuscita ($f). Il modello precedente (se presente) è invariato."
    }

    fun refresh() {
        _models.value = modelManager.listModels()
    }

    fun importModel(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Importazione in corso… (i modelli sono grandi, può richiedere qualche minuto)"
        viewModelScope.launch {
            val inUse = buildSet {
                if (llm.loadState.value != LlmLoadState.UNLOADED) settings.modelPath.first().takeIf { it.isNotBlank() }?.let { add(it) }
                if (router.advancedLoadState.value != LlmLoadState.UNLOADED) settings.advancedModelPath.first().takeIf { it.isNotBlank() }?.let { add(it) }
                if (router.classifierLoadState.value != LlmLoadState.UNLOADED) settings.classifierModelPath.first().takeIf { it.isNotBlank() }?.let { add(it) }
            }
            _status.value = when (val r = modelManager.importModel(uri, inUse)) {
                is ImportResult.Rejected -> r.message
                is ImportResult.Ok -> "Importato: ${r.model.name} (${r.model.sizeBytes / (1024 * 1024)} MiB, completo)"
                is ImportResult.Incomplete ->
                    "Copia incompleta: attesi ${r.expectedBytes / (1024 * 1024)} MiB, " +
                        "copiati ${r.copiedBytes / (1024 * 1024)} MiB. File rimosso. " +
                        "Riprova, o copia prima il file in Download con un altro file manager."
                is ImportResult.Failed -> "Importazione non riuscita (${r.reason})"
            }
            refresh()
            _busy.value = false
        }
    }

    fun load(model: LocalModel) = attempt(com.simone.jarvismobile.core.llm.SlotId.FAST, model, confirmed = false)

    /** Assigns a model to the optional "advanced" slot used for reasoning. */
    fun loadAdvanced(model: LocalModel) {
        if (_busy.value) return
        viewModelScope.launch {
            if (model.path == settings.modelPath.first()) {
                router.advanced.unload()
                settings.clearAdvancedModel()
                _status.value = "È già il modello rapido: JARVIS userà una sola copia " +
                    "anche per le richieste complesse, risparmiando RAM e tempo."
                return@launch
            }
            attempt(com.simone.jarvismobile.core.llm.SlotId.ADVANCED, model, confirmed = false)
        }
    }

    fun unloadAdvanced() {
        router.advanced.unload()
        viewModelScope.launch { settings.clearAdvancedModel() }
        _status.value = "Modello avanzato scaricato"
    }

    /**
     * Assigns a model to the optional "classificatore" slot — used ONLY to
     * decide which comando/tool an utterance means when the deterministic
     * aliases don't recognise it (spec: "quando JARVIS non rileva subito
     * alias rapidi"), never for an actual conversational answer. Isolated
     * from the fast slot so a tiny model dedicated purely to classification
     * doesn't also become the model that answers ordinary chat.
     */
    fun loadClassifier(model: LocalModel) {
        if (_busy.value) return
        viewModelScope.launch {
            if (model.path == settings.modelPath.first()) {
                router.classifier.unload()
                settings.clearClassifierModel()
                _status.value = "È già il modello rapido: JARVIS lo userà anche per " +
                    "la classificazione, senza caricarlo due volte."
                return@launch
            }
            attempt(com.simone.jarvismobile.core.llm.SlotId.CLASSIFIER, model, confirmed = false)
        }
    }

    fun unloadClassifier() {
        router.classifier.unload()
        viewModelScope.launch { settings.clearClassifierModel() }
        _status.value = "Modello classificatore scaricato"
    }

    /**
     * § FASE 2A.11 — assigns the two files EmbeddingGemma needs
     * (`.tflite` + SentencePiece tokenizer) to the Semantic Understanding
     * Layer's dedicated slot. Never used for chat/answers — only for turning
     * text into an embedding for the semantic classifier.
     */
    fun loadSemanticClassifier(tfliteModel: LocalModel, tokenizerModel: LocalModel) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Caricamento del modello di comprensione semantica…"
        viewModelScope.launch {
            val ok = semanticEmbeddingEngine.load(tfliteModel.path, tokenizerModel.path, tfliteModel.name)
            if (ok) {
                settings.setSemanticClassifierModel(tfliteModel.path, tfliteModel.name, tokenizerModel.path, tokenizerModel.name)
                semanticClassifier.invalidate()
                _status.value = "Comprensione semantica pronta: ${tfliteModel.name}. Verrà usata per capire " +
                    "il significato delle richieste, mai per rispondere direttamente."
            } else {
                _status.value = "Caricamento della comprensione semantica fallito. Dettaglio: " +
                    semanticEmbeddingEngine.lastLoadDetail.value.ifBlank { "errore sconosciuto" }
            }
            _busy.value = false
        }
    }

    fun unloadSemanticClassifier() {
        semanticEmbeddingEngine.unload()
        semanticClassifier.invalidate()
        viewModelScope.launch { settings.clearSemanticClassifierModel() }
        _status.value = "Comprensione semantica scaricata — le richieste passano al ciclo di ragionamento completo"
    }

    fun unload() {
        llm.unload()
        viewModelScope.launch { settings.clearActiveModel() }
        _status.value = "Modello scaricato dalla memoria"
    }

    fun delete(model: LocalModel) {
        viewModelScope.launch {
            // BETA 2/8: never delete a file an engine still has mapped — release that slot first, so the
            // chat reports "model missing" cleanly instead of failing on a dangling path.
            if (model.path == settings.modelPath.first()) {
                llm.unload(); settings.clearActiveModel()
            }
            if (model.path == settings.advancedModelPath.first()) {
                router.advanced.unload(); settings.clearAdvancedModel()
            }
            if (model.path == settings.classifierModelPath.first()) {
                router.classifier.unload(); settings.clearClassifierModel()
            }
            modelManager.deleteModel(model.path)
            refresh()
        }
    }
}
