package com.simone.jarvismobile.memory

import android.util.Log
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorts a memory into one macro-category with the on-device model, so the
 * archive groups like a notes app ("mi piace il gelato" and "la pizza ai funghi"
 * both land in «Cibo e gusti»).
 *
 * Best-effort by design: it asks for ONE word from a fixed list on a stateless
 * [LlmEngine.generate] call (so it never pollutes the conversation), and returns
 * "" when no model is loaded — the UI then falls back to the keyword topic, and
 * the memory is saved regardless. Only the choice is taken from the model; it is
 * mapped to a canonical bucket by the pure [MemoryCategories.normalize].
 */
@Singleton
class MemoryCategoryClassifier @Inject constructor(
    private val llm: LlmEngine,
) {
    suspend fun classify(text: String): String {
        if (text.isBlank() || llm.loadState.value != LlmLoadState.LOADED) return ""
        val generated = runCatching { llm.generate(prompt(text)) }.getOrNull() ?: return ""
        val line = generated.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val category = MemoryCategories.normalize(line)
        Log.i(TAG, "memory_category=$category")
        return category
    }

    private fun prompt(text: String): String = """
        Classifica questo appunto personale in UNA sola macro-categoria, scelta
        ESATTAMENTE da questa lista:
        ${MemoryCategories.CANONICAL.joinToString(", ")}.
        Rispondi con una sola riga: solo il nome della categoria, niente altro.

        Esempi:
        Appunto: mi piace il gelato
        Categoria: Cibo e gusti
        Appunto: la pizza ai funghi è la mia preferita
        Categoria: Cibo e gusti
        Appunto: sono allergico alla polvere
        Categoria: Salute
        Appunto: il mutuo scade il 5 di ogni mese
        Categoria: Soldi e acquisti
        Appunto: mia sorella si chiama Anna
        Categoria: Famiglia e amici

        Appunto: ${text.replace('\n', ' ').take(MAX_CHARS)}
        Categoria:
    """.trimIndent()

    private companion object {
        const val TAG = "JarvisMemoryCat"
        const val MAX_CHARS = 300
    }
}
