package com.simone.jarvismobile.core.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A §65.1/ADR-014. The closed-world
 * registry of Responsibility types this build knows about. Never a
 * self-generated/arbitrary goal (ADR-014: "no self-generated arbitrary
 * goals") — a new value here is a deliberate product decision, not something
 * any runtime path invents. Only [MORNING_ASSISTANCE] exists today, matching
 * §65.1's "Prima responsibility: MORNING_ASSISTANCE" — PA-1A ships the
 * generic kernel only; wiring a real MORNING_ASSISTANCE instance into the
 * live Morning Briefing path is explicitly PA-2 (§65.1), not this pass.
 */
enum class ResponsibilityType {
    MORNING_ASSISTANCE,
}

/**
 * § PA-1A. The bounded lifecycle a Responsibility record moves through.
 * Deliberately the subset of §65.1's target diagram
 * (`INACTIVE → WAITING → READY → ACTING → VERIFYING → COMPLETED`, plus
 * `BLOCKED`/`FAILED`/`EXPIRED`) that [ResponsibilityLifecycle] below gives a
 * real, used transition for — no `INACTIVE` state, because PA-1A never
 * creates a record that is not already waiting on something (the master
 * doc's own rule: "Non creare stati senza una transizione reale che li
 * usa.").
 */
enum class ResponsibilityLifecycleState {
    /** Created, observing — not yet ready to act. */
    WAITING,

    /** Observation is sufficient to act, but no action has been dispatched yet. */
    READY,

    /** An action has been dispatched to an existing owner (never by this kernel itself — see [ResponsibilityDecision.ACT]'s doc comment). */
    ACTING,

    /** The dispatched action's real-world outcome is being checked against an authoritative source. */
    VERIFYING,

    /** The responsibility's goal was genuinely achieved, verified against an authoritative source. Terminal. */
    COMPLETED,

    /** Stuck pending something this kernel cannot resolve by itself (a missing permission, an unknown verification outcome, an unanswered ask-user) — never auto-retried; see [ResponsibilityVerificationOutcome.UNKNOWN]. */
    BLOCKED,

    /** A terminal failure — never retried. Terminal. */
    FAILED,

    /** The responsibility's window to act has passed without completion. Terminal. */
    EXPIRED,
    ;

    /** COMPLETED/FAILED/EXPIRED — no legal outgoing transition exists from any of these (enforced unconditionally by [ResponsibilityLifecycle], not merely by omission from its edge table). */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == EXPIRED
}

/**
 * § PA-1A. The stable logical identity of one Responsibility instance —
 * [type] plus a caller-defined [logicalScope] (e.g. a local date for a daily
 * responsibility), nothing else. Deliberately mirrors
 * `com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey`'s own
 * discipline: no `System.currentTimeMillis()`, no random UUID, no
 * worker/process id baked into identity — those would silently create a new
 * logical responsibility for what should be the same one.
 */
data class ResponsibilityKey(val type: ResponsibilityType, val logicalScope: String) {
    /** The canonical durable-storage string form — `"$type:$logicalScope"`, stable and parseable back via [parse]. */
    override fun toString(): String = "$type:$logicalScope"

    companion object {
        /** Inverse of [toString] — returns `null` on anything that does not round-trip (an unknown type, a missing scope), never throws. */
        fun parse(raw: String): ResponsibilityKey? {
            val separatorIndex = raw.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == raw.length - 1) return null
            val typeName = raw.substring(0, separatorIndex)
            val scope = raw.substring(separatorIndex + 1)
            val type = runCatching { ResponsibilityType.valueOf(typeName) }.getOrNull() ?: return null
            return ResponsibilityKey(type, scope)
        }
    }
}

/**
 * § PA-1A §65.1 ("una decisione deterministica ... ACT / WAIT / VERIFY /
 * RECHECK_AT / ASK_USER / COMPLETE / ABORT collegata alla stessa
 * responsabilità"). This is the bounded TAXONOMY only — PA-1A does not ship
 * any policy that decides WHICH of these applies to a real observation (that
 * is `ResponsibilityDecisionPolicy`/`MorningAssistancePolicy`, explicitly
 * PA-2 territory per §65.1's "Planner — adattamento obbligatorio" section).
 * [ACT] never means this kernel performs the action itself — per ADR-014
 * ("action routing delegates to existing executors/dispatchers") a future
 * adapter always delegates to an existing owner
 * (`ProactiveDeliveryDispatcher`, `AutomationExecutor`, `ToolRunner`, …),
 * never a second executor built here.
 */
enum class ResponsibilityDecision {
    WAIT, ACT, VERIFY, RECHECK_AT, ASK_USER, COMPLETE, ABORT
}

