package com.simone.jarvismobile.core.llm

enum class LoadAdviceLevel { OK, TIGHT, INSUFFICIENT }

data class LoadAdvice(val level: LoadAdviceLevel, val message: String?)

/**
 * ADVISORY ONLY (since the "unrestricted loading" change). A native out-of-memory while initialising a model can kill the
 * whole process and cannot be caught, so JARVIS tells the user what the numbers suggest — but the estimate NEVER decides
 * whether an attempt may be made: [ModelLoadPolicy] turns it into a warning or a request for confirmation, never a refusal.
 * The thresholds are heuristics over SYSTEM-WIDE available memory (which Android can grow by reclaiming caches, and which
 * is not what the process can actually allocate); they are not hardware limits and not measurements of the model.
 */
object ModelLoadAdvisor {
    /** Weights plus runtime/KV-cache overhead, as a multiple of the model file size. */
    const val OVERHEAD_FACTOR = 1.15

    fun assess(fileBytes: Long, availableBytes: Long, lowMemoryFlag: Boolean): LoadAdvice {
        if (fileBytes <= 0 || availableBytes <= 0) return LoadAdvice(LoadAdviceLevel.OK, null)
        val need = (fileBytes * OVERHEAD_FACTOR).toLong()
        val mib = { b: Long -> b / (1024 * 1024) }
        return when {
            availableBytes < need / 2 -> LoadAdvice(
                LoadAdviceLevel.INSUFFICIENT,
                "Memoria libera insufficiente secondo la stima (circa ${mib(availableBytes)} MiB disponibili, " +
                    "stima del modello circa ${mib(need)} MiB). È un'indicazione prudenziale, non un limite: Android può liberare memoria " +
                    "e il caricamento può riuscire, ma potrebbe essere molto lento, fallire o far chiudere l'app.",
            )
            availableBytes < need || lowMemoryFlag -> LoadAdvice(
                LoadAdviceLevel.TIGHT,
                "Memoria libera al limite (circa ${mib(availableBytes)} MiB, modello da ~${mib(need)} MiB): il caricamento potrebbe essere lento.",
            )
            else -> LoadAdvice(LoadAdviceLevel.OK, null)
        }
    }
}
