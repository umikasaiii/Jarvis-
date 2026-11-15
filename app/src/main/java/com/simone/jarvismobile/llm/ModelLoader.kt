package com.simone.jarvismobile.llm

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.llm.LoadAdvice
import com.simone.jarvismobile.core.llm.LoadDecision
import com.simone.jarvismobile.core.llm.LoadReport
import com.simone.jarvismobile.core.llm.ModelLoadAdvisor
import com.simone.jarvismobile.core.llm.ModelLoadPolicy
import com.simone.jarvismobile.core.llm.ModelResidencyPolicy
import com.simone.jarvismobile.core.llm.ResidentModel
import com.simone.jarvismobile.core.llm.SlotId
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE place that decides how and when a local model is loaded (manual loads from the Models screen AND the start-up loads).
 * It exists to separate three things the old code mixed:
 *  - ADVICE (a memory estimate: informs, never forbids — [ModelLoadPolicy]);
 *  - SEQUENCING (one load at a time, the previous model of the slot released and awaited first, and — only when the estimate is
 *    insufficient — other resident slots parked, one at a time, re-measuring memory after each);
 *  - the ATTEMPT itself (the engine really calls `Engine.initialize()`; if the runtime refuses, the real error is reported).
 * File validity and format are still checked by the engine/runtime and are never relaxed here. Nothing here deletes anything.
 */
