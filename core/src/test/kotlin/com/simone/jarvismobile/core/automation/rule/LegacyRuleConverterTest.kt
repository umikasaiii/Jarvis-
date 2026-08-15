package com.simone.jarvismobile.core.automation.rule

import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.Trigger
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The migration runs once, silently, over automations the user actually wrote.
 * If it gets a time wrong nobody notices until the rule misfires at 3am, so the
 * cases below check the exact values carried across — and check that anything
 * without a faithful equivalent is refused rather than approximated.
 */
class LegacyRuleConverterTest {

    private val now = LocalDateTime.of(2026, 8, 6, 22, 14)

    private fun legacy(trigger: Trigger, action: Action = Action.Speak("ciao")) =
        Automation(id = "old1", name = "Vecchia", trigger = trigger, action = action)

    @Test
    fun aDailyTimeRuleKeepsItsExactTimeAndDays() {
        val old = legacy(
            Trigger.TimeOfDay(LocalTime.of(8, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
        )
        val new = assertNotNull(LegacyRuleConverter.convert(old, now))
        val trigger = new.triggers.single()
        assertEquals(TriggerRegistry.RECURRING_TIME, trigger.type)
        assertEquals("08:30", trigger.param("time"))
        assertEquals("MONDAY,FRIDAY", trigger.param("days"))
    }

    @Test
    fun anEveryDayRuleCarriesNoDayFilter() {
        val new = assertNotNull(
            LegacyRuleConverter.convert(legacy(Trigger.TimeOfDay(LocalTime.of(7, 0))), now),
        )
        assertNull(new.triggers.single().param("days"))
    }

    @Test
    fun aOneOffTriggerBecomesAOneShotRule() {
        val at = LocalDateTime.of(2026, 8, 8, 11, 45)
        val new = assertNotNull(LegacyRuleConverter.convert(legacy(Trigger.Once(at)), now))
        assertEquals(TriggerRegistry.TIME_AT, new.triggers.single().type)
        assertEquals(at.toString(), new.triggers.single().param("at"))
        assertTrue(new.oneShot, "a one-off rule must not become recurring")
    }

    @Test
    fun theOtherSupportedTriggersMapAcross() {
        assertEquals(
            TriggerRegistry.DEVICE_CHARGING,
            LegacyRuleConverter.convert(legacy(Trigger.ChargingStarted), now)?.triggers?.single()?.type,
        )
        val unlock = assertNotNull(
            LegacyRuleConverter.convert(legacy(Trigger.MorningUnlock(LocalTime.of(6, 30))), now),
        )
        assertEquals(TriggerRegistry.FIRST_UNLOCK_OF_DAY, unlock.triggers.single().type)
        assertEquals("06:30", unlock.triggers.single().param("after"))

        val bt = assertNotNull(
            LegacyRuleConverter.convert(legacy(Trigger.BluetoothConnected("Casco")), now),
        )
        assertEquals("Casco", bt.triggers.single().param("device"))
    }

    @Test
    fun everyActionKindMapsAcross() {
        val cases = mapOf(
            Action.Notify("n") to ActionRegistry.SHOW_NOTIFICATION,
            Action.Speak("s") to ActionRegistry.SPEAK,
            Action.AddAgenda("a") to ActionRegistry.CREATE_REMINDER,
            Action.Tool("accendi la torcia") to ActionRegistry.RUN_TOOL,
        )
        for ((action, expected) in cases) {
            val new = assertNotNull(
                LegacyRuleConverter.convert(legacy(Trigger.ChargingStarted, action), now),
            )
            assertEquals(expected, new.actions.single().type, "action $action")
        }
    }

    @Test
    fun theToolCommandIsCarriedVerbatim() {
        val new = assertNotNull(
            LegacyRuleConverter.convert(
                legacy(Trigger.ChargingStarted, Action.Tool("accendi la torcia")), now,
            ),
        )
        assertEquals("accendi la torcia", new.actions.single().param("command"))
    }

    @Test
    fun identityAndStateAreCarried() {
        val old = Automation(
            id = "keepme", name = "Sveglia", enabled = false,
            trigger = Trigger.ChargingStarted, action = Action.Speak("ciao"),
            lastFired = now.minusDays(1),
        )
        val new = assertNotNull(LegacyRuleConverter.convert(old, now))
        assertEquals("keepme", new.id, "the id must survive so history still matches")
        assertEquals("Sveglia", new.name)
        assertTrue(!new.enabled, "a disabled rule must not wake up enabled")
        assertEquals(now.minusDays(1), new.lastFiredAt, "losing lastFired could re-fire a daily rule")
    }

    @Test
    fun triggersWithoutAFaithfulEquivalentAreRefused() {
        val unsupported = listOf(
            Trigger.BatteryBelow(20),
            Trigger.ScreenUnlocked,
            Trigger.HeadphonesConnected,
            Trigger.AirplaneMode(true),
            Trigger.WifiPower(true),
            Trigger.MobileData(false),
            Trigger.WifiNetwork("CasaWifi"),
            Trigger.ArrivedHome,
        )
        for (t in unsupported) {
            assertNull(LegacyRuleConverter.convert(legacy(t), now), "must not approximate: $t")
            assertNotNull(LegacyRuleConverter.reasonForSkipping(legacy(t)), "must explain: $t")
        }
    }

    @Test
    fun arrivedHomeIsRefusedBecauseItNeverWorked() {
        // It exists in the old model but no geofencing exists in the app, so it
        // could never fire. Migrating it would carry a broken rule forward.
        val reason = assertNotNull(LegacyRuleConverter.reasonForSkipping(legacy(Trigger.ArrivedHome)))
        assertTrue(reason.contains("arrivo a casa"), reason)
    }

    @Test
    fun aSupportedRuleNeedsNoExplanation() {
        assertNull(LegacyRuleConverter.reasonForSkipping(legacy(Trigger.ChargingStarted)))
    }

    @Test
    fun convertedRulesPassValidation() {
        // A migration that produced rules the engine then refuses to store would
        // lose them just as surely as not migrating at all.
        val supported = listOf(
            Trigger.TimeOfDay(LocalTime.of(8, 0)),
            Trigger.Once(now.plusHours(2)),
            Trigger.ChargingStarted,
            Trigger.MorningUnlock(LocalTime.of(7, 0)),
            Trigger.BluetoothConnected("Casco"),
        )
        for (t in supported) {
            val new = assertNotNull(LegacyRuleConverter.convert(legacy(t), now))
            assertEquals(emptyList(), new.validationErrors(), "invalid after conversion: $t")
        }
    }

    @Test
    fun convertedRulesSurviveTheStorageRoundTrip() {
        val new = assertNotNull(
            LegacyRuleConverter.convert(
                legacy(Trigger.TimeOfDay(LocalTime.of(8, 30), setOf(DayOfWeek.MONDAY))), now,
            ),
        )
        val decoded = RuleCodec.decodeRule(
            id = new.id, name = new.name, enabled = new.enabled,
            triggersJson = RuleCodec.encodeTriggers(new.triggers),
            conditionJson = RuleCodec.encodeCondition(new.condition),
            actionsJson = RuleCodec.encodeActions(new.actions),
            priority = new.priority, executionPolicy = new.executionPolicy.name,
            cooldownSeconds = new.cooldownSeconds, oneShot = new.oneShot,
            expiresAt = null, lastFiredAt = null,
            createdAt = new.createdAt?.toString(), updatedAt = new.updatedAt?.toString(),
            schemaVersion = new.schemaVersion,
        )
        assertEquals(new.copy(lastFiredAt = null), decoded)
    }
}
