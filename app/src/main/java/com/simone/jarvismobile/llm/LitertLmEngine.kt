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
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
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

    // Serializes chat() calls: sendMessage is blocking and a Conversation is not
    // safe to drive from two coroutines at once.
    private val chatMutex = Mutex()

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

    override suspend fun generate(prompt: String): String? = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext null
        try {
            // A fresh conversation per call keeps generation stateless (no memory).
            e.createConversation().use { conv ->
                // sendMessage returns a Message; Message.toString() concatenates its
                // text Contents into the plain reply string (Content.Text.toString()
                // is the raw text). Blocking call — we are on Dispatchers.Default.
                conv.sendMessage(prompt).toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "llm_generate_failed ${t.javaClass.simpleName}")
            null
        }
    }

    override suspend fun chat(userText: String, systemPrompt: String): String? =
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            chatMutex.withLock {
                try {
                    // Re-seed when the system instruction changes (e.g. the user's
                    // notes were updated) so the model always sees current context.
                    if (conversation != null && seededSystemPrompt != systemPrompt) {
                        runCatching { conversation?.close() }
                        conversation = null
                    }
                    val conv = conversation ?: e.createConversation(
                        ConversationConfig(systemInstruction = Contents.of(systemPrompt)),
                    ).also {
                        conversation = it
                        seededSystemPrompt = systemPrompt
                    }
                    // Only the new user message is sent; the conversation keeps the
                    // whole history internally, so the model remembers the context.
                    conv.sendMessage(userText).toString()
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
    }

    override fun cancel() {
        // sendMessage is synchronous and not interruptible mid-call; cancellation
        // applies to the surrounding coroutine. No-op here.
    }

    private companion object {
        const val TAG = "JarvisLlm"

        /** Context window (input+output tokens) — how much conversation fits in memory. */
        const val MAX_CONTEXT_TOKENS = 4096
    }
}
