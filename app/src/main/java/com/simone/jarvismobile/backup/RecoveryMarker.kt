package com.simone.jarvismobile.backup

import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.3 §2/§3/§4 — the pure,
 * Android-independent half of post-cutover marker handling. Kept separate
 * from [BackupRepository] only so this logic is unit-testable on a plain
 * JVM temp directory (same reasoning as [RestoreStaging]) — it has no
 * public entry point of its own beyond [BackupRepository], not a second
 * recovery system.
 */
internal object RecoveryMarker {

    /**
     * Attempts to create [marker] and reports whether it is durably present
     * afterward — NOT whether this specific call is what created it.
     * `File.createNewFile()`'s own boolean return conflates "I just created
     * it" (true) with "it already existed" (false); both are fine outcomes
     * here. Only "still absent after trying" (e.g. the parent directory
     * vanished, or the volume is full/read-only) is a genuine failure —
     * marker persistence is part of restore correctness, never a
     * best-effort diagnostic silently ignored.
     */
    fun persist(marker: File): Boolean {
        runCatching { marker.createNewFile() }
        return marker.exists()
    }

    /**
     * If [marker] does not exist, there is nothing to prove — returns
     * `true` without ever invoking [open]. If it does, invokes [open]
     * exactly once (any exception it throws is contained here, never
     * propagated — a failed open must not crash cold start; the caller
     * only ever passes a synchronous, non-suspending action, so there is
     * no [kotlinx.coroutines.CancellationException] to worry about
     * containing) and reports whether [marker] is now gone — i.e. whatever
     * ran during [open] (in the real caller: Room opening, which runs
     * `RestoreSanitizeCallback.onOpen()`) actually cleared it. A swallowed
     * or failed sanitize inside that callback therefore still surfaces
     * here as "not proven," instead of a successful-looking Room open
     * silently standing in for a successful sanitize.
     */
    fun ensureClearedByOpening(marker: File, open: () -> Unit): Boolean {
        if (!marker.exists()) return true
        runCatching(open)
        return !marker.exists()
    }
}
