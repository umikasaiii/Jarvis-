package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.engine.JarvisEngineMode
import com.simone.jarvismobile.core.engine.ReasoningMode
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.engine.MemoryEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the «Motore JARVIS» settings section. Self-contained so [SettingsViewModel] stays lean. */
@HiltViewModel
class EngineSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val memoryEngine: MemoryEngine,
) : ViewModel() {

    val engineMode: StateFlow<JarvisEngineMode> = settings.jarvisEngineMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JarvisEngineMode.CLASSICO)
    val reasoningMode: StateFlow<ReasoningMode> = settings.jarvisReasoningMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReasoningMode.AUTO)
    val memoryEnabled: StateFlow<Boolean> = settings.jarvisMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val streamingEnabled: StateFlow<Boolean> = settings.jarvisStreamingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val fastPathEnabled: StateFlow<Boolean> = settings.jarvisFastPathEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val autoContextEnabled: StateFlow<Boolean> = settings.jarvisAutoContextEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val diagnosticsVerbose: StateFlow<Boolean> = settings.jarvisEngineDiagnosticsVerbose
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val toolLoopCap: StateFlow<Int> = settings.jarvisToolLoopCap
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_JARVIS_TOOL_LOOP_CAP)
    val memoryTopN: StateFlow<Int> = settings.jarvisMemoryTopN
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_JARVIS_MEMORY_TOPN)
    val conversationalModelSlot: StateFlow<String> = settings.jarvisConversationalModelSlot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "fast")

    fun setEngineMode(value: JarvisEngineMode) = viewModelScope.launch { settings.setJarvisEngineMode(value) }
    fun setReasoningMode(value: ReasoningMode) = viewModelScope.launch { settings.setJarvisReasoningMode(value) }
    fun setMemoryEnabled(value: Boolean) = viewModelScope.launch { settings.setJarvisMemoryEnabled(value) }
    fun setStreamingEnabled(value: Boolean) = viewModelScope.launch { settings.setJarvisStreamingEnabled(value) }
    fun setFastPathEnabled(value: Boolean) = viewModelScope.launch { settings.setJarvisFastPathEnabled(value) }
    fun setAutoContextEnabled(value: Boolean) = viewModelScope.launch { settings.setJarvisAutoContextEnabled(value) }
    fun setDiagnosticsVerbose(value: Boolean) = viewModelScope.launch { settings.setJarvisEngineDiagnosticsVerbose(value) }
    fun setToolLoopCap(value: Int) = viewModelScope.launch { settings.setJarvisToolLoopCap(value) }
    fun setMemoryTopN(value: Int) = viewModelScope.launch { settings.setJarvisMemoryTopN(value) }
    fun setConversationalModelSlot(value: String) = viewModelScope.launch { settings.setJarvisConversationalModelSlot(value) }

    /** Destructive: wipes the Episodic memory tier. Working/Semantic keep their own clear paths. */
    fun clearConversationalMemory() = viewModelScope.launch { memoryEngine.clearEpisodic() }
}
