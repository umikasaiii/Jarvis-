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
) : LlmEngine {

    /** "fast" / "advanced" / "classifier" — set by [LlmRouter]; only used to label diagnostics. */
    @Volatile var label: String = "llm"

    private val _loadState = MutableStateFlow(LlmLoadState.UNLOADED)
    override val loadState = _loadState.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName = _loadedModelName.asStateFlow()

    private val _lastLoadDetail = MutableStateFlow("")
    override val lastLoadDetail = _lastLoadDetail.asStateFlow()

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
        return try {
            val file = File(modelPath)
            if (!file.isFile) error("model_file_missing")
            // A native out-of-memory while initialising kills the whole process and cannot be caught:
            // refuse only the cases that cannot realistically succeed, warn on the tight ones.
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
            val advice = ModelLoadAdvisor.assess(file.length(), mi.availMem, mi.lowMemory)
            if (advice.level == LoadAdviceLevel.INSUFFICIENT) throw InsufficientMemoryException(advice.message.orEmpty())
            if (advice.level == LoadAdviceLevel.TIGHT) Log.w(TAG, "llm_load_tight_memory")
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
            )
            loadedPath = modelPath
            loadedName = modelName
            _loadedModelName.value = modelName
            _lastLoadDetail.value = advice.message.orEmpty()
            _loadState.value = LlmLoadState.LOADED
            Log.i(TAG, "llm_loaded")
            note(InferenceEventType.LOAD_OK, durationMs = System.currentTimeMillis() - started)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "llm_load_failed ${e.javaClass.simpleName}")
            _lastLoadDetail.value = if (e is InsufficientMemoryException) {
                e.message.orEmpty()
            } else {
                "${e.javaClass.simpleName}: ${e.message?.take(220) ?: ""}"
            }
            _loadState.value = LlmLoadState.ERROR
            _loadedModelName.value = null
            slot = null
            loadedPath = null
            note(InferenceEventType.LOAD_FAIL, tag = e.javaClass.simpleName)
            false
        }
    }

    private class InsufficientMemoryException(message: String) : RuntimeException(message)

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
    }

    override suspend fun generate(prompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.once(prompt, null, timeoutSeconds * 1000L, "generate") }

    override suspend fun chat(userText: String, systemPrompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.chat(userText, systemPrompt, timeoutSeconds * 1000L) }

    override suspend fun chatStateless(userText: String, systemPrompt: String, timeoutSeconds: Long): String? =
        runOnSlot { it.once(userText, systemPrompt, timeoutSeconds * 1000L, "stateless") }

    override fun resetConversation() {
        // Safe at any time: if a generation is running the slot defers the native close until it returned.
        slot?.reset("api")
    }

    override fun cancel() {
        slot?.cancel()
    }

    private suspend fun runOnSlot(block: suspend (InferenceSlot) -> String?): String? {
        val s = slot ?: return null
        return try {
            block(s)
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
        override fun open(systemPrompt: String?): NativeConversation {
            val conv = if (systemPrompt == null) {
                engine.createConversation()
            } else {
                engine.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt)))
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
        override fun cancel() {
            conversation.cancelProcess()
        }

        override fun close() {
            conversation.close()
        }
    }

    private companion object {
        const val TAG = "JarvisLlm"

        /** Context window (input+output tokens) — how much conversation fits in memory. */
        const val MAX_CONTEXT_TOKENS = 4096

        /** How long load() waits for a cancelled generation to release the previous engine. */
        const val UNWIND_TIMEOUT_MS = 15_000L
    }
}
