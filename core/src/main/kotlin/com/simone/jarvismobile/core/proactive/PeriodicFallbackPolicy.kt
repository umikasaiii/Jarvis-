package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §12 (PERIODIC FALLBACK CORRECTION). The old rule was
 * `automationServiceEnabled == true -> periodic recovery suppressed
 * forever` — but a desired-ON setting is never proof the runtime observer
 * (`AutomationEventService`) is actually alive (§11's own distinction).
 * This is the pure replacement: before the configured fallback time, a
 * periodic tick never sends the morning digest early (so a still-possibly-
 * working FIRST_UNLOCK/NEXT_ALARM signal isn't preempted); once the
 * configured fallback time has passed, periodic reconciliation may recover
 * a still-legitimately-available occurrence regardless of the desired
 * service preference — the actual delivery decision (whether an occurrence
 * is still AVAILABLE) is made by the durable occurrence claim one layer up,
 * never by this policy.
 */
object PeriodicFallbackPolicy {
    fun shouldOfferMorningOnPeriodicTick(
        automationServiceDesiredOn: Boolean,
        nowMinuteOfDay: Int,
        configuredFallbackMinuteOfDay: Int,
    ): Boolean = !automationServiceDesiredOn || nowMinuteOfDay >= configuredFallbackMinuteOfDay
}
