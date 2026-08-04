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

    /** Generates a full reply for [prompt]. Returns null on failure. */
    suspend fun generate(prompt: String): String?

    fun cancel()
}
