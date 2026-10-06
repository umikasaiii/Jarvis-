package com.simone.jarvismobile.core.voice

/**
 * Deterministic bounded re-framer with EXACT sample conservation: samples go in at any chunk size,
 * come out as fixed [blockSize] blocks, in order. Never drops, duplicates or invents (no padding
 * silence). The pending remainder is always < [blockSize]. [reset] discards the remainder
 * (used when a sequence gap/generation change makes continuity impossible).
 * Not thread-safe: one owner feeds it sequentially.
 */
class ShortBlockAssembler(val blockSize: Int) {
    init { require(blockSize in 1..4_096) { "blockSize out of bounds" } }
    private val pending = ShortArray(blockSize)
    private var pendingCount = 0
    val pendingSamples: Int get() = pendingCount
    var totalIn: Long = 0; private set
    var totalOut: Long = 0; private set
    var discarded: Long = 0; private set

    fun reset() { discarded += pendingCount; pendingCount = 0 }

    /** Appends [count] samples of [src]; invokes [emit] with a FRESH array for each completed block. */
    fun push(src: ShortArray, count: Int, emit: (ShortArray) -> Unit) {
        require(count in 0..src.size)
        totalIn += count
        var i = 0
        while (i < count) {
            val take = minOf(blockSize - pendingCount, count - i)
            System.arraycopy(src, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            if (pendingCount == blockSize) {
                emit(pending.copyOf())
                totalOut += blockSize
                pendingCount = 0
            }
        }
    }
}

class FloatBlockAssembler(val blockSize: Int) {
    init { require(blockSize in 1..4_096) { "blockSize out of bounds" } }
    private val pending = FloatArray(blockSize)
    private var pendingCount = 0
    val pendingSamples: Int get() = pendingCount
    var totalIn: Long = 0; private set
    var totalOut: Long = 0; private set
    var discarded: Long = 0; private set

    fun reset() { discarded += pendingCount; pendingCount = 0 }

    fun push(src: FloatArray, count: Int, emit: (FloatArray) -> Unit) {
        require(count in 0..src.size)
        totalIn += count
        var i = 0
        while (i < count) {
            val take = minOf(blockSize - pendingCount, count - i)
            System.arraycopy(src, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            if (pendingCount == blockSize) {
                emit(pending.copyOf())
                totalOut += blockSize
                pendingCount = 0
            }
        }
    }
}
