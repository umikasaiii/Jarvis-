package com.simone.jarvismobile.core.reliability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Why alarms have to be booked again. Android drops them all on shutdown/update; zone and clock changes make them wrong. */
enum class RearmTrigger {
    BOOT,
    PACKAGE_REPLACED,
    TIMEZONE_CHANGED,
    TIME_CHANGED,
    EXACT_ALARM_PERMISSION_CHANGED,
    APP_START,
    WORKER_SAFETY_NET,
}

/** The independent things that can be re-armed. Each one is isolated from the others. */
enum class RearmStep { AGENDA_REMINDERS, AUTOMATIONS, RULES, PLACES, PROACTIVE }

enum class StepStatus { OK, FAILED, TIMED_OUT, SKIPPED }

data class StepResult(val step: RearmStep, val status: StepStatus, val error: String? = null)

/**
 * The verifiable outcome of one re-arm pass. [startedAtMs] is wall-clock time; nothing here carries user content —
 * only step names, statuses and exception class names.
 */
data class RearmReport(
    val trigger: RearmTrigger,
    val startedAtMs: Long,
    val results: List<StepResult>,
) {
    val allOk: Boolean get() = results.none { it.status == StepStatus.FAILED || it.status == StepStatus.TIMED_OUT }
    val failedSteps: List<RearmStep> get() = results.filter { it.status == StepStatus.FAILED || it.status == StepStatus.TIMED_OUT }.map { it.step }

    /** One line, safe to log and to show in diagnostics. */
    fun summary(): String =
        "trigger=${trigger.name} " + results.joinToString(" ") { "${it.step.name}=${it.status.name}" }
}

object RearmPlan {
    /**
     * Which steps a trigger needs. A time-zone or clock change only invalidates wall-clock alarms, so geofences
     * (location based, not time based) are left alone; after a reboot or an update everything is gone.
     */
    fun stepsFor(trigger: RearmTrigger): List<RearmStep> = when (trigger) {
        RearmTrigger.BOOT, RearmTrigger.PACKAGE_REPLACED, RearmTrigger.APP_START, RearmTrigger.WORKER_SAFETY_NET ->
            listOf(RearmStep.AGENDA_REMINDERS, RearmStep.AUTOMATIONS, RearmStep.RULES, RearmStep.PLACES, RearmStep.PROACTIVE)
        RearmTrigger.TIMEZONE_CHANGED, RearmTrigger.TIME_CHANGED ->
            listOf(RearmStep.AGENDA_REMINDERS, RearmStep.AUTOMATIONS, RearmStep.RULES, RearmStep.PROACTIVE)
        RearmTrigger.EXACT_ALARM_PERMISSION_CHANGED ->
            listOf(RearmStep.AGENDA_REMINDERS, RearmStep.AUTOMATIONS, RearmStep.RULES, RearmStep.PROACTIVE)
    }
}

/**
 * Runs the re-arm steps one by one. The previous implementation ran them in a single `try`: if the agenda failed to
 * load, the automations, the rule engine and — worst — the briefing alarms were never re-armed, and nothing recorded
 * it. Here every step is isolated (an exception or a timeout in one never skips the others) and the result of each is
 * reported. Cancellation of the caller is still propagated.
 */
class RearmRunner(
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(trigger: RearmTrigger, actions: Map<RearmStep, suspend () -> Unit>): RearmReport {
        val started = clock()
        val results = RearmPlan.stepsFor(trigger).map { step ->
            val action = actions[step] ?: return@map StepResult(step, StepStatus.SKIPPED)
            try {
                withTimeout(stepTimeoutMs) { action() }
                StepResult(step, StepStatus.OK)
            } catch (e: TimeoutCancellationException) {
                StepResult(step, StepStatus.TIMED_OUT, e.javaClass.simpleName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                StepResult(step, StepStatus.FAILED, e.javaClass.simpleName)
            }
        }
        return RearmReport(trigger, started, results)
    }

    companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 8_000L
    }
}
