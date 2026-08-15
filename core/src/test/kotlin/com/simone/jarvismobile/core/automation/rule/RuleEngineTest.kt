package com.simone.jarvismobile.core.automation.rule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the generic rule engine core (§26). The emphasis is on the cases
 * that decide whether an automation fires *once*, fires *at the wrong time*, or
 * fires *on a guess* — the three ways an automation betrays its user.
 */
class RuleEngineTest {

    // Thursday 6 August 2026, 22:14
    private val now = LocalDateTime.of(2026, 8, 6, 22, 14)
    private val ctx = EvaluationContext(now = now)

    private fun rule(
        id: String = "r1",
        cooldown: Long = 0,
        oneShot: Boolean = false,
        lastFired: LocalDateTime? = null,
        expires: LocalDateTime? = null,
        enabled: Boolean = true,
    ) = AutomationRule(
        id = id,
        name = "Test",
        enabled = enabled,
        triggers = listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING)),
        actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
        cooldownSeconds = cooldown,
        oneShot = oneShot,
        lastFiredAt = lastFired,
        expiresAt = expires,
    )

    // --- Conditions: AND / OR / NOT -------------------------------------

    @Test
    fun allRequiresEveryChild() {
        val c = Condition.All(listOf(Condition.IsCharging, Condition.NetworkAvailable))
        assertTrue(ConditionEvaluator.evaluate(c, ctx.copy(charging = true, networkAvailable = true)))
        assertFalse(ConditionEvaluator.evaluate(c, ctx.copy(charging = true, networkAvailable = false)))
    }

    @Test
    fun anyNeedsOnlyOneChild() {
        val c = Condition.Any(listOf(Condition.IsCharging, Condition.NetworkAvailable))
        assertTrue(ConditionEvaluator.evaluate(c, ctx.copy(charging = false, networkAvailable = true)))
        assertFalse(ConditionEvaluator.evaluate(c, ctx.copy(charging = false, networkAvailable = false)))
    }

    @Test
    fun emptyAllIsTrueAndEmptyAnyIsFalse() {
        assertTrue(ConditionEvaluator.evaluate(Condition.All(emptyList()), ctx))
        assertFalse(ConditionEvaluator.evaluate(Condition.Any(emptyList()), ctx))
    }

    @Test
    fun notInvertsAKnownResult() {
        val c = Condition.Not(Condition.IsCharging)
        assertTrue(ConditionEvaluator.evaluate(c, ctx.copy(charging = false)))
        assertFalse(ConditionEvaluator.evaluate(c, ctx.copy(charging = true)))
    }

    @Test
    fun nestedTreesCompose() {
        // weekdays AND (at home OR charging) AND NOT low battery
        val c = Condition.All(
            listOf(
                Condition.DayOfWeekIn(setOf(DayOfWeek.THURSDAY)),
                Condition.Any(listOf(Condition.CurrentPlace("home"), Condition.IsCharging)),
                Condition.Not(Condition.BatteryBelow(20)),
            ),
        )
        assertTrue(
            ConditionEvaluator.evaluate(
                c, ctx.copy(placeId = "home", charging = false, batteryPercent = 80),
            ),
        )
        assertFalse(
            ConditionEvaluator.evaluate(
                c, ctx.copy(placeId = "work", charging = false, batteryPercent = 80),
            ),
        )
    }

    // --- Unknown facts --------------------------------------------------

    @Test
    fun anUnknownFactIsNeverTrue() {
        // The phone cannot say where it is: "when I'm at home" must not hold.
        assertNull(ConditionEvaluator.known(Condition.CurrentPlace("home"), ctx))
        assertFalse(ConditionEvaluator.evaluate(Condition.CurrentPlace("home"), ctx))
    }

    @Test
    fun negatingAnUnknownStaysUnknownRatherThanBecomingTrue() {
        // The dangerous case: NOT(unknown) must not open the gate.
        val c = Condition.Not(Condition.CurrentPlace("home"))
        assertNull(ConditionEvaluator.known(c, ctx))
        assertFalse(ConditionEvaluator.evaluate(c, ctx))
    }

    @Test
    fun anUnknownBranchCannotSatisfyAnAnd() {
        val c = Condition.All(listOf(Condition.IsCharging, Condition.CurrentPlace("home")))
        assertFalse(ConditionEvaluator.evaluate(c, ctx.copy(charging = true)))
    }

    @Test
    fun aKnownTrueBranchStillSatisfiesAnOr() {
        val c = Condition.Any(listOf(Condition.IsCharging, Condition.CurrentPlace("home")))
        assertTrue(ConditionEvaluator.evaluate(c, ctx.copy(charging = true)))
    }

    // --- Time windows ---------------------------------------------------

    @Test
    fun timeWindowCrossingMidnightIsUnderstood() {
        val night = Condition.TimeRange(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(ConditionEvaluator.evaluate(night, ctx)) // 22:14
        assertTrue(ConditionEvaluator.evaluate(night, ctx.copy(now = now.withHour(3).withMinute(0))))
        assertFalse(ConditionEvaluator.evaluate(night, ctx.copy(now = now.withHour(12).withMinute(0))))
    }

    @Test
    fun ordinaryTimeWindowIsInclusive() {
        val work = Condition.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))
        assertTrue(ConditionEvaluator.evaluate(work, ctx.copy(now = now.withHour(9).withMinute(0))))
        assertTrue(ConditionEvaluator.evaluate(work, ctx.copy(now = now.withHour(18).withMinute(0))))
        assertFalse(ConditionEvaluator.evaluate(work, ctx.copy(now = now.withHour(18).withMinute(1))))
    }

    @Test
    fun ruleNotExecutedRecentlyCountsFromTheLastRun() {
        val c = Condition.RuleNotExecutedRecently(30)
        assertTrue(ConditionEvaluator.evaluate(c, ctx)) // never run
        assertFalse(ConditionEvaluator.evaluate(c, ctx.copy(lastExecutionAt = now.minusMinutes(10))))
        assertTrue(ConditionEvaluator.evaluate(c, ctx.copy(lastExecutionAt = now.minusMinutes(31))))
    }

    // --- Eligibility: cooldown, expiry, one-shot ------------------------

    @Test
    fun cooldownBlocksARefireAndThenReleasesIt() {
        val r = rule(cooldown = 600, lastFired = now.minusMinutes(5))
        assertFalse(r.isEligible(now), "fired 5 min ago with a 10 min cooldown")
        assertTrue(r.copy(lastFiredAt = now.minusMinutes(11)).isEligible(now))
    }

    @Test
    fun cooldownBoundaryIsInclusive() {
        val r = rule(cooldown = 600, lastFired = now.minusSeconds(600))
        assertTrue(r.isEligible(now), "exactly one cooldown later must be allowed")
    }

    @Test
    fun aRuleThatNeverFiredIsAlwaysPastItsCooldown() {
        assertTrue(rule(cooldown = 3600, lastFired = null).isEligible(now))
    }

    @Test
    fun expiredAndDisabledRulesAreNotEligible() {
        assertFalse(rule(expires = now.minusMinutes(1)).isEligible(now))
        assertFalse(rule(enabled = false).isEligible(now))
        assertTrue(rule(expires = now.plusMinutes(1)).isEligible(now))
    }

    @Test
    fun aRuleWithoutActionsOrTriggersCannotRun() {
        val noActions = rule().copy(actions = emptyList())
        val noTriggers = rule().copy(triggers = emptyList())
        assertFalse(noActions.isRunnable)
        assertFalse(noTriggers.isRunnable)
    }

    // --- Validation and capability honesty ------------------------------

    @Test
    fun aWellFormedRuleValidates() {
        assertEquals(emptyList(), rule().validationErrors())
    }

    @Test
    fun missingRequiredParametersAreReported() {
        val r = rule().copy(actions = listOf(ActionSpec(ActionRegistry.SPEAK)))
        assertTrue(r.validationErrors().any { it.contains("Testo") }, r.validationErrors().toString())
    }

    @Test
    fun unknownTriggerOrActionTypesAreRejected() {
        val r = rule().copy(
            triggers = listOf(TriggerSpec("TELEPORT")),
            actions = listOf(ActionSpec("LAUNCH_MISSILE")),
        )
        val errors = r.validationErrors()
        assertTrue(errors.any { it.contains("trigger sconosciuto") }, errors.toString())
        assertTrue(errors.any { it.contains("azione sconosciuta") }, errors.toString())
    }

    @Test
    fun unknownParametersAreRejectedRatherThanIgnored() {
        val r = rule().copy(
            actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao", "volume" to "9"))),
        )
        assertTrue(r.validationErrors().any { it.contains("volume") })
    }

    @Test
    fun aRuleArmedOnAnUnimplementedTriggerIsRefused() {
        // The trap the old ArrivedHome trigger fell into: selectable in the model,
        // impossible to fire in reality. Storing it would show the user an
        // "active" rule that can never run. PLACE_DWELL still has no dwell-tick
        // source, so it stands in for "declared but not deliverable yet".
        val r = rule().copy(
            triggers = listOf(TriggerSpec(TriggerRegistry.PLACE_DWELL, mapOf("placeId" to "home"))),
        )
        assertTrue(
            r.validationErrors().any { it.contains("non è ancora disponibile") },
            r.validationErrors().toString(),
        )
    }

    @Test
    fun theBuilderOnlyOffersWhatCanActuallyRun() {
        assertTrue(TriggerRegistry.available().all { it.implemented })
        assertTrue(ActionRegistry.available().all { it.implemented })
        assertTrue(TriggerRegistry.available().isNotEmpty())
        assertTrue(ActionRegistry.available().isNotEmpty())
    }

    @Test
    fun everyDefinitionDeclaresItsCapability() {
        // A trigger that needs a permission but forgets to say so would ask for
        // nothing and then silently never fire.
        val placeTriggers = TriggerRegistry.all.filter { it.type.startsWith("PLACE_") }
        assertTrue(placeTriggers.isNotEmpty())
        assertTrue(placeTriggers.all { it.requires == Capability.LOCATION_BACKGROUND })
        assertEquals(
            Capability.NOTIFICATION_LISTENER,
            TriggerRegistry.definition(TriggerRegistry.NOTIFICATION_MATCH)?.requires,
        )
    }

    @Test
    fun registryTypesAreUnique() {
        assertEquals(TriggerRegistry.all.size, TriggerRegistry.all.map { it.type }.toSet().size)
        assertEquals(ActionRegistry.all.size, ActionRegistry.all.map { it.type }.toSet().size)
    }

    // --- Risk classification --------------------------------------------

    @Test
    fun ruleRiskIsTheHighestOfItsActions() {
        val actions = listOf(
            ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao")),
            ActionSpec(ActionRegistry.DELETE_REMINDER, mapOf("id" to "x")),
        )
        assertEquals(ActionRisk.SENSITIVE, ActionRegistry.riskOf(actions))
    }

    @Test
    fun homeAssistantActionsAreExternalSideEffects() {
        assertEquals(
            ActionRisk.EXTERNAL_SIDE_EFFECT,
            ActionRegistry.definition(ActionRegistry.RUN_HOME_ASSISTANT_ACTION)?.risk,
        )
    }

    // --- Diagnostics -----------------------------------------------------

    @Test
    fun unmetLeavesExplainWhyARuleDidNotRun() {
        val c = Condition.All(
            listOf(
                Condition.IsCharging,
                Condition.BatteryAbove(50),
                Condition.DayOfWeekIn(setOf(DayOfWeek.THURSDAY)),
            ),
        )
        val unmet = ConditionEvaluator.unmetLeaves(c, ctx.copy(charging = false, batteryPercent = 80))
        assertEquals(listOf<Condition>(Condition.IsCharging), unmet)
    }

    @Test
    fun nothingIsUnmetWhenEverythingHolds() {
        val c = Condition.All(listOf(Condition.IsCharging))
        assertEquals(emptyList(), ConditionEvaluator.unmetLeaves(c, ctx.copy(charging = true)))
    }

    @Test
    fun aNegativeCooldownIsRejectedAtConstruction() {
        val failed = runCatching { rule().copy(cooldownSeconds = -1) }.isFailure
        assertTrue(failed, "a negative cooldown must not be constructible")
    }

    @Test
    fun onlyActionsWithARealHandlerAreDeclaredImplemented() {
        // The registry promises the Rule Builder what it can offer. An action
        // marked implemented but with no handler bound in AutomationEngineModule
        // would be selectable and then fail at 3am — the same trap the old
        // ArrivedHome trigger fell into. This pins the two lists together:
        // adding a handler and flipping the flag must happen in one change.
        val withHandlers = setOf(
            ActionRegistry.SHOW_NOTIFICATION,
            ActionRegistry.SPEAK,
            ActionRegistry.CREATE_REMINDER,
            ActionRegistry.RUN_TOOL,
        )
        assertEquals(
            withHandlers,
            ActionRegistry.available().map { it.type }.toSet(),
            "available actions must match the handlers bound in AutomationEngineModule",
        )
    }

    @Test
    fun theTriggersOfferedAreOnlyTheOnesWithoutExtraPlumbing() {
        // Activity, notification and place-dwell triggers need subsystems that do
        // not exist yet; they must not be offerable until they do. Place
        // enter/exit became offerable in phase 6 (geofencing via addProximityAlert).
        val offered = TriggerRegistry.available().map { it.type }.toSet()
        assertTrue(TriggerRegistry.ACTIVITY_ENTER !in offered)
        assertTrue(TriggerRegistry.NOTIFICATION_MATCH !in offered)
        assertTrue(TriggerRegistry.PLACE_DWELL !in offered)
        assertTrue(TriggerRegistry.PLACE_ENTER in offered)
        assertTrue(TriggerRegistry.PLACE_EXIT in offered)
        assertTrue(TriggerRegistry.DEVICE_CHARGING in offered)
        assertTrue(TriggerRegistry.RECURRING_TIME in offered)
    }
}
