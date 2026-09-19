package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE A (§11). The result of one dispatch attempt through the
 * single canonical [ProactiveDeliveryDispatcher] — mirrors
 * [ProactiveDispatchOutcome] but adds the one outcome that lives above the
 * notifier: [FencingLost], a canonical DB transition failure that means NO
 * Android call was ever made (§13: "canonical DB transition failure before
 * dispatch ⇒ NO Android call").
 */
sealed interface ProactiveDeliveryResult {
    data object Posted : ProactiveDeliveryResult
    data object BlockedPermission : ProactiveDeliveryResult
    data class UnknownEffect(val detail: String) : ProactiveDeliveryResult

    /** The fenced DB transition to DISPATCH_INTENT (`markDeliveryAttempt`) did not stick — a takeover changed `claimedAtMs` first. The notifier is never called. */
    data object FencingLost : ProactiveDeliveryResult
}

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE A (§11/§12/§13). The ONE production dispatch owner for every
 * occurrence-backed proactive kind (MORNING_DIGEST/EVENING_DIGEST/
 * WEATHER_ALERT). Thin by design — never a second policy/governor/composer:
 * it only (1) obtains durable dispatch permission via a fenced Room
 * transition, (2) calls the internal notifier exactly once if and only if
 * that transition stuck, (3) records the typed outcome, fenced by the same
 * `expectedClaimedAtMs` token. No direct production `notifier.dispatch(...)`
 * call may bypass this class for an occurrence-backed kind.
 */
@Singleton
class ProactiveDeliveryDispatcher @Inject constructor(
    private val occurrenceStore: ProactiveOccurrenceStore,
    private val notifier: ProactiveNotifier,
) {
    suspend fun dispatch(
        occurrenceKey: String,
        expectedClaimedAtMs: Long,
        suggestion: ProactiveSuggestion,
        silent: Boolean = false,
        tag: String? = null,
    ): ProactiveDeliveryResult {
        val fenced = occurrenceStore.markDeliveryAttempt(occurrenceKey, expectedClaimedAtMs)
        if (!fenced) return ProactiveDeliveryResult.FencingLost

        return when (val outcome = notifier.dispatch(suggestion, silent, tag)) {
            is ProactiveDispatchOutcome.Posted -> {
                occurrenceStore.markDelivered(occurrenceKey, expectedClaimedAtMs)
                ProactiveDeliveryResult.Posted
            }
            is ProactiveDispatchOutcome.BlockedPermission -> {
                occurrenceStore.markBlockedPermission(occurrenceKey, "notification_blocked", expectedClaimedAtMs)
                ProactiveDeliveryResult.BlockedPermission
            }
            is ProactiveDispatchOutcome.ApiThrew -> {
                occurrenceStore.markUnknownEffect(occurrenceKey, outcome.exceptionClass, expectedClaimedAtMs)
                ProactiveDeliveryResult.UnknownEffect(outcome.exceptionClass)
            }
        }
    }
}
