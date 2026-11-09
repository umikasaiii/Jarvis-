package com.simone.jarvismobile.core.reliability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmTimeTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun sameWallClockIsADifferentInstantInAnotherZone() {
        val t = LocalDateTime.of(2026, 11, 10, 7, 30)
        assertTrue(AlarmTime.epochMs(t, rome) != AlarmTime.epochMs(t, tokyo))
        assertEquals(t, AlarmTime.wallClock(AlarmTime.epochMs(t, tokyo), tokyo))
    }

    @Test fun anAlarmBookedInOneZoneIsStaleAfterTheZoneChanges() {
        val t = LocalDateTime.of(2026, 11, 10, 7, 30)
        val booked = AlarmTime.epochMs(t, rome)
        assertFalse(AlarmTime.isStale(booked, t, rome))
        assertTrue(AlarmTime.isStale(booked, t, tokyo))
    }

    @Test fun springForwardGapResolvesToTheFirstInstantAfterTheGap() {
        // Europe/Rome 2026-03-29: 02:00 -> 03:00, so 02:30 does not exist.
        val missing = LocalDateTime.of(2026, 3, 29, 2, 30)
        val ms = AlarmTime.epochMs(missing, rome)
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), AlarmTime.wallClock(ms, rome))
    }

    @Test fun autumnOverlapResolvesToTheEarlierInstant() {
        // Europe/Rome 2026-10-25: 03:00 -> 02:00, so 02:30 happens twice.
        val twice = LocalDateTime.of(2026, 10, 25, 2, 30)
        val ms = AlarmTime.epochMs(twice, rome)
        val later = ms + Duration.ofHours(1).toMillis()
        assertEquals(twice, AlarmTime.wallClock(ms, rome))
        assertEquals(twice, AlarmTime.wallClock(later, rome)) // both instants read 02:30
        assertTrue(ms < later)
    }

    @Test fun aDailyAlarmKeepsItsWallClockAcrossTheDstSwitch() {
        val before = LocalDateTime.of(2026, 3, 28, 7, 0)
        val after = LocalDateTime.of(2026, 3, 29, 7, 0)
        val gap = AlarmTime.epochMs(after, rome) - AlarmTime.epochMs(before, rome)
        assertEquals(Duration.ofHours(23).toMillis(), gap) // 23h day, but still 07:00 on the clock
    }
}

class RearmRunnerTest {
    private fun ok(): suspend () -> Unit = {}
    private val all: Map<RearmStep, suspend () -> Unit> = RearmStep.values().associateWith { ok() }

    @Test fun bootRunsEveryStepAndReportsThem() = runTest {
        val report = RearmRunner().run(RearmTrigger.BOOT, all)
        assertEquals(RearmStep.values().toList(), report.results.map { it.step })
        assertTrue(report.allOk)
    }

    @Test fun oneFailingStepNeverSkipsTheOthers() = runTest {
        val order = mutableListOf<RearmStep>()
        val actions = RearmStep.values().associateWith<RearmStep, suspend () -> Unit> { s ->
            { order += s; if (s == RearmStep.AGENDA_REMINDERS) error("agenda unreadable") }
        }
        val report = RearmRunner().run(RearmTrigger.BOOT, actions)
        assertEquals(RearmStep.values().toList(), order)
        assertEquals(listOf(RearmStep.AGENDA_REMINDERS), report.failedSteps)
        assertEquals("IllegalStateException", report.results.first().error)
        assertFalse(report.allOk)
        // the briefing alarms (last step) were still re-armed
        assertEquals(StepStatus.OK, report.results.last().status)
    }

    @Test fun aHangingStepTimesOutAndTheRestStillRun() = runTest {
        val actions = RearmStep.values().associateWith<RearmStep, suspend () -> Unit> { s ->
            { if (s == RearmStep.RULES) delay(60_000) }
        }
        val report = RearmRunner(stepTimeoutMs = 1_000).run(RearmTrigger.BOOT, actions)
        assertEquals(StepStatus.TIMED_OUT, report.results.first { it.step == RearmStep.RULES }.status)
        assertEquals(StepStatus.OK, report.results.first { it.step == RearmStep.PROACTIVE }.status)
    }

