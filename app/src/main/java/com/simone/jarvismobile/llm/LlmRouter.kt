package com.simone.jarvismobile.llm

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which brain answered, for diagnostics and the UI. */
enum class ModelSlot { FAST, ADVANCED }

/**
 * Routes work between a small, quick model and an optional larger one.
 *
 * Rationale: a 4B model gives real reasoning but is slow for every "che ore
 * sono". Keeping a fast model for commands and short replies, and paying the
 * big model's latency only when the question actually needs thinking, is what
 * makes the assistant feel responsive without giving up depth.
 *
 * If no advanced model is configured, everything runs on the fast one, so the
 * app behaves exactly as before.
 */
@Singleton
class LlmRouter @Inject constructor(
    val fast: LitertLmEngine,
    val advanced: LitertLmEngine,
) {
    /** The fast engine backs the UI's load indicator — it is the always-on brain. */
    val loadState: StateFlow<LlmLoadState> get() = fast.loadState
    val loadedModelName: StateFlow<String?> get() = fast.loadedModelName
    val lastLoadDetail: StateFlow<String> get() = fast.lastLoadDetail

    val advancedLoadState: StateFlow<LlmLoadState> get() = advanced.loadState
    val advancedModelName: StateFlow<String?> get() = advanced.loadedModelName

    /** True when a second, larger model is loaded and can take hard questions. */
    val hasAdvanced: Boolean get() = advanced.loadState.value == LlmLoadState.LOADED

    fun engineFor(slot: ModelSlot): LitertLmEngine = when (slot) {
        ModelSlot.FAST -> fast
        ModelSlot.ADVANCED -> if (hasAdvanced) advanced else fast
    }

    /**
     * Answers [userText]. [needsReasoning] routes to the larger model when one is
     * loaded; simple exchanges stay on the fast one.
     */
    suspend fun chat(userText: String, systemPrompt: String, needsReasoning: Boolean): String? {
        val slot = if (needsReasoning && hasAdvanced) ModelSlot.ADVANCED else ModelSlot.FAST
        Log.i(TAG, "chat_slot=$slot reasoning=$needsReasoning")
        return engineFor(slot).chat(userText, systemPrompt)
    }

    /** Clears the running conversation on both brains. */
    fun resetConversation() {
        fast.resetConversation()
        advanced.resetConversation()
    }

    fun cancel() {
        fast.cancel()
        advanced.cancel()
    }

    private companion object {
        const val TAG = "JarvisRouter"
    }
}
