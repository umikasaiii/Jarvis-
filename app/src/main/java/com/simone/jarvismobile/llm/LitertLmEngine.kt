package com.simone.jarvismobile.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.simone.jarvismobile.core.llm.ChatSeed
import com.simone.jarvismobile.core.llm.GenerationTimedOut
import com.simone.jarvismobile.core.llm.InferenceEvent
import com.simone.jarvismobile.core.llm.InferenceEventLog
import com.simone.jarvismobile.core.llm.InferenceEventType
import com.simone.jarvismobile.core.llm.InferenceScheduler
import com.simone.jarvismobile.core.llm.InferenceSlot
import com.simone.jarvismobile.core.llm.LoadAdviceLevel
import com.simone.jarvismobile.core.llm.ModelLoadAdvisor
import com.simone.jarvismobile.core.llm.NativeConversation
import com.simone.jarvismobile.core.llm.NativeEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * On-device LLM backed by LiteRT-LM (Google AI Edge). Runs fully offline on a `.litertlm` model file the user
 * imports. CPU backend for maximum compatibility; nothing is bundled.
 *
 * HOTFIX 3 — this class is now only the Android adapter. Everything about WHEN native resources may be used,
 * closed, cancelled or abandoned lives in the JVM-tested [InferenceSlot] (`:core`): one lane per engine, a
 * deferred (never concurrent) reset/close, caller cancellation wired to `Conversation.cancelProcess()`,
 * discarded conversations after any stop/failure/degenerate output, and wedge detection. What remains here:
 * loading the model file, mapping LiteRT types onto the slot's tiny native contract, and recovery by
 * replacing a wedged engine.
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
class LitertLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: InferenceDiagnostics,
    private val loadGuard: ModelLoadGuard,
    private val settings: com.simone.jarvismobile.data.SettingsRepository,
    private val profiles: ModelProfileStore,
) : LlmEngine {

    /** The shared, content-free event trail (also used by the turn ledger). */
    val eventLog: InferenceEventLog get() = diagnostics.log

    /** "fast" / "advanced" / "classifier" — set by [LlmRouter]; only used to label diagnostics. */
    @Volatile var label: String = "llm"

    private val _loadState = MutableStateFlow(LlmLoadState.UNLOADED)
    override val loadState = _loadState.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName = _loadedModelName.asStateFlow()

    private val _lastLoadDetail = MutableStateFlow("")
    override val lastLoadDetail = _lastLoadDetail.asStateFlow()

    private val _lastLoadReport = MutableStateFlow<com.simone.jarvismobile.core.llm.LoadReport?>(null)
    /** What the last load attempt of THIS engine looked like (memory before/after, estimate, backend, time, outcome, real error). */
    val lastLoadReport = _lastLoadReport.asStateFlow()

    private val _generating = MutableStateFlow(false)
    override val generating = _generating.asStateFlow()

    @Volatile private var slot: InferenceSlot? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedName: String? = null

    /** Epoch contributed by slots that were already replaced, so [conversationEpoch] never goes backwards. */
    @Volatile private var epochBase: Long = 0L

    /** Changes whenever the live KV-cache conversation is discarded (reset, stop, timeout, reload, recovery). */
    val conversationEpoch: Long get() = epochBase + (slot?.epoch ?: 0L)

    // Only load/unload/recovery are serialised here; generation is serialised inside the slot.
    private val loadMutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-llm-watchdog").apply { isDaemon = true }
    }
    private val scheduler = InferenceScheduler { delayMs, task ->
        val future = watchdog.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        val cancelTask: () -> Unit = { future.cancel(false) }
        cancelTask
    }

    private fun note(type: InferenceEventType, durationMs: Long = -1, tag: String = "") {
        diagnostics.log.record(
            InferenceEvent(System.currentTimeMillis(), type, label, conversationEpoch, durationMs, -1, -1, InferenceEventLog.sanitiseTag(tag)),
        )
    }

    override suspend fun load(modelPath: String, modelName: String): Boolean =
        withContext(Dispatchers.Default) {
            loadMutex.withLock { loadLocked(modelPath, modelName, force = false) }
        }

    /** Body of [load]; runs with [loadMutex] held. */
    private suspend fun loadLocked(modelPath: String, modelName: String, force: Boolean): Boolean {
        // Already serving exactly this file: a second caller must not reload it (double RAM, lost chat).
        if (!force && _loadState.value == LlmLoadState.LOADED && loadedPath == modelPath && slot != null) {
            _loadedModelName.value = modelName
            return true
        }
        val started = System.currentTimeMillis()
        profiles.syncInterrupted() // loads the PREVIOUS process did not survive are recorded on their profiles (idempotent)
        note(InferenceEventType.LOAD_START)
        _loadState.value = LlmLoadState.LOADING
        // Retire the old engine WITHOUT closing it under a running generation: the slot closes it itself once
        // native code has returned. Wait (bounded) so two multi-hundred-MB engines never coexist for long;
        // a wedged slot is allowed to linger (it will be closed if/when native code ever returns).
        val old = slot
        if (old != null) {
            epochBase += old.epoch + 1
            slot = null
            old.shutdown()
            if (!old.awaitClosed(UNWIND_TIMEOUT_MS) && !old.wedged) {
                slot = old // keep it reachable; the caller can retry
                _lastLoadDetail.value = "il motore sta ancora terminando una risposta: riprova tra qualche secondo"
                _loadState.value = if (_loadedModelName.value != null) LlmLoadState.LOADED else LlmLoadState.ERROR
                note(InferenceEventType.LOAD_FAIL, tag = "busy")
                return false
            }
        }
        loadedPath = null
        var before = MemorySnapshot(-1, -1, false, -1)
        var advice = com.simone.jarvismobile.core.llm.LoadAdvice(LoadAdviceLevel.OK, null)
        var guarded = false
        val experimental = runCatching { settings.experimentalHeavyModels.first() }.getOrDefault(false)
        fun report(ok: Boolean, error: String?, sizeBytes: Long) {
            val after = DeviceMemory.read(context)
            _lastLoadReport.value = com.simone.jarvismobile.core.llm.LoadReport(
                slot = label, modelName = modelName, backend = "CPU", fileBytes = sizeBytes,
                estimateBytes = (sizeBytes * ModelLoadAdvisor.OVERHEAD_FACTOR).toLong(),
                totalRamBytes = before.totalBytes, availBeforeBytes = before.availBytes, lowMemoryBefore = before.lowMemory,
                availAfterBytes = after.availBytes, processPssBeforeBytes = before.pssBytes, processPssAfterBytes = after.pssBytes,
                adviceLevel = advice.level.name, durationMs = System.currentTimeMillis() - started,
                outcome = if (ok) com.simone.jarvismobile.core.llm.LoadReport.Outcome.LOADED else com.simone.jarvismobile.core.llm.LoadReport.Outcome.FAILED,
                error = error, experimentalMode = experimental,
            )
            // M3: the profile registry and the content-free trace learn what REALLY happened (no path, no text)
            val ms = System.currentTimeMillis() - started
            profiles.noteLoad(modelPath, label, ok, error, ms, MAX_CONTEXT_TOKENS, before.pssBytes, after.pssBytes, before.totalBytes)
            com.simone.jarvismobile.net.OfflineGate.trace.llm(
                "-", label, "load", if (ok) "ok" else loadFailureCategory(error), ms,
                mapOf("pssBeforeMb" to maxOf(0L, before.pssBytes) / (1024 * 1024), "pssAfterMb" to maxOf(0L, after.pssBytes) / (1024 * 1024), "totalMb" to maxOf(0L, before.totalBytes) / (1024 * 1024), "configuredWindow" to MAX_CONTEXT_TOKENS.toLong()),
            )
        }
        return try {
            val file = File(modelPath)
            if (!file.isFile) error("model_file_missing")
            // The memory estimate is ADVISORY ONLY: it never stops an attempt (the caller — ModelLoader, after the user's
            // confirmation or the experimental preference — decides). Native out-of-memory during initialisation can end the whole
            // process and cannot be caught, so a durable marker is written first: after a kill the next start knows the load was
            // interrupted and does not repeat it by itself.
            before = DeviceMemory.read(context)
            advice = ModelLoadAdvisor.assess(file.length(), before.availBytes, before.lowMemory)
            if (advice.level != LoadAdviceLevel.OK) Log.w(TAG, "llm_load_advice level=${advice.level}")
            loadGuard.guard.begin(com.simone.jarvismobile.core.llm.LoadGuard.Attempt(label, modelPath, modelName, file.length(), started, advice.level.name))
            guarded = true
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                // Context window (sum of input+output tokens). 4096 is a good balance on ~8 GB RAM.
                maxNumTokens = MAX_CONTEXT_TOKENS,
                // Writable dir for compiled artifacts → faster subsequent loads.
                cacheDir = context.cacheDir.absolutePath,
            )
            val e = Engine(config)
            // Can take several seconds; we are already off the main thread.
            e.initialize()
            slot = InferenceSlot(
                label = label,
                engine = LiteRtNativeEngine(e),
                scheduler = scheduler,
                log = diagnostics.log,
                onGenerating = { _generating.value = it },
                onWedged = { wedgedSlot -> engineScope.launch { recoverFromWedge(wedgedSlot) } },
                configuredWindow = MAX_CONTEXT_TOKENS,
            )
            loadedPath = modelPath
            loadedName = modelName
            _loadedModelName.value = modelName
            _lastLoadDetail.value = advice.message.orEmpty()
            _loadState.value = LlmLoadState.LOADED
            if (guarded) loadGuard.guard.finish(modelPath, success = true)
            report(true, null, file.length())
            Log.i(TAG, "llm_loaded")
            note(InferenceEventType.LOAD_OK, durationMs = System.currentTimeMillis() - started)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "llm_load_failed ${e.javaClass.simpleName}")
            // the REAL runtime error, as the runtime reported it (not a pre-computed verdict)
            val detail = "${e.javaClass.simpleName}: ${e.message?.take(300) ?: ""}"
            _lastLoadDetail.value = detail
            if (guarded) loadGuard.guard.finish(modelPath, success = false) // the process survived: not an interrupted load
            report(false, detail, runCatching { File(modelPath).length() }.getOrDefault(-1L))
            _loadState.value = LlmLoadState.ERROR
            _loadedModelName.value = null
            slot = null
            loadedPath = null
            note(InferenceEventType.LOAD_FAIL, tag = e.javaClass.simpleName)
            false
        }
    }

    /** Unloads and WAITS (bounded) until the native engine is actually closed, so its memory is free before the next load. */
    suspend fun unloadAndAwait(timeoutMs: Long = UNWIND_TIMEOUT_MS): Boolean {
        val old = slot
        unload()
        return old == null || old.wedged || old.awaitClosed(timeoutMs)
    }

    /** Native code ignored a stop request past the grace period: replace the engine instead of hanging forever. */
    private suspend fun recoverFromWedge(wedgedSlot: InferenceSlot) {
        loadMutex.withLock {
            if (slot !== wedgedSlot) return@withLock
            val path = loadedPath ?: return@withLock
            val name = loadedName ?: return@withLock
            Log.w(TAG, "llm_wedged_recovering")
            loadLocked(path, name, force = true)
        }
    }

    override fun unload() {
        // The UI sees "unloaded" at once; the slot closes the native engine as soon as nothing is using it.
        _loadState.value = LlmLoadState.UNLOADED
        _loadedModelName.value = null
        val old = slot
        if (old != null) epochBase += old.epoch + 1
        slot = null
        loadedPath = null
        old?.shutdown()
        note(InferenceEventType.UNLOAD)
        com.simone.jarvismobile.net.OfflineGate.trace.llm("-", label, "unload", "ok")
    }

    override suspend fun generate(prompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.once(prompt, null, timeoutSeconds * 1000L, "generate") }

    override suspend fun chat(userText: String, systemPrompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.chat(userText, systemPrompt, timeoutSeconds * 1000L) }

    override suspend fun chatStateless(userText: String, systemPrompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.once(userText, systemPrompt, timeoutSeconds * 1000L, "stateless") }

    override suspend fun chatSeeded(userText: String, systemPrompt: String, seed: ChatSeed, timeoutSeconds: Long): String? =
        runOnSlot { it.chat(userText, systemPrompt, timeoutSeconds * 1000L, seed) }

    override fun resetConversation() {
        com.simone.jarvismobile.net.OfflineGate.trace.llm("-", label, "reset", "ok")
        // Safe at any time: if a generation is running the slot defers the native close until it returned.
        slot?.reset("api")
    }

    override fun cancel() {
        com.simone.jarvismobile.net.OfflineGate.trace.llm("-", label, "cancel", "cancelled")
        slot?.cancel()
    }

    private suspend fun runOnSlot(block: suspend (InferenceSlot) -> String?): String? {
        val s = slot ?: return null
        val t0 = System.currentTimeMillis()
        return try {
            block(s).also { answer -> profiles.noteInference(loadedPath, answer != null, System.currentTimeMillis() - t0) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationTimedOut) {
            throw LlmGenerationTimeoutException()
        } catch (t: Throwable) {
            Log.w(TAG, "llm_call_failed ${t.javaClass.simpleName}")
            null
        }
    }

    /** Maps the LiteRT-LM classes onto the tiny contract [InferenceSlot] works with. */
    private class LiteRtNativeEngine(private val engine: Engine) : NativeEngine {
        override fun open(systemPrompt: String?, seed: ChatSeed): NativeConversation {
            val history = seed.normalized()
            val conv = if (systemPrompt == null) {
                engine.createConversation()
            } else {
                // Documented API (LiteRT-LM Kotlin v0.14.0): ConversationConfig(systemInstruction, initialMessages,
                // samplerConfig). Earlier turns are real user/model messages; sampling is applied only when given.
                val sampler = seed.sampling?.let { SamplerConfig(topK = it.topK, topP = it.topP, temperature = it.temperature) }
                val initial = history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) }
                when {
                    sampler != null && initial.isNotEmpty() ->
                        engine.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt), initialMessages = initial, samplerConfig = sampler))
                    sampler != null ->
                        engine.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt), samplerConfig = sampler))
                    initial.isNotEmpty() ->
                        engine.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt), initialMessages = initial))
                    else ->
                        engine.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt)))
                }
            }
            return LiteRtNativeConversation(conv)
        }

        override fun close() {
            runCatching { engine.close() }
        }
    }

    private class LiteRtNativeConversation(private val conversation: Conversation) : NativeConversation {
        // sendMessage returns a Message; toString() concatenates its text Contents. Blocking JNI call.
        override fun send(text: String): String = conversation.sendMessage(text).toString()

        /**
         * `Conversation.getTokenCount()` — present in the exact AAR (litertlm-android 0.14.0, javap in CI). Called by reflection so a future signature
         * change degrades to "no measurement" instead of a crash; the value's semantics are undocumented and it is never used to size a prompt.
         */
        override fun tokenCount(): Int? = runCatching { (conversation.javaClass.getMethod("getTokenCount").invoke(conversation) as? Int) }.getOrNull()
        override fun cancel() {
            conversation.cancelProcess()
        }

        override fun close() {
            conversation.close()
        }
    }

    /** Failure CATEGORY for the trace: the runtime message itself is never stored there. */
    private fun loadFailureCategory(error: String?): String = when {
        error == null -> "failed"
        error.contains("model_file_missing") -> "missing_file"
        error.contains("OutOfMemory", ignoreCase = true) -> "oom"
        error.contains("busy") -> "timeout"
        else -> "failed"
    }

    private companion object {
        const val TAG = "JarvisLlm"

        /** Context window (input+output tokens) — how much conversation fits in memory. */
        const val MAX_CONTEXT_TOKENS = 4096

        /** How long load() waits for a cancelled generation to release the previous engine. */
        const val UNWIND_TIMEOUT_MS = 15_000L
    }
}
