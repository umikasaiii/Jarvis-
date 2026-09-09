package com.simone.jarvismobile.core.segnale

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §K, corrected by
 * PASSAGGIO 12.1. Pure resolution — reading the real Android system
 * preference (`Settings.Global.ANIMATOR_DURATION_SCALE`/`ValueAnimator
 * .areAnimatorsEnabled()`) is an `app/`-side concern (needs a
 * `ContentResolver`); this is the decision function once that boolean is
 * already known, so it is genuinely testable on a plain JVM here.
 *
 * **Precedence contract (PASSAGGIO 12.1)**: system reduced motion always
 * wins. [userPrefersReducedMotion] (formerly named `userOverride`, renamed
 * because "override" wrongly implied it could ALSO override the system
 * signal back to `false`) can only ever REQUEST additional reduced motion
 * on top of what the system already grants — it can never re-enable
 * ornamental motion Android/system accessibility has disabled. Equivalent
 * to `systemReducedMotion || (userPrefersReducedMotion == true)`, i.e. a
 * boolean OR, not a null-coalesce override.
 *
 * No new Settings screen/persisted preference is introduced by this pass
 * (§K: "Do not add a new Settings screen in this pass") —
 * [userPrefersReducedMotion] is `null` today from every real caller; the
 * parameter exists so a later, genuinely requested preference can compose
 * with the system signal without this function's contract changing.
 */
object SegnaleMotionPolicy {
    fun effectiveReducedMotion(systemReducedMotion: Boolean, userPrefersReducedMotion: Boolean? = null): Boolean =
        systemReducedMotion || (userPrefersReducedMotion == true)
}
