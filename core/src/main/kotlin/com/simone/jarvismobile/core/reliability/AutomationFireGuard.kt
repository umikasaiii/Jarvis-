package com.simone.jarvismobile.core.reliability

import java.time.Duration
import java.time.LocalDateTime

/**
 * Collapses a duplicated delivery of the same exact alarm (re-armed twice around a reboot, a retry, a stale
 * PendingIntent) into one run: a rule that already ran within [minGap] is not run again.
 */
object AutomationFireGuard {
    val DEFAULT_MIN_GAP: Duration = Duration.ofSeconds(55)

    fun shouldRun(enabled: Boolean, lastFired: LocalDateTime?, now: LocalDateTime, minGap: Duration = DEFAULT_MIN_GAP): Boolean {
        if (!enabled) return false
        if (lastFired == null) return true
        val since = Duration.between(lastFired, now)
        // A negative gap (clock moved back) must not block the rule forever.
        return since.isNegative || since >= minGap
    }
}
