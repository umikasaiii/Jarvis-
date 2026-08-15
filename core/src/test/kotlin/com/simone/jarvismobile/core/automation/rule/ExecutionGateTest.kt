package com.simone.jarvismobile.core.automation.rule

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate decides whether a rule runs, so these tests are about the three ways
 * an automation betrays its user: doing the thing twice, doing it at the wrong
 * moment, and doing it when a condition could not actually be checked.
 */
class ExecutionGateTest {

    private val now = LocalDateTime.of(2026, 8, 6, 22, 14)

    private fun rule(
        id: String = "r1",
        cooldown: Long = 0,
        condition: Condition? = null,
        enabled: Boolean = true,
        priority: Int = AutomationRule.PRIORITY_NORMAL,
        actions: List<ActionSpec> = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ok"))),
        triggerType: String = TriggerRegistry.DEVICE_CHARGING,
        policy: ExecutionPolicy = ExecutionPolicy.SKIP_IF_RUNNING,
    ) = AutomationRule(
        id = id,
        name = "Test $id",
        enabled = enabled,
        triggers = listOf(TriggerSpec(triggerType)),
        condition = condition,
        actions = actions,
        priority = priority,
        cooldownSeconds = cooldown,
        executionPolicy = policy,
    )

    private fun event(at: LocalDateTime = now, key: String = "") =
        TriggerEvent(TriggerRegistry.DEVICE_CHARGING, at, key)

    private val ctx = EvaluationContext(now = now)

    @Test
    fun aReadyRuleFires() {
        val result = RuleGate.evaluate(rule(), event(), ctx)
        assertEquals(ExecutionDecision.FIRE, result.decision)
    }

    // --- Duplicate suppression ------------------------------------------

    @Test
    fun theSameEventDeliveredTwiceRunsOnlyOnce() {
        // §11's headline failure: one happening, several deliveries, N actions.
        val r = rule()
        val first = event(key = "home")
        val history = ExecutionHistory(recentKeys = mapOf(first.idempotencyKey(r.id) to first.at))
        val second = RuleGate.evaluate(r, event(at = now.plusSeconds(5), key = "home"), ctx, history)
        assertEquals(ExecutionDecision.SKIP_DUPLICATE, second.decision)
    }

    @Test
    fun theSameEventAfterTheDedupWindowIsANewOccurrence() {
        val r = rule()
        val first = event(key = "home")
        val history = ExecutionHistory(recentKeys = mapOf(first.idempotencyKey(r.id) to first.at))
        val later = RuleGate.evaluate(r, event(at = now.plusMinutes(5), key = "home"), ctx, history)
        assertEquals(ExecutionDecision.FIRE, later.decision)
    }

    @Test
    fun differentOccurrencesOfTheSameTriggerBothFire() {
        // Arriving at two different places must not collapse into one event.
        val r = rule()
        val home = event(key = "home")
        val history = ExecutionHistory(recentKeys = mapOf(home.idempotencyKey(r.id) to home.at))
        val gym = RuleGate.evaluate(r, event(at = now.plusSeconds(5), key = "gym"), ctx, history)
        assertEquals(ExecutionDecision.FIRE, gym.decision)
    }

    @Test
    fun anEventForAnotherRuleDoesNotSuppressThisOne() {
        val mine = rule(id = "mine")
        val theirs = event(key = "home").idempotencyKey("theirs")
        val history = ExecutionHistory(recentKeys = mapOf(theirs to now))
        val result = RuleGate.evaluate(mine, event(key = "home"), ctx, history)
        assertEquals(ExecutionDecision.FIRE, result.decision)
    }

    @Test
    fun aRunAlreadyInProgressIsNotStartedAgainUnderTheDefaultPolicy() {
        val result = RuleGate.evaluate(rule(), event(), ctx, ExecutionHistory(running = true))
        assertEquals(ExecutionDecision.SKIP_DUPLICATE, result.decision)
    }