    @Test fun timeZoneChangeSkipsGeofencesButRearmsClockAlarms() = runTest {
        val report = RearmRunner().run(RearmTrigger.TIMEZONE_CHANGED, all)
        assertFalse(report.results.any { it.step == RearmStep.PLACES })
        assertTrue(report.results.any { it.step == RearmStep.PROACTIVE })
        assertTrue(RearmPlan.stepsFor(RearmTrigger.PACKAGE_REPLACED).contains(RearmStep.PLACES))
    }

    @Test fun aMissingActionIsReportedAsSkippedNotAsSuccess() = runTest {
        val report = RearmRunner().run(RearmTrigger.BOOT, all - RearmStep.PLACES)
        assertEquals(StepStatus.SKIPPED, report.results.first { it.step == RearmStep.PLACES }.status)
    }

    @Test fun cancellationOfTheCallerIsNotSwallowed() = runTest {
        val actions = mapOf<RearmStep, suspend () -> Unit>(RearmStep.AGENDA_REMINDERS to { throw CancellationException("stop") })
        assertFailsWith<CancellationException> { RearmRunner().run(RearmTrigger.BOOT, actions) }
    }

    @Test fun summaryHasNoUserContent() = runTest {
        val s = RearmRunner(clock = { 42L }).run(RearmTrigger.BOOT, all).summary()
        assertTrue(s.startsWith("trigger=BOOT AGENDA_REMINDERS=OK"))
    }
}

class BoundedWorkTest {
    @Test fun successorIsRearmedAfterNormalCompletion() = runTest {
        var rearmed = false
        val out = BoundedWork.runThenAlways(1_000, { }, { rearmed = true })
        assertEquals(WorkOutcome.COMPLETED, out); assertTrue(rearmed)
    }

    @Test fun successorIsRearmedWhenTheWorkThrows() = runTest {
        var rearmed = false
        val out = BoundedWork.runThenAlways(1_000, { error("boom") }, { rearmed = true })
        assertEquals(WorkOutcome.FAILED, out); assertTrue(rearmed)
    }

    @Test fun successorIsRearmedWhenTheWorkHangs() = runTest {
        var rearmed = false
        val out = BoundedWork.runThenAlways(1_000, { delay(3_600_000) }, { rearmed = true })
        assertEquals(WorkOutcome.TIMED_OUT, out); assertTrue(rearmed)
    }

    @Test fun callerCancellationPropagatesWithoutRearm() = runTest {
        var rearmed = false
        assertFailsWith<CancellationException> {
            BoundedWork.runThenAlways(1_000, { throw CancellationException("x") }, { rearmed = true })
        }
        assertFalse(rearmed)
    }
}

class MissedReminderPolicyTest {
    private val now = LocalDateTime.of(2026, 11, 10, 8, 10)
    private fun c(k: String, t: LocalDateTime) = ReminderCandidate(k, t)

    @Test fun aReminderMissedWhileThePhoneWasOffIsSelected() {
        val r = MissedReminderPolicy.select(listOf(c("a", now.minusMinutes(10))), emptySet(), now)
        assertEquals(listOf("a"), r.map { it.key })
    }

    @Test fun futureAndAlreadyDeliveredAreNotSelected() {
        val r = MissedReminderPolicy.select(listOf(c("f", now.plusMinutes(5)), c("d", now.minusMinutes(5))), setOf("d"), now)
        assertTrue(r.isEmpty())
    }

    @Test fun tooOldIsIgnored() {
        assertTrue(MissedReminderPolicy.select(listOf(c("old", now.minusHours(13))), emptySet(), now).isEmpty())
        assertEquals(1, MissedReminderPolicy.select(listOf(c("edge", now.minusHours(12))), emptySet(), now).size)
    }

    @Test fun aLongOutageNeverFloodsTheUser() {
        val many = (1..9).map { c("k$it", now.minusMinutes(it.toLong())) }
        val r = MissedReminderPolicy.select(many, emptySet(), now)
        assertEquals(5, r.size)
        assertEquals(listOf("k5", "k4", "k3", "k2", "k1"), r.map { it.key }) // most recent five, oldest first
    }

