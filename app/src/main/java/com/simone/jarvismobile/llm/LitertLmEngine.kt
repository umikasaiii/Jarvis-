package com.simone.jarvismobile.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * On-device LLM backed by LiteRT-LM (Google AI Edge). Runs fully offline on a
 * `.litertlm` model file the user imports — the format AI Edge Gallery / the
 * HuggingFace LiteRT community publish, and Google's current direction now that
 * the MediaPipe LLM Inference API is in maintenance mode.
 *
 * We use the CPU backend for maximum device compatibility (the GPU/NPU backends
 * need extra native libraries and are model-dependent). Everything is offline;
 * the model file is imported by the user and never bundled.
 *
 * Not compiled in the scaffolding container (no Android SDK); built in CI.
 */
class LitertLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmEngine {

    private val _loadState = MutableStateFlow(LlmLoadState.UNLOADED)
    override val loadState = _loadState.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName = _loadedModelName.asStateFlow()

    private val _lastLoadDetail = MutableStateFlow("")
    override val lastLoadDetail = _lastLoadDetail.asStateFlow()

    @Volatile private var engine: Engine? = null

    // A single conversation reused across turns so the model REMEMBERS the chat
    // (KV cache / history). Created lazily on the first chat() after a load/reset.
    @Volatile private var conversation: Conversation? = null

    /** System instruction the live conversation was seeded with. */
    @Volatile private var seededSystemPrompt: String? = null

    /** Changes whenever the live KV-cache conversation is discarded. */
    @Volatile var conversationEpoch: Long = 0L
        private set

    // Serializes chat() calls: sendMessage is blocking and a Conversation is not
    // safe to drive from two coroutines at once.
    private val chatMutex = Mutex()