    @Test
    fun aQueuePolicyAllowsAConcurrentFiring() {
        val result = RuleGate.evaluate(
            rule(policy = ExecutionPolicy.QUEUE), event(), ctx, ExecutionHistory(running = true),
        )
        assertEquals(ExecutionDecision.FIRE, result.decision)
    }

    // --- Cooldown --------------------------------------------------------

    @Test
    fun theCooldownIsMeasuredFromTheRecordedHistoryNotOnlyTheRule() {
        val r = rule(cooldown = 600)
        val history = ExecutionHistory(lastFiredAt = now.minusMinutes(2))
        assertEquals(
            ExecutionDecision.SKIP_COOLDOWN,
            RuleGate.evaluate(r, event(), ctx, history).decision,
        )
    }

    @Test
    fun duplicateSuppressionIsReportedBeforeCooldown() {
        // The reason matters: "already handled" and "too soon" mean different
        // things to someone reading the log to understand a missing action.
        val r = rule(cooldown = 3600)
        val e = event(key = "home")
        val history = ExecutionHistory(
            lastFiredAt = now.minusSeconds(1),
            recentKeys = mapOf(e.idempotencyKey(r.id) to now.minusSeconds(1)),
        )
        assertEquals(ExecutionDecision.SKIP_DUPLICATE, RuleGate.evaluate(r, e, ctx, history).decision)
    }

    // --- Conditions ------------------------------------------------------

    @Test
    fun unmetConditionsBlockTheRuleAndAreNamed() {
        val r = rule(condition = Condition.IsCharging)
        val result = RuleGate.evaluate(r, event(), ctx.copy(charging = false))
        assertEquals(ExecutionDecision.SKIP_CONDITIONS, result.decision)
        assertEquals(listOf<Condition>(Condition.IsCharging), result.unmet)
    }

    @Test
    fun anUncheckableConditionBlocksTheRule() {
        // The sensor cannot answer: the rule must not fire on a guess.
        val r = rule(condition = Condition.CurrentPlace("home"))
        val result = RuleGate.evaluate(r, event(), ctx)
        assertEquals(ExecutionDecision.SKIP_CONDITIONS, result.decision)
    }

    // --- State and capability --------------------------------------------

    @Test
    fun disabledExpiredAndIncompleteRulesAreDistinguished() {
        assertEquals(
            ExecutionDecision.SKIP_DISABLED,
            RuleGate.evaluate(rule(enabled = false), event(), ctx).decision,
        )
        assertEquals(
            ExecutionDecision.SKIP_EXPIRED,
            RuleGate.evaluate(rule().copy(expiresAt = now.minusMinutes(1)), event(), ctx).decision,
        )
        assertEquals(
            ExecutionDecision.SKIP_NOT_RUNNABLE,
            RuleGate.evaluate(rule().copy(actions = emptyList()), event(), ctx).decision,
        )
    }

    @Test
    fun aRuleDoesNotFireForATriggerItDoesNotListenFor() {
        val r = rule(triggerType = TriggerRegistry.DEVICE_CHARGING)
        val other = TriggerEvent(TriggerRegistry.DEVICE_UNPLUGGED, now)
        assertEquals(ExecutionDecision.SKIP_NOT_RUNNABLE, RuleGate.evaluate(r, other, ctx).decision)
    }

