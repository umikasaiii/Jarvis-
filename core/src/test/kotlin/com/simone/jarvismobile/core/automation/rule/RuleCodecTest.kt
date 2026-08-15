package com.simone.jarvismobile.core.automation.rule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage format is where user automations are actually kept, so these
 * tests care about two things: that a rule survives a round trip unchanged, and
 * that a damaged payload is refused rather than half-read. Half-reading is the
 * dangerous outcome — a rule that comes back missing a condition would fire in
 * situations the user explicitly excluded.
 */
class RuleCodecTest {

    private val now = LocalDateTime.of(2026, 8, 6, 22, 14)

    private fun roundTrip(rule: AutomationRule): AutomationRule? = RuleCodec.decodeRule(
        id = rule.id,
        name = rule.name,
        enabled = rule.enabled,
        triggersJson = RuleCodec.encodeTriggers(rule.triggers),
        conditionJson = RuleCodec.encodeCondition(rule.condition),
        actionsJson = RuleCodec.encodeActions(rule.actions),
        priority = rule.priority,
        executionPolicy = rule.executionPolicy.name,
        cooldownSeconds = rule.cooldownSeconds,
        oneShot = rule.oneShot,
        expiresAt = rule.expiresAt?.toString(),
        lastFiredAt = rule.lastFiredAt?.toString(),
        createdAt = rule.createdAt?.toString(),
        updatedAt = rule.updatedAt?.toString(),
        schemaVersion = rule.schemaVersion,
    )

