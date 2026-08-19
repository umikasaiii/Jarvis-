package com.simone.jarvismobile.llm

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which brain answered, for diagnostics and the UI. */
enum class ModelSlot { FAST, ADVANCED }

/**
 * What [com.simone.jarvismobile.tools.LlmIntentClassifier] actually needs from
 * [LlmRouter]: just the one engine to classify with. Kept as its own interface
 * (implemented by [LlmRouter] below) so the classifier can be unit-tested with
 * a plain fake instead of a real, Android-`Context`-dependent [LlmRouter].
 */
fun interface ClassifierEngineProvider {
    fun classifierEngine(): LlmEngine
}

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
    val classifier: LitertLmEngine,
) : ClassifierEngineProvider {
    /** The fast engine backs the UI's load indicator — it is the always-on brain. */
    val loadState: StateFlow<LlmLoadState> get() = fast.loadState
    val loadedModelName: StateFlow<String?> get() = fast.loadedModelName
    val lastLoadDetail: StateFlow<String> get() = fast.lastLoadDetail

    val advancedLoadState: StateFlow<LlmLoadState> get() = advanced.loadState
    val advancedModelName: StateFlow<String?> get() = advanced.loadedModelName

    /**
     * Optional third brain, used ONLY by [com.simone.jarvismobile.tools.LlmIntentClassifier]
     * — never for an actual conversational answer. Isolated from [fast] so a
     * tiny model dedicated purely to "which tool did the user mean" can be
     * imported without also becoming the model that answers ordinary chat.
     */
    val classifierLoadState: StateFlow<LlmLoadState> get() = classifier.loadState
    val classifierModelName: StateFlow<String?> get() = classifier.loadedModelName

    /** True when a second, larger model is loaded and can take hard questions. */
    val hasAdvanced: Boolean get() = advanced.loadState.value == LlmLoadState.LOADED

    /** True when a dedicated classifier model is loaded. */
    val hasClassifier: Boolean get() = classifier.loadState.value == LlmLoadState.LOADED

    /**
     * The engine [com.simone.jarvismobile.tools.LlmIntentClassifier] should use:
     * the dedicated classifier model when one is loaded, otherwise the fast
     * engine — so the app behaves exactly as before when no third model is
     * imported.
     */
    override fun classifierEngine(): LlmEngine = if (hasClassifier) classifier else fast

    fun engineFor(slot: ModelSlot): LitertLmEngine = when (slot) {
        ModelSlot.FAST -> fast
        ModelSlot.ADVANCED -> if (hasAdvanced) advanced else fast
    }

    /** Resolves the actual slot once, so the coordinator can keep its context in sync. */
    fun selectSlot(needsReasoning: Boolean): ModelSlot =
        if (needsReasoning && hasAdvanced) ModelSlot.ADVANCED else ModelSlot.FAST

    fun conversationEpoch(slot: ModelSlot): Long = engineFor(slot).conversationEpoch

    /**
     * Answers [userText]. [needsReasoning] routes to the larger model when one is
     * loaded; simple exchanges stay on the fast one.
     */
    suspend fun chat(userText: String, systemPrompt: String, needsReasoning: Boolean): String? {
        val slot = selectSlot(needsReasoning)
        return chat(userText, systemPrompt, slot)
    }

    /** Chats with an already resolved [slot]. */
    suspend fun chat(userText: String, systemPrompt: String, slot: ModelSlot): String? {
        Log.i(TAG, "chat_slot=$slot")
        return engineFor(slot).chat(userText, systemPrompt)
    }

    /** Clears one brain without discarding the other brain's warm conversation. */
    fun resetConversation(slot: ModelSlot) {
        engineFor(slot).resetConversation()
    }

    /** Clears the running conversation on both brains. */
    fun resetConversation() {
        fast.resetConversation()
        advanced.resetConversation()
    }

    fun cancel() {
        fast.cancel()
        advanced.cancel()
        classifier.cancel()
    }

    private companion object {
        const val TAG = "JarvisRouter"
    }
}