@Singleton
class ModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: LlmRouter,
    private val settings: SettingsRepository,
    private val guard: ModelLoadGuard,
) {
    sealed interface Result {
        data class Loaded(val warning: String?, val parked: List<SlotId>) : Result
        /** Default mode and an insufficient estimate: nothing was attempted; ask the user ("Carica comunque"). */
        data class NeedsConfirmation(val message: String) : Result
        data class Failed(val detail: String, val parked: List<SlotId>) : Result
    }

    private val gate = Mutex() // never two loads at once, whoever asks
    private val _parked = MutableStateFlow<Set<SlotId>>(emptySet())
    /** Slots whose model stays SELECTED in the settings but was released from memory to make room (not deleted). */
    val parked: StateFlow<Set<SlotId>> = _parked.asStateFlow()

    private val _reports = MutableStateFlow<Map<SlotId, LoadReport>>(emptyMap())
    val reports: StateFlow<Map<SlotId, LoadReport>> = _reports.asStateFlow()

    private val _autoLoadNote = MutableStateFlow<String?>(null)
    /** Why the last start-up load did not happen (interrupted before, or an insufficient estimate in the cautious mode). */
    val autoLoadNote: StateFlow<String?> = _autoLoadNote.asStateFlow()

    val interrupted get() = guard.interrupted

    fun engineOf(slot: SlotId): LitertLmEngine = when (slot) {
        SlotId.FAST -> router.fast
        SlotId.ADVANCED -> router.advanced
        SlotId.CLASSIFIER -> router.classifier
    }

    fun memory(): MemorySnapshot = DeviceMemory.read(context)

    fun advise(path: String): LoadAdvice {
        val m = memory()
        return ModelLoadAdvisor.assess(runCatching { File(path).length() }.getOrDefault(0L), m.availBytes, m.lowMemory)
    }

    /** User-initiated load. [confirmed] = the user pressed "Carica comunque" for this attempt. */
    suspend fun load(slot: SlotId, path: String, name: String, confirmed: Boolean = false): Result = gate.withLock {
        guard.startup()
        val experimental = settings.experimentalHeavyModels.first()
        var advice = advise(path)
        val decision = ModelLoadPolicy.decide(advice, experimental, confirmed)
        if (decision is LoadDecision.NeedsConfirmation) return@withLock Result.NeedsConfirmation(decision.message)
        val warning = (decision as LoadDecision.Proceed).warning

        // Make room only when the estimate says it is needed, one slot at a time, waiting for the native close and re-measuring.
        val parkedNow = ArrayList<SlotId>()
        while (true) {
            val next = ModelResidencyPolicy.nextToPark(slot, advice.level, residentOthers(slot)) ?: break
            Log.i(TAG, "model_park slot=$next for=$slot")
            engineOf(next).unloadAndAwait()
            _parked.value = _parked.value + next
            parkedNow += next
            advice = advise(path)
        }

        val engine = engineOf(slot)
        val ok = engine.load(path, name) // the real Engine.initialize(); the runtime's own error comes back in lastLoadDetail
        engine.lastLoadReport.value?.let { r -> _reports.value = _reports.value + (slot to r.copy(parked = parkedNow.map { it.name.lowercase() })) }
        if (ok) {
            when (slot) {
                SlotId.FAST -> settings.setActiveModel(path, name)
                SlotId.ADVANCED -> settings.setAdvancedModel(path, name)
                SlotId.CLASSIFIER -> settings.setClassifierModel(path, name)
            }
            _parked.value = _parked.value - slot
            guard.refresh()
            Result.Loaded(warning, parkedNow)
        } else {
            Result.Failed(engine.lastLoadDetail.value.ifBlank { "errore sconosciuto" }, parkedNow)
        }
    }

    /**
     * Start-up load of the persisted model of [slot]. Stricter than a manual attempt, because nobody is looking at the screen:
     * never a model whose previous attempt was interrupted (no crash loop), and an insufficient estimate only in experimental
     * mode. Returns true if the model is (now) loaded. Does not touch the settings: the selection stays as the user made it.
     */
    suspend fun autoLoad(slot: SlotId, path: String, name: String): Boolean = gate.withLock {
        guard.startup()
        val engine = engineOf(slot)
        if (engine.loadState.value == LlmLoadState.LOADED) return@withLock true
        val label = name.ifBlank { File(path).name }
        if (!guard.guard.autoLoadAllowed(path)) {
            _autoLoadNote.value = "Il caricamento precedente di «$label» è stato interrotto (probabilmente per memoria insufficiente). " +
                "Non lo ricarico da solo: scegli un modello più piccolo oppure riprova a mano dalla schermata Modelli."
            _parked.value = _parked.value + slot
            return@withLock false
        }
        val advice = advise(path)
        val experimental = settings.experimentalHeavyModels.first()
        if (!ModelLoadPolicy.autoLoadAllowed(advice, experimental, interruptedBefore = false)) {
            _autoLoadNote.value = "«$label» non è stato caricato all'avvio: la stima di memoria è insufficiente (${advice.message?.substringBefore(" È un") ?: ""}). " +
                "Caricalo a mano dalla schermata Modelli (puoi scegliere «Carica comunque») oppure abilita il caricamento sperimentale."
            _parked.value = _parked.value + slot
            return@withLock false
        }
        val ok = engine.load(path, name)
        engine.lastLoadReport.value?.let { r -> _reports.value = _reports.value + (slot to r) }
        if (ok) { _parked.value = _parked.value - slot; _autoLoadNote.value = null }
        ok
    }

    /** Asks for nothing and removes nothing: just forgets the "interrupted" notice so the model may auto-load again. */
    fun dismissInterrupted(path: String) { guard.dismiss(path); _autoLoadNote.value = null }

    private suspend fun residentOthers(requested: SlotId): List<ResidentModel> = buildList {
        for (s in SlotId.entries) {
            if (s == requested || engineOf(s).loadState.value != LlmLoadState.LOADED) continue
            val p = when (s) {
                SlotId.FAST -> settings.modelPath.first()
                SlotId.ADVANCED -> settings.advancedModelPath.first()
                SlotId.CLASSIFIER -> settings.classifierModelPath.first()
            }
            add(ResidentModel(s, runCatching { File(p).length() }.getOrDefault(0L)))
        }
    }

    private companion object { const val TAG = "JarvisModelLoader" }
}
