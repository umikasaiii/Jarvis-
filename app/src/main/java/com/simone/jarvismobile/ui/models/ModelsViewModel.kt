package com.simone.jarvismobile.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LocalModel
import com.simone.jarvismobile.llm.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val llm: LlmEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    val loadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName

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
            val imported = modelManager.importModel(uri)
            _status.value = if (imported != null) "Importato: ${imported.name}" else "Importazione non riuscita"
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
