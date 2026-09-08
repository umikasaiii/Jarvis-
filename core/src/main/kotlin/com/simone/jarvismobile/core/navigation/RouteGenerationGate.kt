package com.simone.jarvismobile.core.navigation

import com.simone.jarvismobile.core.engine.SessionEpoch

/**
 * The generation-arbitration primitive JARVIS Implementation Master Plan
 * PASSAGGIO 9 needs: whichever route computation was accepted most recently
 * wins, and an older one finishing later — even one whose coroutine escaped
 * cancellation — must be discarded, never resurrect or overwrite newer
 * navigation state (§1/§2/§3).
 *
 * Deliberately just [SessionEpoch] — already this codebase's "smallest
 * reusable primitive" for exactly this shape of problem, built for the
 * conversation engine in PASSAGGIO 3 and explicitly documented there as
 * "reused as-is by every owner of epoch-scoped pending state in this
 * codebase" — plus the one lock every mutator of generation-scoped
 * navigation state shares. Not a second, navigation-flavoured copy of that
 * primitive, and not a general concurrency framework: the lock only ever
 * guards a plain field/StateFlow assignment lambda, never a suspend call.
 *
 * [com.simone.jarvismobile.navigation.NavigationRepository] is this gate's
 * only intended owner: one instance per navigation session, driving route
 * requests, reroutes and stop exactly as this class' own doc on each method
 * describes.
 */
class RouteGenerationGate {
    private val epoch = SessionEpoch()
    private val lock = Any()

    /**
     * A new logical request — an initial route search or a reroute — each
     * get their own generation (§1/§4: "a reroute is a new route computation
     * generation"). Returns the generation to tag that request's eventual
     * result with; any generation the caller had previously captured is
     * superseded from this call onward, whether or not its coroutine/job is
     * also cancelled (§2 — cancellation is desirable but never required for
     * correctness here).
     */
    fun beginGeneration(): Long = synchronized(lock) { epoch.invalidate() }

    /**
     * The epoch's current value. A plain read, safe without the lock for the
     * same reason [SessionEpoch] itself documents: concurrent reads racing a
     * single synchronized writer need no lock of their own.
     */
    fun current(): Long = epoch.current()

    /**
     * Runs [publish] only if [generation] is still current, inside the same
     * lock [beginGeneration]/[stop] use — closing the race where a result
     * passes the generation check just as a concurrent stop or newer request
     * invalidates it a moment later. Returns whether [publish] ran, so the
     * caller can log/diagnose a discard without duplicating the check.
     */
    fun publishIfCurrent(generation: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (!epoch.isCurrent(generation)) return@synchronized false
        publish()
        true
    }

    /**
     * Invalidates every outstanding generation BEFORE [clear] runs, and
     * atomically with it (§3: "invalidate... BEFORE or atomically with
     * clearing the active route/session") — a [publishIfCurrent] racing this
     * call either fully completes first (and this stop then correctly
     * invalidates what it just published) or sees the bumped epoch and
     * discards, never a torn result in between. Returns the new, post-stop
     * generation; starting navigation again always calls [beginGeneration]
     * fresh, so a stopped generation is never reused (§3).
     */
    fun stop(clear: () -> Unit): Long = synchronized(lock) {
        val next = epoch.invalidate()
        clear()
        next
    }
}