    private val fullRule = AutomationRule(
        id = "abc123",
        name = "Sera a casa",
        enabled = true,
        triggers = listOf(
            TriggerSpec(TriggerRegistry.RECURRING_TIME, mapOf("time" to "22:00", "days" to "MONDAY,FRIDAY")),
            TriggerSpec(TriggerRegistry.DEVICE_CHARGING),
        ),
        condition = Condition.All(
            listOf(
                Condition.DayOfWeekIn(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
                Condition.Any(listOf(Condition.CurrentPlace("home"), Condition.IsCharging)),
                Condition.Not(Condition.BatteryBelow(15)),
                Condition.TimeRange(LocalTime.of(22, 0), LocalTime.of(6, 0)),
                Condition.RuleNotExecutedRecently(30),
            ),
        ),
        actions = listOf(
            ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "Buonasera")),
            ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to "Modalità sera")),
        ),
        priority = AutomationRule.PRIORITY_HIGH,
        executionPolicy = ExecutionPolicy.QUEUE,
        cooldownSeconds = 600,
        oneShot = false,
        expiresAt = now.plusDays(30),
        lastFiredAt = now.minusHours(2),
        createdAt = now.minusDays(1),
        updatedAt = now,
    )

    @Test
    fun aCompleteRuleSurvivesARoundTrip() {
        assertEquals(fullRule, roundTrip(fullRule))
    }

    @Test
    fun aMinimalRuleSurvivesARoundTrip() {
        val minimal = AutomationRule(
            id = "m1",
            name = "Minima",
            triggers = listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING)),
            actions = listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ok"))),
        )
        assertEquals(minimal, roundTrip(minimal))
    }

    @Test
    fun everyConditionKindRoundTrips() {
        val kinds: List<Condition> = listOf(
            Condition.DayOfWeekIn(setOf(DayOfWeek.SUNDAY)),
            Condition.TimeRange(LocalTime.of(9, 30), LocalTime.of(18, 0)),
            Condition.CurrentPlace("work"),
            Condition.ActivityType("IN_VEHICLE"),
            Condition.BatteryAbove(50),
            Condition.BatteryBelow(20),
            Condition.IsCharging,
            Condition.NetworkAvailable,
            Condition.BluetoothConnected("Casco"),
            Condition.JarvisMode("GUIDA"),
            Condition.CalendarEventExists,
            Condition.RuleNotExecutedRecently(45),
            Condition.RainToday,
            Condition.RainTomorrow,
        )
        for (c in kinds) {
            assertEquals(c, RuleCodec.decodeCondition(RuleCodec.encodeCondition(c)), "round trip: $c")
        }
    }

    @Test
    fun deeplyNestedTreesSurvive() {
        val nested = Condition.All(
            listOf(
                Condition.Not(
                    Condition.Any(
                        listOf(
                            Condition.All(listOf(Condition.IsCharging, Condition.NetworkAvailable)),
                            Condition.CurrentPlace("home"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(nested, RuleCodec.decodeCondition(RuleCodec.encodeCondition(nested)))
    }

    @Test
    fun anAbsentConditionStaysAbsent() {
        val encoded = RuleCodec.encodeCondition(null)
        assertNull(RuleCodec.decodeCondition(encoded))
        assertTrue(RuleCodec.isDecodableCondition(encoded))
        assertTrue(RuleCodec.isDecodableCondition(null))
        assertTrue(RuleCodec.isDecodableCondition(""))
    }

    @Test
    fun paramsWithAwkwardCharactersSurvive() {
        val spec = TriggerSpec(
            TriggerRegistry.NOTIFICATION_MATCH,
            mapOf("app" to "Revolut", "contains" to """Pagamento "da 12,50 €" — ok\n"""),
        )
        assertEquals(listOf(spec), RuleCodec.decodeTriggers(RuleCodec.encodeTriggers(listOf(spec))))
    }

    // --- Refusing damaged payloads --------------------------------------

    @Test
    fun anUnknownConditionKindIsRefusedNotSkipped() {
        // The dangerous case: silently dropping a condition would widen the rule.
        val raw = """{"t":"MOON_PHASE","phase":"full"}"""
        assertNull(RuleCodec.decodeCondition(raw))
        assertFalse(RuleCodec.isDecodableCondition(raw))
    }

    @Test
    fun anUnknownConditionInsideAnAndRefusesTheWholeTree() {
        val raw = """{"t":"ALL","of":[{"t":"IS_CHARGING"},{"t":"MOON_PHASE"}]}"""
        assertFalse(RuleCodec.isDecodableCondition(raw))
        assertNull(RuleCodec.decodeCondition(raw))
    }

    @Test
    fun malformedJsonIsRefusedWithoutThrowing() {
        assertNull(RuleCodec.decodeTriggers("{not json"))
        assertNull(RuleCodec.decodeActions("[[["))
        assertFalse(RuleCodec.isDecodableCondition("{oops"))
    }

    @Test
    fun aRuleWithAnUnreadableConditionIsQuarantinedNotLoadedWithoutIt() {
        val decoded = RuleCodec.decodeRule(
            id = "x", name = "Rotta", enabled = true,
            triggersJson = RuleCodec.encodeTriggers(listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING))),
            conditionJson = """{"t":"MOON_PHASE"}""",
            actionsJson = RuleCodec.encodeActions(
                listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
            ),
            priority = 50, executionPolicy = "SKIP_IF_RUNNING", cooldownSeconds = 0, oneShot = false,
            expiresAt = null, lastFiredAt = null, createdAt = null, updatedAt = null, schemaVersion = 1,
        )
        assertNull(decoded, "a rule whose condition cannot be read must not load unconditioned")
    }

    @Test
    fun anUnknownExecutionPolicyFallsBackToTheSafestOne() {
        val decoded = assertNotNull(
            RuleCodec.decodeRule(
                id = "x", name = "N", enabled = true,
                triggersJson = RuleCodec.encodeTriggers(listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING))),
                conditionJson = null,
                actionsJson = RuleCodec.encodeActions(
                    listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
                ),
                priority = 50, executionPolicy = "PARALLEL_CHAOS", cooldownSeconds = 0, oneShot = false,
                expiresAt = null, lastFiredAt = null, createdAt = null, updatedAt = null, schemaVersion = 1,
            ),
        )
        assertEquals(ExecutionPolicy.SKIP_IF_RUNNING, decoded.executionPolicy)
    }

    @Test
    fun unreadableTimestampsDoNotDiscardTheWholeRule() {
        // A bad timestamp is recoverable — losing the rule would be worse.
        val decoded = assertNotNull(
            RuleCodec.decodeRule(
                id = "x", name = "N", enabled = true,
                triggersJson = RuleCodec.encodeTriggers(listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING))),
                conditionJson = null,
                actionsJson = RuleCodec.encodeActions(
                    listOf(ActionSpec(ActionRegistry.SPEAK, mapOf("message" to "ciao"))),
                ),
                priority = 50, executionPolicy = "SKIP_IF_RUNNING", cooldownSeconds = 0, oneShot = false,
                expiresAt = "ieri", lastFiredAt = "boh", createdAt = null, updatedAt = null, schemaVersion = 1,
            ),
        )
        assertNull(decoded.expiresAt)
        assertNull(decoded.lastFiredAt)
    }

    @Test
    fun unknownFieldsInStoredJsonAreToleratedForForwardCompatibility() {
        // A rule written by a newer build must still load in an older one.
        val raw = """[{"type":"DEVICE_CHARGING","params":{},"futureField":123}]"""
        assertEquals(listOf(TriggerSpec(TriggerRegistry.DEVICE_CHARGING)), RuleCodec.decodeTriggers(raw))
    }

    @Test
    fun theFormatVersionIsPinned() {
        // A change here must be a conscious decision accompanied by a migration.
        assertEquals(1, RuleCodec.VERSION)
        assertEquals(1, AutomationRule.SCHEMA_VERSION)
    }
}