/**
 * § PA-1A §65.1 ("Verification" section). The bounded, domain-agnostic
 * outcome taxonomy every typed adapter's more specific result must collapse
 * to for the kernel's own bookkeeping — "ma ogni adapter deve conservare
 * l'esito più specifico del proprio dominio" (the adapter's richer detail is
 * not this enum's job to carry). [UNKNOWN] is the deliberate non-failure,
 * non-success state for "the side effect's real-world outcome could not be
 * durably determined" (mirrors
 * `com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState.UNKNOWN_EFFECT`)
 * — see [ResponsibilityLifecycle.afterVerification]'s doc comment for why it
 * can never auto-authorize a retry.
 */
enum class ResponsibilityVerificationOutcome {
    SUCCESS, RETRYABLE_FAILURE, TERMINAL_FAILURE, UNKNOWN
}

/**
 * § PA-1A §65.1 ("Target concettuale minimo" `ResponsibilityRecord`). The
 * pure, Android-free shape of one durable Responsibility — the Room entity
 * in `app/` is this record's storage shape, never a second source of truth
 * for what these fields mean. [revision] is the CAS fencing token every
 * mutation must present (mirrors
 * `com.simone.jarvismobile.proactive.ProactiveOccurrenceEntity.claimedAtMs`'s
 * role for occurrence rows) — it exists purely so the app-side store can do
 * `UPDATE ... WHERE logicalKey = :key AND revision = :expectedRevision`
 * without this module knowing anything about SQL.
 */
data class ResponsibilityRecord(
    val key: ResponsibilityKey,
    val lifecycleState: ResponsibilityLifecycleState,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val priority: Int = 0,
    val deadlineAtMs: Long? = null,
    val recheckAtMs: Long? = null,
    val lastDecision: ResponsibilityDecision? = null,
    val lastVerificationOutcome: ResponsibilityVerificationOutcome? = null,
    /** An opaque identity in an existing authoritative side-effect store (e.g. a `ProactiveOccurrenceKey` string) — never a second copy of that store's own delivered/unknown state (§65.1's "Ownership rule"). */
    val linkedActionOccurrenceKey: String? = null,
    /** Bounded, privacy-safe — never briefing/agenda/health content (§65.1's journal rule applies to this field too, since it is the one piece of free text this record carries). */
    val terminalReason: String? = null,
) {
    init {
        require(priority in MIN_PRIORITY..MAX_PRIORITY) { "priority must be within [$MIN_PRIORITY, $MAX_PRIORITY], was $priority" }
        require(terminalReason == null || terminalReason.length <= MAX_REASON_CHARS) {
            "terminalReason must be at most $MAX_REASON_CHARS chars, was ${terminalReason?.length}"
        }
    }

    companion object {
        const val MIN_PRIORITY = 0
        const val MAX_PRIORITY = 9
        const val MAX_REASON_CHARS = 160
    }
}

/** The result of attempting a lifecycle transition — never a thrown exception for an illegal edge, always an explicit, inspectable outcome. */
sealed interface ResponsibilityTransitionResult {
    data class Applied(val record: ResponsibilityRecord) : ResponsibilityTransitionResult
    data class Rejected(val reason: ResponsibilityTransitionRejection) : ResponsibilityTransitionResult
}

/** Bounded, closed-world reason codes for a rejected transition — never a free-text message (journal-safe by construction). */
enum class ResponsibilityTransitionRejection {
    TERMINAL_STATE_IMMUTABLE,
    ILLEGAL_EDGE,
    SAME_STATE_NOOP,
}

/**
 * § PA-1A §65.1. The pure, deterministic lifecycle transition validator —
 * the ONE place that decides whether moving a [ResponsibilityRecord] from
 * one [ResponsibilityLifecycleState] to another is legal. Mirrors
 * `com.simone.jarvismobile.core.proactive.ProactiveOccurrenceReconciler`'s
 * role for occurrence claims: this function only decides what SHOULD
 * happen — the app-side store (PA-1A's `ResponsibilityStore`) is what makes
 * it actually stick atomically via CAS-fenced Room writes, re-validating
 * before ever committing.
 */
object ResponsibilityLifecycle {

    /**
     * The full legal-edge table. Deliberately explicit and exhaustive rather
     * than computed, so every edge this kernel allows is visible in one
     * place and reviewable against §65.1's lifecycle diagram — adding a new
     * edge is a deliberate, visible change, never an emergent side effect of
     * some other refactor.
     */
    private val legalEdges: Map<ResponsibilityLifecycleState, Set<ResponsibilityLifecycleState>> = mapOf(
        ResponsibilityLifecycleState.WAITING to setOf(
            ResponsibilityLifecycleState.READY,
            ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.EXPIRED,
        ),
        ResponsibilityLifecycleState.READY to setOf(
            ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.EXPIRED,
        ),
        ResponsibilityLifecycleState.ACTING to setOf(
            ResponsibilityLifecycleState.VERIFYING,
            ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.FAILED,
        ),
        ResponsibilityLifecycleState.VERIFYING to setOf(
            ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.FAILED,
        ),
        ResponsibilityLifecycleState.BLOCKED to setOf(
            ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.READY,
            ResponsibilityLifecycleState.EXPIRED,
            ResponsibilityLifecycleState.FAILED,
        ),
        // COMPLETED/FAILED/EXPIRED deliberately absent — isTerminal is checked
        // unconditionally below, never relying on an empty set here alone.
    )