    @Test fun deliveredSetStaysBounded() {
        assertEquals(setOf("a"), MissedReminderPolicy.prune(setOf("a", "gone"), setOf("a", "b")))
    }
}

class AutomationFireGuardTest {
    private val now = LocalDateTime.of(2026, 11, 10, 8, 0, 1)

    @Test fun disabledNeverRuns() = assertFalse(AutomationFireGuard.shouldRun(false, null, now))
    @Test fun neverFiredRuns() = assertTrue(AutomationFireGuard.shouldRun(true, null, now))
    @Test fun aDuplicateWithinTheGapIsCollapsed() = assertFalse(AutomationFireGuard.shouldRun(true, now.minusSeconds(3), now))
    @Test fun nextDayRunsAgain() = assertTrue(AutomationFireGuard.shouldRun(true, now.minusDays(1), now))
    @Test fun aClockMovedBackDoesNotBlockTheRuleForever() = assertTrue(AutomationFireGuard.shouldRun(true, now.plusHours(2), now))
}

class NotificationGateTest {
    private fun g(rp: Boolean = true, pg: Boolean = true, en: Boolean = true, cb: Boolean = false) =
        NotificationGate.evaluate(rp, pg, en, cb)

    @Test fun allowed() = assertEquals(NotificationGateResult.ALLOWED, g())
    @Test fun permissionDenied() = assertEquals(NotificationGateResult.BLOCKED_PERMISSION, g(pg = false))
    @Test fun oldAndroidDoesNotNeedThePermission() = assertEquals(NotificationGateResult.ALLOWED, g(rp = false, pg = false))
    @Test fun appLevelSwitchOff() = assertEquals(NotificationGateResult.BLOCKED_APP_DISABLED, g(en = false))
    @Test fun mutedChannel() = assertEquals(NotificationGateResult.BLOCKED_CHANNEL, g(cb = true))
    @Test fun permissionWinsOverTheOthers() = assertEquals(NotificationGateResult.BLOCKED_PERMISSION, g(pg = false, en = false, cb = true))
}

class DrivingLifecycleTest {
    private fun lc() = DrivingLifecycle("DRIVING")

    @Test fun startThenGracefulStopRestoresTheOriginalMode() {
        val l = lc()
        assertTrue(l.requestStart("HOME")); l.serviceUp()
        assertTrue(l.requestStop())
        assertEquals("HOME", l.serviceDown())
        assertEquals(DrivingPhase.IDLE, l.phase)
    }

    @Test fun aSecondStartBeforeTheServiceIsUpKeepsTheRealPreviousMode() {
        val l = lc()
        assertTrue(l.requestStart("WORK"))
        assertFalse(l.requestStart("DRIVING")) // mode was already flipped by the first start
        l.serviceUp()
        assertFalse(l.requestStart("DRIVING"))
        assertEquals("WORK", l.serviceDown())
    }

    @Test fun stopWhileIdleDoesNotStartTheServiceJustToStopIt() {
        assertFalse(lc().requestStop())
    }

    @Test fun aSecondStopIsIgnored() {
        val l = lc(); l.requestStart("HOME"); l.serviceUp()
        assertTrue(l.requestStop()); assertFalse(l.requestStop())
    }

    @Test fun aServiceThatDiesWithoutAGracefulStopStillRestoresTheMode() {
        val l = lc(); l.requestStart("HOME"); l.serviceUp()
        assertEquals("HOME", l.serviceDown())
        assertNull(l.serviceDown()) // restored once only
    }

    @Test fun aFailedStartRollsBackAndAllowsANewStart() {
        val l = lc(); l.requestStart("HOME")
        assertEquals("HOME", l.startFailed())
        assertTrue(l.requestStart("HOME"))
    }

    @Test fun startingWhileAlreadyInDrivingModeRemembersNothingToRestore() {
        val l = lc(); l.requestStart("DRIVING"); l.serviceUp()
        assertNull(l.serviceDown())
    }
}
