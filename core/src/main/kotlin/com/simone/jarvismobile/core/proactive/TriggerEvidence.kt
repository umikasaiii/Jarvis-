package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. Bounded,
 * PERSISTENT diagnostic evidence for the three real Morning Briefing trigger
 * paths (FIRST_UNLOCK/NEXT_ALARM/CONFIGURED_TIME) — the exact observability
 * gap the spec requires closed: [ProactiveManager.MorningDeliveryReceipt]
 * (MICRO-PATCH 14.2.2) is process-memory only, so "no receipt visible" never
 * proves a trigger never fired if the process restarted meanwhile. This is
 * DEBUG EVIDENCE ONLY — it must never become a runtime source of truth (the
 * durable claim in `ProactiveOccurrenceStore` remains the only owner of "did
 * today's briefing actually go out") and must never carry briefing/agenda/
 * health/weather CONTENT — only enum-shaped facts and short whitelisted
 * `key=value` fragments (§ "history must be strictly bounded").
 */
enum class TriggerEvidenceSource { FIRST_UNLOCK, NEXT_ALARM, CONFIGURED_TIME, COMMON }

/** One checkpoint along a trigger's real path — never the outcome of the whole morning, just this one step. */
enum class TriggerEvidenceStage {
    // FIRST_UNLOCK path: SettingsRepository -> AutomationServiceController -> AutomationEventService.
    // Never equates "setting ON" with "service actually running" — each of
    // these is its own distinct, observable checkpoint.
    AUTOMATION_SETTING_ENABLED,
    AUTOMATION_SETTING_DISABLED,
    SERVICE_START_REQUESTED,
    SERVICE_START_FAILED,
    SERVICE_STOP_REQUESTED,
    SERVICE_ON_CREATE,
    SERVICE_ON_START_COMMAND,
    RECEIVER_REGISTERED,
    RECEIVER_REGISTER_FAILED,
    USER_PRESENT_OBSERVED,

    // NEXT_ALARM path: AlarmManager.nextAlarmClock -> MorningTriggerScheduler -> ExactAlarms -> AlarmReceiver.
    NEXT_ALARM_READ,
    NEXT_ALARM_ABSENT,
    NEXT_ALARM_SCHEDULE_ATTEMPTED,
    NEXT_ALARM_SCHEDULED,
    NEXT_ALARM_CANCELLED_PAST,
    NEXT_ALARM_SCHEDULE_FAILED,
    NEXT_ALARM_RECEIVER_FIRED,

    // CONFIGURED_TIME path: SettingsRepository -> MorningTriggerScheduler -> ExactAlarms -> AlarmReceiver.
    CONFIGURED_TIME_PERSISTED,
    CONFIGURED_TIME_SCHEDULE_ATTEMPTED,
    CONFIGURED_TIME_SCHEDULED,
    CONFIGURED_TIME_SCHEDULE_FAILED,
    CONFIGURED_TIME_RECEIVER_FIRED,

    // Shared exact-alarm permission diagnostics (§12) — never collapsed into
    // a single generic "failed" for either signal.
    EXACT_ALARM_PERMISSION_MISSING,
    EXACT_ALARM_SECURITY_EXCEPTION,

    // All sources converge on the same occurrence call (§5 "all sources").
    PROACTIVE_CALL_ATTEMPTED,
    PROACTIVE_CALL_SUCCEEDED,
    PROACTIVE_CALL_FAILED,
}

/**
 * One bounded evidence entry. [detail] is a short, whitelisted `key=value`
 * fragment (e.g. `"offsetMinutes=5"`), never free text — see
 * [TriggerEvidencePolicy.sanitizeDetail], the last defensive bound applied
 * before persistence.
 */
data class TriggerEvidenceEntry(
    val eventAtMs: Long,
    val processSessionId: String,
    val source: TriggerEvidenceSource,
    val stage: TriggerEvidenceStage,
    val detail: String? = null,
)

/** Pure bounding rules — kept separate from Android/Room so they stay unit-testable. */
object TriggerEvidencePolicy {
    const val MAX_ENTRIES_PER_SOURCE = 40
    const val MAX_DETAIL_CHARS = 160

    /**
     * Truncates [detail] to a bounded length and strips newlines. This is
     * metadata (offsets/timestamps/booleans/enum names), never briefing,
     * agenda, health or weather CONTENT — callers are expected to only ever
     * pass whitelisted `key=value` fragments; this is just the last
     * defensive bound (§ "history must be strictly bounded").
     */
    fun sanitizeDetail(detail: String?): String? {
        if (detail == null) return null
        val flattened = detail.replace('\n', ' ').replace('\r', ' ').trim()
        if (flattened.isEmpty()) return null
        return flattened.take(MAX_DETAIL_CHARS)
    }
}
