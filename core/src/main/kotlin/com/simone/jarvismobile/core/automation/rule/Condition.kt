package com.simone.jarvismobile.core.automation.rule

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The "SE" half of a rule: composable tests over the current context (§9).
 *
 * Conditions are a tree rather than a flat list plus an operator field, so
 * "weekdays AND (at home OR driving) AND NOT charging" is expressible without a
 * special case. Evaluation is pure and total: an unknown fact is never guessed.
 */
sealed interface Condition {

    // --- Logical composition ------------------------------------------
    /** True when every child is true. An empty list is true (nothing forbids). */
    data class All(val of: List<Condition>) : Condition

    /** True when at least one child is true. An empty list is false. */
    data class Any(val of: List<Condition>) : Condition

    data class Not(val of: Condition) : Condition

    // --- Time ----------------------------------------------------------
    data class DayOfWeekIn(val days: Set<DayOfWeek>) : Condition

    /**
     * Between [from] and [to]. A window that ends before it starts is read as
     * crossing midnight ("22:00–06:00"), which is how people say it.
     */
    data class TimeRange(val from: LocalTime, val to: LocalTime) : Condition

    // --- Place / movement ----------------------------------------------
    data class CurrentPlace(val placeId: String) : Condition
    data class ActivityType(val activity: String) : Condition

    // --- Device --------------------------------------------------------
    data class BatteryAbove(val percent: Int) : Condition
    data class BatteryBelow(val percent: Int) : Condition
    data object IsCharging : Condition
    data object NetworkAvailable : Condition
    data class BluetoothConnected(val device: String) : Condition
    data class JarvisMode(val mode: String) : Condition

    // --- Agenda / history ----------------------------------------------
    data object CalendarEventExists : Condition

    /** Guards against a rule re-firing too soon for reasons of its own. */
    data class RuleNotExecutedRecently(val minutes: Long) : Condition
}

/**
 * A pure snapshot of everything conditions may ask about.
 *
 * This is intentionally a plain value with nullable fields rather than a live
 * reference to the (not yet built) ContextEngine: it keeps `:core` testable, and
 * it forces the "I don't know" case to be explicit. A null field means *unknown*,
 * not false — see [ConditionEvaluator] for what that does.
 */
data class EvaluationContext(
    val now: LocalDateTime,
    val placeId: String? = null,
    val activity: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val networkAvailable: Boolean? = null,
    val connectedBluetooth: Set<String> = emptySet(),
    val bluetoothKnown: Boolean = true,
    val jarvisMode: String? = null,
    val hasCalendarEvent: Boolean? = null,
    val lastExecutionAt: LocalDateTime? = null,
)

/**
 * Decides whether a condition tree holds.
 *
 * **Unknown facts make a condition false, never true.** If the phone cannot say
 * where it is, "quando sono a casa" does not hold — so a rule that would turn
 * off the alarm, unlock something or spend money cannot fire on a guess. The
 * cost is a missed automation when a sensor is unavailable, which is the right
 * side to err on. [Condition.Not] inverts only a *known* result, so an unknown
 * fact stays unknown-and-false rather than flipping into true.
 */
object ConditionEvaluator {

    /** Evaluates [condition] against [context]. A null condition is "no filter". */
    fun evaluate(condition: Condition?, context: EvaluationContext): Boolean =
        when (condition) {
            null -> true
            else -> known(condition, context) == true
        }

    /**
     * Three-valued evaluation: true, false, or null for "cannot tell". Exposed
     * for tests and for diagnostics that want to explain *why* a rule did not
     * run, rather than only that it did not.
     */
    fun known(condition: Condition, context: EvaluationContext): Boolean? = when (condition) {
        is Condition.All -> {
            val results = condition.of.map { known(it, context) }
            when {
                results.any { it == false } -> false
                results.any { it == null } -> null
                else -> true
            }
        }

        is Condition.Any -> {
            if (condition.of.isEmpty()) {
                false
            } else {
                val results = condition.of.map { known(it, context) }
                when {
                    results.any { it == true } -> true
                    results.any { it == null } -> null
                    else -> false
                }
            }
        }

        // Negating something unknown leaves it unknown — never true.
        is Condition.Not -> known(condition.of, context)?.not()

        is Condition.DayOfWeekIn ->
            condition.days.isEmpty() || context.now.dayOfWeek in condition.days

        is Condition.TimeRange -> inWindow(context.now.toLocalTime(), condition.from, condition.to)

        is Condition.CurrentPlace -> context.placeId?.let { it == condition.placeId }
        is Condition.ActivityType ->
            context.activity?.let { it.equals(condition.activity, ignoreCase = true) }

        is Condition.BatteryAbove -> context.batteryPercent?.let { it > condition.percent }
        is Condition.BatteryBelow -> context.batteryPercent?.let { it < condition.percent }
        Condition.IsCharging -> context.charging
        Condition.NetworkAvailable -> context.networkAvailable

        is Condition.BluetoothConnected ->
            if (!context.bluetoothKnown) {
                null
            } else {
                context.connectedBluetooth.any { it.equals(condition.device, ignoreCase = true) }
            }

        is Condition.JarvisMode -> context.jarvisMode?.let { it.equals(condition.mode, ignoreCase = true) }
        Condition.CalendarEventExists -> context.hasCalendarEvent

        is Condition.RuleNotExecutedRecently -> {
            val last = context.lastExecutionAt
            // Never executed satisfies "not executed recently".
            if (last == null) true
            else Duration.between(last, context.now).toMinutes() >= condition.minutes
        }
    }

    /** Handles windows that cross midnight, inclusive of both ends. */
    private fun inWindow(t: LocalTime, from: LocalTime, to: LocalTime): Boolean =
        if (from <= to) t >= from && t <= to else t >= from || t <= to

    /**
     * Lists the leaf conditions that were not satisfied, so the execution log
     * can say *which* test failed instead of just "conditions not met" (§21).
     * Unknown leaves are reported too — they are the interesting case.
     */
    fun unmetLeaves(condition: Condition?, context: EvaluationContext): List<Condition> {
        if (condition == null) return emptyList()
        return buildList { collectUnmet(condition, context, this) }
    }

    private fun collectUnmet(
        condition: Condition,
        context: EvaluationContext,
        out: MutableList<Condition>,
    ) {
        when (condition) {
            is Condition.All -> condition.of.forEach { collectUnmet(it, context, out) }
            is Condition.Any ->
                // An OR is only "unmet" as a whole; blaming one branch would be
                // misleading when the user offered alternatives.
                if (known(condition, context) != true) out += condition
            else -> if (known(condition, context) != true) out += condition
        }
    }
}
