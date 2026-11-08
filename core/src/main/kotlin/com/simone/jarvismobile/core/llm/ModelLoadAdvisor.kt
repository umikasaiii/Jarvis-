package com.simone.jarvismobile.core.llm

enum class LoadAdviceLevel { OK, TIGHT, INSUFFICIENT }

data class LoadAdvice(val level: LoadAdviceLevel, val message: String?)

/**
 * BETA 2/8 — a native out-of-memory while initialising a model kills the whole process and cannot be
 * caught. This estimate refuses only the cases that are very unlikely to succeed and warns on tight ones.
 * The thresholds are heuristics, not measurements: the real figure must be read on the device.
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
                "Memoria libera insufficiente per questo modello (circa ${mib(availableBytes)} MiB disponibili, " +
                    "ne servono circa ${mib(need)} MiB). Chiudi le altre app e riprova, oppure usa un modello più piccolo.",
            )
            availableBytes < need || lowMemoryFlag -> LoadAdvice(
                LoadAdviceLevel.TIGHT,
                "Memoria libera al limite (circa ${mib(availableBytes)} MiB, modello da ~${mib(need)} MiB): il caricamento potrebbe essere lento.",
            )
            else -> LoadAdvice(LoadAdviceLevel.OK, null)
        }
    }
}
