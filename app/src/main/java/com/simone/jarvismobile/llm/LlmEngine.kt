package com.simone.jarvismobile.llm

import kotlinx.coroutines.flow.StateFlow

enum class LlmLoadState { UNLOADED, LOADING, LOADED, ERROR }

/** A native generation exceeded JARVIS's bounded per-call deadline. */
class LlmGenerationTimeoutException : RuntimeException("generation_timeout")

/**
 * Default per-call deadline for a full conversational generation. Callers that
 * only need a short, single-line completion (e.g. intent classification)
 * should pass a much smaller value to [LlmEngine.generate] instead — see
 * [com.simone.jarvismobile.tools.LlmIntentClassifier].
 */
const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 90L

/**
 * Local, on-device language model (docs/ARCHITECTURE.md §5). Phase 3 ships
 * [LitertLmEngine] (LiteRT-LM, `.litertlm` models); the interface stays swappable
 * so another backend can replace it later. Everything runs offline; the model
 * file is imported by the user (never bundled).
 */
interface LlmEngine {
    val loadState: StateFlow<LlmLoadState>
    val loadedModelName: StateFlow<String?>

    /** Technical detail of the last load attempt (real engine error; for diagnostics). */
    val lastLoadDetail: StateFlow<String>

    /**
     * True while a native call ([generate]/[chat]) is actually in flight — including
     * the window after a cancel/timeout has been REQUESTED but the native call hasn't
     * actually unwound yet (that latency is outside this app's control). Lets the UI
     * show "still finishing up" distinctly from a conversation state that already
     * reset to idle, instead of looking stuck with no explanation.
     */
    val generating: StateFlow<Boolean>

    /** Loads a model from an app-private file path. Returns true on success. */
    suspend fun load(modelPath: String, modelName: String): Boolean

    /** Frees the model and its memory. */
    fun unload()

    /**
     * Generates a full reply for [prompt] with no memory (stateless). Null on
     * failure. [timeoutSeconds] bounds the native call — shorten it for a
     * short, single-line completion so a stuck/slow model fails fast instead
     * of blocking the caller for the full conversational deadline.
     */
    suspend fun generate(prompt: String, timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS): String?

    /**
     * Multi-turn chat: sends [userText] within a conversation that persists across
     * calls, so the model remembers the earlier exchanges (KV cache). On the first
     * call after [load]/[resetConversation] the conversation is seeded with
     * [systemPrompt]; later calls IGNORE it — changing the instruction mid-chat
     * would mean rebuilding the conversation and losing everything said so far.
     * Call [resetConversation] when the instruction really must change.
     * Returns null on failure. [timeoutSeconds] bounds the native call, same as
     * [generate] — shorten it for a follow-up turn that only needs to phrase an
     * already-known result, so a stuck/slow model fails fast instead of costing
     * the full conversational deadline on every round of a multi-round turn.
     */
    suspend fun chat(userText: String, systemPrompt: String, timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS): String?

    /** Drops the multi-turn history and starts a fresh conversation next [chat]. */
    fun resetConversation()

    /** Requests a real stop of the currently running native inference. */
    fun cancel()
}
