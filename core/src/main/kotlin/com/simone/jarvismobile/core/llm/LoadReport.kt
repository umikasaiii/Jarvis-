package com.simone.jarvismobile.core.llm

/**
 * What one load attempt looked like, for the diagnostics screen. Every figure is optional (-1 = not available) and every
 * label says what it IS: system-wide available memory is not what the process may allocate, the estimate is a heuristic over
 * the file size, and the "measured" growth is the process's own proportional set size before/after — not a hardware limit.
 */
data class LoadReport(
    val slot: String,
    val modelName: String,
    val backend: String,
    val fileBytes: Long,
    val estimateBytes: Long,
    val totalRamBytes: Long,
    val availBeforeBytes: Long,
    val lowMemoryBefore: Boolean,
    val availAfterBytes: Long,
    val processPssBeforeBytes: Long,
    val processPssAfterBytes: Long,
    val adviceLevel: String,
    val durationMs: Long,
    val outcome: Outcome,
    val error: String?,
    val experimentalMode: Boolean,
    val parked: List<String> = emptyList(),
) {
    enum class Outcome { LOADED, FAILED }

    val measuredGrowthBytes: Long get() = if (processPssBeforeBytes >= 0 && processPssAfterBytes >= 0) processPssAfterBytes - processPssBeforeBytes else -1

    fun describe(): List<String> {
        fun mib(b: Long) = if (b < 0) "n/d" else "${b / (1024 * 1024)} MiB"
        val out = ArrayList<String>()
        out += "Modello: $modelName · slot $slot · backend $backend"
        out += "File: ${mib(fileBytes)} · stima di consumo (euristica): ${mib(estimateBytes)} · giudizio della stima: $adviceLevel"
        out += "RAM fisica: ${mib(totalRamBytes)} · disponibile secondo Android prima del caricamento: ${mib(availBeforeBytes)}" + if (lowMemoryBefore) " (Android segnala memoria bassa)" else ""
        if (availAfterBytes >= 0) out += "Disponibile dopo il tentativo: ${mib(availAfterBytes)}"
        if (measuredGrowthBytes >= 0) {
            val delta = measuredGrowthBytes - estimateBytes
            out += "Crescita misurata del processo (PSS): ${mib(measuredGrowthBytes)} · differenza dalla stima: ${if (delta >= 0) "+" else "-"}${mib(kotlin.math.abs(delta))}"
        }
        out += "Esito: " + (if (outcome == Outcome.LOADED) "caricato" else "NON caricato") + " in ${durationMs / 1000.0} s" + (if (experimentalMode) " (modalità sperimentale)" else "")
        error?.let { out += "Errore del runtime: $it" }
        if (parked.isNotEmpty()) out += "Messi in pausa per fare spazio: ${parked.joinToString()}"
        out += "Nota: la memoria «disponibile» è un dato di sistema, non la quantità allocabile dal processo; la stima non è un limite hardware."
        return out
    }
}