    /** Pure predicate — never mutates, never throws. */
    fun isValidTransition(from: ResponsibilityLifecycleState, to: ResponsibilityLifecycleState): Boolean {
        if (from.isTerminal) return false
        if (from == to) return false
        return legalEdges[from]?.contains(to) == true
    }

    /**
     * Attempts to move [record] to [to]. Never mutates [record] — returns a
     * new [ResponsibilityRecord] on success. [revisionAfter] is supplied by
     * the caller (the app-side store), since only the durable store knows
     * the real next revision number after a successful CAS write; this pure
     * function never invents one.
     */
    fun transition(
        record: ResponsibilityRecord,
        to: ResponsibilityLifecycleState,
        now: Long,
        revisionAfter: Long,
        decision: ResponsibilityDecision? = null,
        verificationOutcome: ResponsibilityVerificationOutcome? = null,
        terminalReason: String? = null,
    ): ResponsibilityTransitionResult {
        if (record.lifecycleState.isTerminal) {
            return ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.TERMINAL_STATE_IMMUTABLE)
        }
        if (record.lifecycleState == to) {
            return ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.SAME_STATE_NOOP)
        }
        if (!isValidTransition(record.lifecycleState, to)) {
            return ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.ILLEGAL_EDGE)
        }
        return ResponsibilityTransitionResult.Applied(
            record.copy(
                lifecycleState = to,
                revision = revisionAfter,
                updatedAtMs = now,
                lastDecision = decision ?: record.lastDecision,
                lastVerificationOutcome = verificationOutcome ?: record.lastVerificationOutcome,
                terminalReason = terminalReason ?: record.terminalReason,
            ),
        )
    }

    /**
     * § 65.1 ("Verification" section) — the single deterministic mapping
     * from a typed verification outcome to the lifecycle state a VERIFYING
     * responsibility should move toward. [ResponsibilityVerificationOutcome.UNKNOWN]
     * maps to [ResponsibilityLifecycleState.BLOCKED], never
     * [ResponsibilityLifecycleState.ACTING] — this is the literal
     * enforcement of "UNKNOWN non autorizza mai blind retry": a human/future
     * reconciliation path must move it on, this function never will.
     * Returns `null` if [from] is not [ResponsibilityLifecycleState.VERIFYING]
     * (nonsensical call — never guessed at).
     */
    fun targetStateForVerification(
        from: ResponsibilityLifecycleState,
        outcome: ResponsibilityVerificationOutcome,
    ): ResponsibilityLifecycleState? {
        if (from != ResponsibilityLifecycleState.VERIFYING) return null
        return when (outcome) {
            ResponsibilityVerificationOutcome.SUCCESS -> ResponsibilityLifecycleState.COMPLETED
            ResponsibilityVerificationOutcome.RETRYABLE_FAILURE -> ResponsibilityLifecycleState.ACTING
            ResponsibilityVerificationOutcome.TERMINAL_FAILURE -> ResponsibilityLifecycleState.FAILED
            ResponsibilityVerificationOutcome.UNKNOWN -> ResponsibilityLifecycleState.BLOCKED
        }
    }
}

/**
 * § PA-1A §65.1 ("Journal / diagnostics"). One bounded, privacy-safe entry —
 * "Mai briefing body, agenda completa, health payload, coordinate,
 * transcript, prompt o tool args sensibili", enforced here by construction:
 * every field is either a closed-world enum, a timestamp, or [reasonCode],
 * which [ResponsibilityJournalPolicy.sanitizeReasonCode] bounds and
 * sanitizes exactly like
 * `com.simone.jarvismobile.core.proactive.TriggerEvidencePolicy.sanitizeDetail`
 * does for its own `detail` field.
 */
data class ResponsibilityJournalEntry(
    val atMs: Long,
    val key: String,
    val type: ResponsibilityType,
    val fromState: ResponsibilityLifecycleState?,
    val toState: ResponsibilityLifecycleState,
    val decision: ResponsibilityDecision?,
    val verificationOutcome: ResponsibilityVerificationOutcome?,
    val reasonCode: String?,
)

/** Pure bounding rules for the journal — kept separate from Android/Room, same split as `TriggerEvidencePolicy`. */
object ResponsibilityJournalPolicy {
    const val MAX_ENTRIES_PER_KEY = 40
    const val MAX_REASON_CODE_CHARS = 160
    const val DEFAULT_RETENTION_DAYS = 30L

    /** Bounds and strips newlines — a last defensive bound, not a substitute for callers only ever passing short `key=value`-shaped codes. */
    fun sanitizeReasonCode(reasonCode: String?): String? {
        if (reasonCode == null) return null
        val flattened = reasonCode.replace('\n', ' ').replace('\r', ' ').trim()
        if (flattened.isEmpty()) return null
        return flattened.take(MAX_REASON_CODE_CHARS)
    }
}
