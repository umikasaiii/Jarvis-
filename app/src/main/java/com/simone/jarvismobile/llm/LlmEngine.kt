package com.simone.jarvismobile.llm

import kotlinx.coroutines.flow.StateFlow

enum class LlmLoadState { UNLOADED, LOADING, LOADED, ERROR }

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

    /** Loads a model from an app-private file path. Returns true on success. */
    suspend fun load(modelPath: String, modelName: String): Boolean

    /** Frees the model and its memory. */
    fun unload()

    /** Generates a full reply for [prompt] with no memory (stateless). Null on failure. */
    suspend fun generate(prompt: String): String?

    /**
     * Multi-turn chat: sends [userText] within a conversation that persists across
     * calls, so the model remembers the earlier exchanges (KV cache). On the first
     * call after [load]/[resetConversation] the conversation is seeded with
     * [systemPrompt]. Returns null on failure.
     */
    suspend fun chat(userText: String, systemPrompt: String): String?

    /** Drops the multi-turn history and starts a fresh conversation next [chat]. */
    fun resetConversation()

    fun cancel()
}
