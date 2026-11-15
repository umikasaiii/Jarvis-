package com.simone.jarvismobile.core.reliability

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * RELIABILITY — ONE ledger per user request, consulted BEFORE every native generation of the answer (first attempt,
 * degenerate-output retry inside the inference slot, quality retry, verification-guided revision). At most [max]
 * generations in total; an exhausted ledger produces ZERO new generations. Every grant and every refusal is recorded with its cause.
 *
 * It travels as a coroutine-context element ([AttemptBudget]), so the slot that owns the native engine counts at the real
 * boundary without any extra parameter on the engine API. [nativeCounting] says whether such a slot is in the loop: when it
 * is, orchestrators only PLAN a cause and check [exhausted]; when it is not (pure tests), they consume the budget themselves.
 */
class AttemptLedger(val max: Int = MAX_ATTEMPTS, val nativeCounting: Boolean = false) {
    data class Entry(val cause: String, val granted: Boolean)
    private val log = ArrayList<Entry>()
    @Volatile private var planned: String? = null

    val attempts: Int @Synchronized get() = log.count { it.granted }
    val entries: List<Entry> @Synchronized get() = log.toList()
    val remaining: Int get() = (max - attempts).coerceAtLeast(0)
    val exhausted: Boolean get() = remaining == 0

    /** Names the cause of the NEXT generation (read once by whoever performs it). */
    fun plan(cause: String) { planned = cause }

    /** Consumes one attempt if any is left; [cause] defaults to the planned one. Refusals are recorded too. */
    @Synchronized fun request(cause: String? = null): Boolean {
        val c = cause ?: planned ?: "initial"
        planned = null
        val ok = log.count { it.granted } < max
        log += Entry(c, ok)
        return ok
    }

    /** Orchestrator-side gate: consumes when nobody counts at the native boundary, else only plans and checks. */
    fun grant(cause: String): Boolean = if (nativeCounting) { if (exhausted) { synchronized(this) { log += Entry(cause, false) }; false } else { plan(cause); true } } else request(cause)

    fun summary(): String = entries.joinToString(",") { (if (it.granted) "" else "!") + it.cause }

    companion object { const val MAX_ATTEMPTS = 2 }
}

/** Carries the request's [AttemptLedger] into the inference slot. */
class AttemptBudget(val ledger: AttemptLedger) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AttemptBudget>
}

/** The ledger of the request being served, if the caller installed one. */
suspend fun currentAttemptLedger(): AttemptLedger? = coroutineContext[AttemptBudget]?.ledger
