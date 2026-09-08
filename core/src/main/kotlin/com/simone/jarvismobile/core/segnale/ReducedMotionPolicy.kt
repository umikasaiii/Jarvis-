package com.simone.jarvismobile.core.segnale

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §K. Pure resolution —
 * reading the real Android system preference (`Settings.Global.
 * ANIMATOR_DURATION_SCALE`/`WindowManager`'s "remove animations") is an
 * `app/`-side concern (needs a `ContentResolver`); this is the decision
 * function once that boolean is already known, so it is genuinely testable
 * on a plain JVM here.
 *
 * No new Settings screen/persisted preference is introduced by this pass
 * (§K: "Do not add a new Settings screen in this pass") — [userOverride] is
 * `null` today from every real caller; the parameter exists so a later,
 * genuinely requested preference can compose with the system signal without
 * this function's contract changing.
 */
object SegnaleMotionPolicy {
    fun effectiveReducedMotion(systemReducedMotion: Boolean, userOverride: Boolean? = null): Boolean =
        userOverride ?: systemReducedMotion
}
