package com.simone.jarvismobile.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.ImportResult
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.LocalModel
import com.simone.jarvismobile.llm.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val settings: SettingsRepository,
) : ViewModel() {

    val loadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName
    val advancedLoadState: StateFlow<LlmLoadState> = router.advancedLoadState
    val advancedModelName: StateFlow<String?> = router.advancedModelName
    val classifierLoadState: StateFlow<LlmLoadState> = router.classifierLoadState
    val classifierModelName: StateFlow<String?> = router.classifierModelName

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _models.value = modelManager.listModels()
    }

    fun importModel(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Importazione in corso… (i modelli sono grandi, può richiedere qualche minuto)"
        viewModelScope.launch {
            _status.value = when (val r = modelManager.importModel(uri)) {
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

    fun load(model: LocalModel) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Caricamento del modello in memoria…"
        viewModelScope.launch {
            val ok = llm.load(model.path, model.name)
            if (ok) settings.setActiveModel(model.path, model.name)
            _status.value = if (ok) {
                "Modello caricato: ${model.name}"
            } else {
                "Caricamento fallito. Dettaglio: ${llm.lastLoadDetail.value.ifBlank { "errore sconosciuto" }}"
            }
            _busy.value = false
        }
    }

    /** Assigns a model to the optional "advanced" slot used for reasoning. */
    fun loadAdvanced(model: LocalModel) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Caricamento del modello avanzato… (può richiedere più tempo)"
        viewModelScope.launch {
            if (model.path == settings.modelPath.first()) {
                router.advanced.unload()
                settings.clearAdvancedModel()
                _status.value = "È già il modello rapido: JARVIS userà una sola copia " +
                    "anche per le richieste complesse, risparmiando RAM e tempo."
                _busy.value = false
                return@launch
            }
            val ok = router.advanced.load(model.path, model.name)
            if (ok) settings.setAdvancedModel(model.path, model.name)
            _status.value = if (ok) {
                "Modello avanzato pronto: ${model.name}. Verrà usato per le domande " +
                    "che richiedono ragionamento."
            } else {
                "Caricamento avanzato fallito. Dettaglio: " +
                    router.advanced.lastLoadDetail.value.ifBlank { "errore sconosciuto" }
            }
            _busy.value = false
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
        _busy.value = true
        _status.value = "Caricamento del modello classificatore…"
        viewModelScope.launch {
            if (model.path == settings.modelPath.first()) {
                router.classifier.unload()
                settings.clearClassifierModel()
                _status.value = "È già il modello rapido: JARVIS lo userà anche per " +
                    "la classificazione, senza caricarlo due volte."
                _busy.value = false
                return@launch
            }
            val ok = router.classifier.load(model.path, model.name)
            if (ok) settings.setClassifierModel(model.path, model.name)
            _status.value = if (ok) {
                "Modello classificatore pronto: ${model.name}. Verrà usato solo per capire " +
                    "quale comando intendi quando gli alias rapidi non bastano, mai per rispondere."
            } else {
                "Caricamento del classificatore fallito. Dettaglio: " +
                    router.classifier.lastLoadDetail.value.ifBlank { "errore sconosciuto" }
            }
            _busy.value = false
        }
    }

    fun unloadClassifier() {
        router.classifier.unload()
        viewModelScope.launch { settings.clearClassifierModel() }
        _status.value = "Modello classificatore scaricato"
    }

    fun unload() {
        llm.unload()
        viewModelScope.launch { settings.clearActiveModel() }
        _status.value = "Modello scaricato dalla memoria"
    }

    fun delete(model: LocalModel) {
        modelManager.deleteModel(model.path)
        refresh()
    }
}
