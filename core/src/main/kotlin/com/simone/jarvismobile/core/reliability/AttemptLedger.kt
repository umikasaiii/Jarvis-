package com.simone.jarvismobile.core.reliability

/**
 * RELIABILITY — one ledger per user request: at most [max] generative attempts in total, whatever layer wants one (the
 * quality retry, the degenerate-output retry, a verification revision). Each attempt after the first needs a recorded
 * [cause]; a refused attempt is recorded too, so the trace says why a request did not get a third try.
 */
class AttemptLedger(val max: Int = MAX_ATTEMPTS) {
    data class Entry(val cause: String, val granted: Boolean)
    private val log = ArrayList<Entry>()
    val attempts: Int get() = log.count { it.granted }
    val entries: List<Entry> get() = log.toList()

    /** The first call (cause "initial") is always granted; later ones only while attempts < [max]. */
    fun request(cause: String): Boolean {
        val ok = attempts < max
        log += Entry(cause, ok)
        return ok
    }

    fun summary(): String = log.joinToString(",") { (if (it.granted) "" else "!") + it.cause }

    companion object { const val MAX_ATTEMPTS = 2 }
}
