package com.simone.jarvismobile.core.knowledge.fts

/** What an indexing run may do right now. [stopReason] non-null = pause (the cursor is kept, a later run resumes). */
data class IndexDecision(val workers: Int, val pauseMillis: Long = 0, val stopReason: String? = null)

/** Asked at every checkpoint (a few times per second at most); must be cheap and thread-safe. */
fun interface IndexGovernor { fun decide(): IndexDecision }

/** What the device reports. [thermalStatus] uses Android's `PowerManager.THERMAL_STATUS_*` scale (0 none … 6 shutdown). */
data class DeviceConditions(
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
    val thermalStatus: Int,
    val lowMemory: Boolean,
    val availableMemoryMb: Long,
)

/**
 * KNOWLEDGE ENGINE 2.1 — adaptive limits as a PURE function of the device conditions, so they are unit-tested on the JVM. The
 * indexer is a background convenience: it yields CPU, heat, battery and memory long before the user would notice, and it can
 * always resume. It never raises priority or bypasses the platform's own power management.
 */
object IndexPolicy {
    const val STOP_BATTERY_PERCENT = 15
    const val SPARE_BATTERY_PERCENT = 30
    const val STOP_MEMORY_MB = 120L
    const val TIGHT_MEMORY_MB = 300L
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    fun decide(c: DeviceConditions, maxWorkers: Int): IndexDecision {
        val max = maxWorkers.coerceAtLeast(1)
        if (!c.charging && c.batteryPercent in 0 until STOP_BATTERY_PERCENT) return IndexDecision(1, stopReason = "Batteria sotto il $STOP_BATTERY_PERCENT%: indicizzazione in pausa.")
        if (c.thermalStatus >= THERMAL_SEVERE) return IndexDecision(1, stopReason = "Telefono troppo caldo: indicizzazione in pausa.")
        if (c.availableMemoryMb in 0 until STOP_MEMORY_MB) return IndexDecision(1, stopReason = "Memoria disponibile molto bassa: indicizzazione in pausa.")
        var workers = max
        var pause = 0L
        if (c.thermalStatus >= THERMAL_MODERATE) { workers = 1; pause = maxOf(pause, 400) } else if (c.thermalStatus >= THERMAL_LIGHT) workers = minOf(workers, maxOf(1, max - 1))
        if (c.powerSave) { workers = 1; pause = maxOf(pause, 150) }
        if (c.lowMemory || (c.availableMemoryMb in 0 until TIGHT_MEMORY_MB)) workers = 1
        if (!c.charging && c.batteryPercent in 0 until SPARE_BATTERY_PERCENT) workers = 1
        return IndexDecision(workers, pause)
    }
}
