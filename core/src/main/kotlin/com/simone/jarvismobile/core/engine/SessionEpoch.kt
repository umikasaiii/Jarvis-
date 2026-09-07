package com.simone.jarvismobile.core.engine

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 3 (Session Epoch + Reset +
 * Stale Callback Safety), findings JARVIS-07/JARVIS-16. The smallest reusable
 * primitive this phase's whole invariant rests on: a session owner (in this
 * codebase, `ConversationManager` — see that class' own doc comment) holds
 * one of these and bumps it via [invalidate] on reset; a turn/job captures
 * [current] once at its own start and, before committing any result into
 * that owner's active state, checks [isCurrent] against the value it
 * captured. A native/blocking call that ignores cancellation and returns
 * AFTER a reset therefore still cannot land its result: the epoch it
 * captured no longer matches by the time it tries to commit.
 *
 * Deliberately NOT itself a session/lifecycle manager (§5 of the phase spec
 * — "do not create SessionManager2/ConversationLifecycleManager/
 * ResetCoordinator") — just the one counter + one comparison every mutator
 * in the actual session owner reuses, instead of a scattered ad-hoc boolean
 * per field. Not thread-synchronized beyond `@Volatile`: a session's reset
 * and its turns already run serialized through `SessionCoordinator`'s own
 * `sessionMutex`, so this only needs to be safe for concurrent READS racing
 * a single WRITE, which a plain volatile `Long` already is.
 */
class SessionEpoch {
    @Volatile private var value: Long = 0L

    /** The current epoch. A turn/job should capture this once, at its own start, and pass it back into [isCurrent] before committing. */
    fun current(): Long = value

    /** True when [epoch] (captured earlier by some in-flight turn/job via [current]) is still the session's current one. */
    fun isCurrent(epoch: Long): Boolean = epoch == value

    /** Bumps the epoch — callers MUST do this BEFORE any dependent cleanup/cancellation, never after (§2 reset order: invalidate first, never depend on cancellation succeeding). Returns the new value. */
    fun invalidate(): Long {
        value++
        return value
    }
}

/**
 * A value bound to the [SessionEpoch] epoch that was current when it was
 * produced — see [SessionEpoch]'s own doc comment for the exact race this
 * closes. Generic and reused as-is by every owner of epoch-scoped pending
 * state in this codebase (never a second, per-class copy of the same shape).
 */
data class EpochScoped<T>(val value: T, val epoch: Long)
