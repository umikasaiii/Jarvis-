package com.simone.jarvismobile.core.knowledge.fts

import java.util.concurrent.atomic.LongAdder

/**
 * Per-phase accounting of an indexing run. Times are SUMMED over threads (a phase running on two workers for one second
 * reports two seconds), so they say where the work goes, not how long the user waited — [wallMillis] is that. Cheap enough to
 * leave on: one `nanoTime` pair per phase per cluster / batch, no allocation.
 */
class IndexProfiler {
    enum class Phase { PLAN, READ, DECOMPRESS, EXTRACT, TOKENIZE, SQLITE, PROGRESS, THROTTLE }

    private val nanos = Phase.entries.associateWith { LongAdder() }
    private val counters = HashMap<String, LongAdder>()
    @Volatile var startedAt = System.nanoTime()

    fun add(phase: Phase, ns: Long) = nanos.getValue(phase).add(ns)

    inline fun <T> time(phase: Phase, block: () -> T): T {
        val t = System.nanoTime()
        try { return block() } finally { add(phase, System.nanoTime() - t) }
    }

    @Synchronized private fun counter(name: String): LongAdder = counters.getOrPut(name) { LongAdder() }
    fun count(name: String, n: Long = 1) = counter(name).add(n)

    data class Snapshot(val millis: Map<Phase, Double>, val counters: Map<String, Long>, val wallMillis: Double) {
        fun report(articles: Int, passages: Int): String = buildString {
            val wall = wallMillis / 1000
            append("tempo %.1f s · %.0f articoli/s · %.0f passaggi/s".format(wall, if (wall > 0) articles / wall else 0.0, if (wall > 0) passages / wall else 0.0))
            append(" · fasi (somma thread, ms): ")
            append(millis.entries.filter { it.value > 0.5 }.joinToString(", ") { "${it.key.name.lowercase()} %.0f".format(it.value) })
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        Phase.entries.associateWith { nanos.getValue(it).sum() / 1e6 },
        synchronized(this) { counters.mapValues { it.value.sum() } },
        (System.nanoTime() - startedAt) / 1e6,
    )
}