    /**
     * LiteRT-LM 0.14 exposes a native [Conversation.cancelProcess] operation.
     * Keep the precise in-flight conversation so UI/WorkManager cancellation
     * stops inference itself, not only the surrounding coroutine.
     */
    private val activeGeneration = AtomicReference<ActiveGeneration?>(null)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-llm-watchdog").apply { isDaemon = true }
    }

    override suspend fun load(modelPath: String, modelName: String): Boolean =
        withContext(Dispatchers.Default) {
            _loadState.value = LlmLoadState.LOADING
            resetConversation()
            runCatching { engine?.close() }
            engine = null
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    // Context window (sum of input+output tokens). Larger = the
                    // model can remember a much longer conversation before it must
                    // drop the oldest turns. 4096 is a good balance on ~8 GB RAM.
                    maxNumTokens = MAX_CONTEXT_TOKENS,
                    // Writable dir for compiled artifacts → faster subsequent loads.
                    cacheDir = context.cacheDir.absolutePath,
                )
                val e = Engine(config)
                // Can take several seconds; we are already off the main thread.
                e.initialize()
                engine = e
                _loadedModelName.value = modelName
                _lastLoadDetail.value = ""
                _loadState.value = LlmLoadState.LOADED
                Log.i(TAG, "llm_loaded")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "llm_load_failed ${e.javaClass.simpleName}")
                _lastLoadDetail.value = "${e.javaClass.simpleName}: ${e.message?.take(220) ?: ""}"
                _loadState.value = LlmLoadState.ERROR
                _loadedModelName.value = null
                runCatching { engine?.close() }
                engine = null
                false
            }
        }

    override fun unload() {
        resetConversation()
        runCatching { engine?.close() }
        engine = null
        _loadState.value = LlmLoadState.UNLOADED
        _loadedModelName.value = null
    }

    override suspend fun generate(
        prompt: String,
        timeoutSeconds: Long,
    ): String? = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext null
        try {
            // A fresh conversation per call keeps generation stateless (no memory).
            e.createConversation().use { conv ->
                // sendMessage returns a Message; Message.toString() concatenates its
                // text Contents into the plain reply string (Content.Text.toString()
                // is the raw text). Blocking call — we are on Dispatchers.Default.
                runGeneration(conv, prompt, timeoutSeconds)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmGenerationTimeoutException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "llm_generate_failed ${t.javaClass.simpleName}")
            null
        }
    }

    override suspend fun chat(
        userText: String,
        systemPrompt: String,
        timeoutSeconds: Long,
    ): String? =
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            chatMutex.withLock {
                try {
                    // The conversation is NOT re-created when [systemPrompt] differs
                    // from the seeded one. It used to be, and that quietly destroyed
                    // the chat memory: the caller rebuilds the system instruction
                    // every turn (it carries retrieved notes, which change with the
                    // question), so almost every message threw away the KV cache and
                    // the model could not even remember the previous line. The
                    // instruction is seeded once; use [resetConversation] to change it.
                    val conv = conversation ?: e.createConversation(
                        ConversationConfig(systemInstruction = Contents.of(systemPrompt)),
                    ).also {
                        conversation = it
                        seededSystemPrompt = systemPrompt
                    }
                    // Only the new user message is sent; the conversation keeps the
                    // whole history internally, so the model remembers the context.
                    runGeneration(conv, userText, timeoutSeconds)
                } catch (e: CancellationException) {
                    discardConversation(conversation)
                    throw e
                } catch (e: LlmGenerationTimeoutException) {
                    discardConversation(conversation)
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "llm_chat_failed ${t.javaClass.simpleName}")
                    null
                }
            }
        }

    override fun resetConversation() {
        runCatching { conversation?.close() }
        conversation = null
        seededSystemPrompt = null
        conversationEpoch++
    }

    override fun cancel() {
        activeGeneration.get()?.request(StopReason.USER)
    }

    /**
     * Runs the synchronous native API with an independent watchdog. A coroutine
     * timeout alone cannot interrupt a JNI call; cancelProcess() can. The mutex
     * remains held until native code acknowledges the stop, preventing a second
     * request from entering the same Conversation concurrently.
     */
    private fun runGeneration(
        conv: Conversation,
        text: String,
        timeoutSeconds: Long = GENERATION_TIMEOUT_SECONDS,
    ): String {
        val active = ActiveGeneration(conv)
        check(activeGeneration.compareAndSet(null, active)) { "generation_already_running" }
        val timeout = watchdog.schedule(
            { active.request(StopReason.TIMEOUT) },
            timeoutSeconds,
            TimeUnit.SECONDS,
        )
        try {
            val answer = conv.sendMessage(text).toString()
            active.throwIfStopped()
            return answer
        } catch (t: Throwable) {
            active.throwIfStopped()
            if (t is CancellationException) throw t
            throw t
        } finally {
            timeout.cancel(false)
            activeGeneration.compareAndSet(active, null)
        }
    }

    private fun discardConversation(expected: Conversation?) {
        if (expected == null || conversation !== expected) return
        conversation = null
        seededSystemPrompt = null
        conversationEpoch++
        runCatching { expected.close() }
    }

    private enum class StopReason { USER, TIMEOUT }

    private class ActiveGeneration(private val conversation: Conversation) {
        private val reason = AtomicReference<StopReason?>(null)

        fun request(stopReason: StopReason) {
            if (reason.compareAndSet(null, stopReason)) {
                runCatching { conversation.cancelProcess() }
            }
        }

        fun throwIfStopped() {
            when (reason.get()) {
                StopReason.USER -> throw CancellationException("generation_cancelled")
                StopReason.TIMEOUT -> throw LlmGenerationTimeoutException()
                null -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "JarvisLlm"

        /** Context window (input+output tokens) — how much conversation fits in memory. */
        const val MAX_CONTEXT_TOKENS = 4096

        /** Prevents a damaged native turn from spinning forever in background. */
        const val GENERATION_TIMEOUT_SECONDS = 90L
    }
}