    @Test
    fun aMissingCapabilityStopsTheRuleWithAnHonestReason() {
        val r = rule(actions = listOf(ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to "x"))))
        val result = RuleGate.evaluate(r, event(), ctx) { capability ->
            if (capability == Capability.POST_NOTIFICATIONS) CapabilityStatus.REQUIRES_PERMISSION
            else CapabilityStatus.SUPPORTED
        }
        assertEquals(ExecutionDecision.SKIP_CAPABILITY, result.decision)
        assertTrue(result.reason.contains("POST_NOTIFICATIONS"), result.reason)
    }

    @Test
    fun capabilityIsCheckedBeforeConditionsSoTheReasonIsNotMisleading() {
        val r = rule(
            condition = Condition.IsCharging,
            actions = listOf(ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to "x"))),
        )
        val result = RuleGate.evaluate(r, event(), ctx.copy(charging = false)) {
            CapabilityStatus.UNSUPPORTED
        }
        assertEquals(ExecutionDecision.SKIP_CAPABILITY, result.decision)
    }

    @Test
    fun missingCapabilitiesAreListedForDiagnostics() {
        val r = rule(
            triggerType = TriggerRegistry.BLUETOOTH_CONNECTED,
            actions = listOf(ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to "x"))),
        )
        val missing = RuleGate.missingCapabilities(r) { CapabilityStatus.REQUIRES_PERMISSION }
        assertTrue(Capability.BLUETOOTH in missing)
        assertTrue(Capability.POST_NOTIFICATIONS in missing)
        assertTrue(Capability.NONE !in missing)
    }

    // --- Conflicts --------------------------------------------------------

    private fun modeRule(id: String, priority: Int, mode: String) = rule(
        id = id,
        priority = priority,
        actions = listOf(ActionSpec(ActionRegistry.SET_JARVIS_MODE, mapOf("mode" to mode))),
    )

    @Test
    fun twoRulesClaimingTheModeAreResolvedByPriority() {
        // The flapping case from §12: one wants silent, the other wants ringing.
        val resolution = ConflictResolver.resolve(
            listOf(
                modeRule("quiet", AutomationRule.PRIORITY_NORMAL, "SILENZIOSA"),
                modeRule("drive", AutomationRule.PRIORITY_HIGH, "GUIDA"),
            ),
        )
        assertEquals(listOf("drive"), resolution.winners.map { it.id })
        assertEquals(1, resolution.losers.size)
        assertEquals("drive", resolution.losers.single().winnerId)
        assertEquals(ActionRegistry.RESOURCE_MODE, resolution.losers.single().resource)
    }

    @Test
    fun rulesTouchingDifferentResourcesAllWin() {
        val resolution = ConflictResolver.resolve(
            listOf(
                modeRule("mode", AutomationRule.PRIORITY_NORMAL, "CASA"),
                rule(id = "speak"),
                rule(
                    id = "wake",
                    actions = listOf(ActionSpec(ActionRegistry.START_WAKE_WORD_MODE)),
                ),
            ),
        )
        assertEquals(3, resolution.winners.size)
        assertTrue(resolution.losers.isEmpty())
    }

    @Test
    fun aTieIsBrokenStablyRatherThanByListOrder() {
        val a = modeRule("aaa", AutomationRule.PRIORITY_NORMAL, "A")
        val b = modeRule("bbb", AutomationRule.PRIORITY_NORMAL, "B")
        val one = ConflictResolver.resolve(listOf(a, b))
        val other = ConflictResolver.resolve(listOf(b, a))
        assertEquals(one.winners.map { it.id }, other.winners.map { it.id })
        assertEquals(listOf("aaa"), one.winners.map { it.id })
    }

    @Test
    fun asingleCandidateNeedsNoResolution() {
        val resolution = ConflictResolver.resolve(listOf(rule()))
        assertEquals(1, resolution.winners.size)
        assertTrue(resolution.losers.isEmpty())
    }

    @Test
    fun theLoserIsReportedSoTheLogCanExplainIt() {
        val resolution = ConflictResolver.resolve(
            listOf(
                modeRule("low", AutomationRule.PRIORITY_LOW, "CASA"),
                modeRule("high", AutomationRule.PRIORITY_CRITICAL, "GUIDA"),
            ),
        )
        val loser = resolution.losers.single()
        assertEquals("low", loser.rule.id)
        assertEquals("high", loser.winnerId)
    }
}
