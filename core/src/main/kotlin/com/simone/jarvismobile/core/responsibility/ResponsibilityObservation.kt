package com.simone.jarvismobile.core.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B §7-9. The closed-world, bounded
 * taxonomy of facts that can cause a [ResponsibilityDecisionPolicy] to
 * re-evaluate a [ResponsibilityRecord]. Deliberately NOT a
 * `Map<String, Any>`/generic string-keyed bag/raw platform event/arbitrary
 * LLM-generated name — every variant below carries only timestamps and
 * closed-world enums, so a future [ResponsibilityDecisionPolicy] can never be
 * handed an observation it was not explicitly built to understand.
 *
 * PA-1B defines this taxonomy and a pure reducer over it
 * ([ResponsibilityKernel]) — it does NOT wire any of these to a real Android
 * receiver, scheduler, or notification. Nothing in `:core` ever constructs
 * one of these from a live signal; that remains PA-2 territory.
 */
sealed interface ResponsibilityObservation {
    /** When this observation was made — never when it is *applied*, which [ResponsibilityKernel.reduce]'s `now` parameter carries separately. */
    val atMs: Long

    /**
     * Something outside this responsibility's own verification loop happened
     * and may warrant re-evaluation — e.g. (PA-2+) a scheduled trigger firing,
     * a reminder window opening. [trigger] is a closed-world identifier, never
     * a free-text source name.
     */
    data class ExternalEventObserved(val trigger: ResponsibilityTriggerIdentifier, override val atMs: Long) : ResponsibilityObservation

    /** A previously-scheduled [ResponsibilityDecision.RECHECK_AT] has matured. */
    data class RecheckDue(override val atMs: Long) : ResponsibilityObservation

    /**
     * The owning process (app/worker) was restored after being torn down —
     * e.g. a cold start reconciling in-flight responsibilities. This means
     * "re-evaluate the existing record", never "create a new one" (that
     * remains [ResponsibilityStore.createIfAbsent]'s job) and never "repeat
     * the last dispatched side effect" (§20 — the reducer treats this
     * identically to any other observation, deferring entirely to the
     * policy's own decision; see [ResponsibilityKernelTest] for the proof).
     */
    data class ProcessRestored(override val atMs: Long) : ResponsibilityObservation

    /** The record's own [ResponsibilityRecord.deadlineAtMs] has been reached or passed. */
    data class DeadlineReached(override val atMs: Long) : ResponsibilityObservation

    /** A capability/authorization this responsibility depends on changed — e.g. a permission was granted or revoked. */
    data class CapabilityChanged(val granted: Boolean, override val atMs: Long) : ResponsibilityObservation

    /**
     * An authoritative verification result arrived for a responsibility
     * currently [ResponsibilityLifecycleState.VERIFYING]. The ONLY observation
     * [ResponsibilityKernel.reduce] maps via [ResponsibilityLifecycle.targetStateForVerification]
     * rather than deferring to the policy's decision — see invariants C/G/H.
     */
    data class VerificationResultObserved(val outcome: ResponsibilityVerificationOutcome, override val atMs: Long) : ResponsibilityObservation
}

/**
 * § PA-1B §7. Closed-world identifiers PA-1B introduces for FUTURE (PA-2+)
 * use as [ResponsibilityObservation.ExternalEventObserved.trigger] values —
 * explicitly NOT wired to any real Android `BroadcastReceiver`/`AlarmManager`/
 * `WorkManager`/notification in this pass, and with NO product timing policy
 * inferred about them yet (that is `MorningAssistancePolicy`, PA-2). Naming
 * these now only prevents PA-2 from inventing a second, incompatible
 * identifier scheme for signals this project's proactive-briefing stack
 * (`ProactiveTriggerSource`, untouched) already distinguishes.
 */
enum class ResponsibilityTriggerIdentifier {
    FIRST_UNLOCK,
    NEXT_ALARM,
    CONFIGURED_TIME,
    PERIODIC_FALLBACK,
}

/**
 * § PA-1B §7. Marker for a typed, immutable context snapshot a
 * [ResponsibilityDecisionPolicy] may consult alongside the record/observation/
 * timestamp. Deliberately NOT a universal `ContextEngine` state map — a real
 * domain-specific snapshot (e.g. a future `MorningAssistanceContextSnapshot`)
 * is PA-2 territory; PA-1B defines only this marker plus the trivial "no
 * context" implementation every PA-1B test/fake policy uses.
 */
interface ResponsibilityContextSnapshot

/** The empty context snapshot — every PA-1B test and fake policy that needs none uses this instead of `null`, so "no context" is itself a typed, closed-world value. */
data object NoResponsibilityContext : ResponsibilityContextSnapshot
