package com.simone.jarvismobile.core.proactive

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherCategory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ProactiveTest {

    private val today = LocalDate.of(2026, 8, 11)
    private val tomorrow = today.plusDays(1)
    private val evening = LocalDateTime.of(today, LocalTime.of(23, 0))
    // A daytime moment outside the default 22:00–08:00 quiet window.
    private val morning = LocalDateTime.of(today, LocalTime.of(9, 30))

    private fun on() = ProactiveSettings(enabled = true)
    private fun freshState() = ProactiveState(today)

    private val battery = ProactiveSuggestion(
        ProactiveKind.BATTERY_BEFORE_ALARM, "carica", priority = 80, dedupKey = "b:$today",
    )
    private val digest = ProactiveSuggestion(
        ProactiveKind.MORNING_DIGEST, "buongiorno", priority = 50, dedupKey = "m:$today",
    )

    /**
     * § WORK PACKAGE C — builds a [ProactiveDigestSnapshot] with sensible,
     * independently-overridable defaults, so each test only states the
     * facts it actually cares about. A section's [ToolOutcomeStatus]
     * defaults to [ToolOutcomeStatus.SUCCESS_EMPTY] when its own lists are
     * all empty, [ToolOutcomeStatus.SUCCESS_DATA] otherwise — an explicit
     * override is how a test expresses a genuine read failure.
     */
    private fun digestSnapshot(
        todayAppointments: List<String> = emptyList(),
        todayTasks: List<String> = emptyList(),
        todayBirthdays: List<String> = emptyList(),
        todayStatus: ToolOutcomeStatus = if (todayAppointments.isEmpty() && todayTasks.isEmpty() && todayBirthdays.isEmpty()) {
            ToolOutcomeStatus.SUCCESS_EMPTY
        } else {
            ToolOutcomeStatus.SUCCESS_DATA
        },
        tomorrowAppointments: List<String> = emptyList(),
        tomorrowTasks: List<String> = emptyList(),
        tomorrowBirthdays: List<String> = emptyList(),
        tomorrowStatus: ToolOutcomeStatus = if (tomorrowAppointments.isEmpty() && tomorrowTasks.isEmpty() && tomorrowBirthdays.isEmpty()) {
            ToolOutcomeStatus.SUCCESS_EMPTY
        } else {
            ToolOutcomeStatus.SUCCESS_DATA
        },
        carryover: List<String> = emptyList(),
        priorities: List<String> = emptyList(),
        todayWeather: WeatherCategory? = null,
        rainToday: Boolean? = null,
        todayWeatherStatus: ToolOutcomeStatus = if (todayWeather != null || rainToday != null) {
            ToolOutcomeStatus.SUCCESS_DATA
        } else {
            ToolOutcomeStatus.DATA_UNAVAILABLE
        },
        tomorrowWeather: WeatherCategory? = null,
        tomorrowWeatherStatus: ToolOutcomeStatus = if (tomorrowWeather != null) ToolOutcomeStatus.SUCCESS_DATA else ToolOutcomeStatus.DATA_UNAVAILABLE,
        batteryPercent: Int = -1,
        charging: Boolean = false,
        nextAlarm: LocalTime? = null,
        deliveryDate: LocalDate = today,
    ): ProactiveDigestSnapshot = ProactiveDigestSnapshot(
        deliveryDate = deliveryDate,
        today = ProactiveDaySection(deliveryDate, todayStatus, todayAppointments, todayTasks, todayBirthdays),
        tomorrow = ProactiveDaySection(deliveryDate.plusDays(1), tomorrowStatus, tomorrowAppointments, tomorrowTasks, tomorrowBirthdays),
        todayCarryoverForEvening = carryover,
        openPriorities = priorities,
        todayWeather = ProactiveWeatherFacts(deliveryDate, todayWeather, todayWeatherStatus, rainToday),
        tomorrowWeather = ProactiveWeatherFacts(deliveryDate.plusDays(1), tomorrowWeather, tomorrowWeatherStatus),
        batteryPercent = batteryPercent,
        charging = charging,
        nextAlarm = nextAlarm,
    )

    // --- governor -----------------------------------------------------------

    @Test fun disabledStaysSilent() {
        val d = ProactiveGovernor.decide(listOf(battery), ProactiveSettings(enabled = false), freshState(), morning)
        assertIs<ProactiveDecision.Skip>(d)
    }

    @Test fun quietHoursStaySilent() {
        // 23:00 is inside the default 22:00–08:00 quiet window.
        val d = ProactiveGovernor.decide(listOf(battery), on(), freshState(), evening)
        assertEquals("quiet_hours", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun morningDigestIsNeverSilencedByDefaultQuietHours() {
        // Bug reale segnalato dall'utente: "non è arrivato briefing" — con le
        // ore silenziose predefinite (22:00→08:00) un vero sblocco delle 6:30
        // cade dentro la finestra silenziosa, ma il digest mattutino è
        // contenuto esplicitamente richiesto dall'utente, non un consiglio
        // facoltativo, quindi deve arrivare comunque.
        val earlyUnlock = LocalDateTime.of(today, LocalTime.of(6, 30))
        val d = ProactiveGovernor.decide(listOf(digest), on(), freshState(), earlyUnlock)
        val deliver = assertIs<ProactiveDecision.Deliver>(d)
        assertEquals(ProactiveKind.MORNING_DIGEST, deliver.suggestion.kind)
    }

    @Test fun optionalSuggestionsStayInsideQuietHoursWhileDigestIsExempt() {
        // Same early-morning moment: a genuinely optional suggestion
        // (BATTERY_BEFORE_ALARM) still yields to quiet hours — only the
        // digest is exempt, not every candidate.
        val earlyUnlock = LocalDateTime.of(today, LocalTime.of(6, 30))
        val d = ProactiveGovernor.decide(listOf(battery), on(), freshState(), earlyUnlock)
        assertEquals("quiet_hours", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun deliversHighestPriorityAndUpdatesState() {
        val d = ProactiveGovernor.decide(listOf(digest, battery), on(), freshState(), morning)
        val deliver = assertIs<ProactiveDecision.Deliver>(d)
        assertEquals(ProactiveKind.BATTERY_BEFORE_ALARM, deliver.suggestion.kind)
        assertEquals(1, deliver.newState.deliveredCount)
        assertTrue("b:$today" in deliver.newState.deliveredKeys)
    }

    @Test fun budgetIsRespected() {
        val settings = on().copy(maxPerDay = 1)
        val spent = ProactiveState(today, deliveredCount = 1)
        val d = ProactiveGovernor.decide(listOf(battery), settings, spent, morning)
        assertEquals("budget", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun sameSuggestionNotRepeatedInADay() {
        val already = ProactiveState(today, deliveredCount = 1, deliveredKeys = setOf("b:$today"))
        val d = ProactiveGovernor.decide(listOf(battery), on(), already, morning)
        assertEquals("no_candidate", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun mutedKindIsSuppressed() {
        val muted = on().copy(mutedKinds = setOf(ProactiveKind.BATTERY_BEFORE_ALARM))
        val d = ProactiveGovernor.decide(listOf(battery), muted, freshState(), morning)
        assertEquals("no_candidate", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun disabledCategoryIsSuppressed() {
        val off = on().copy(disabledKinds = setOf(ProactiveKind.MORNING_DIGEST))
        val d = ProactiveGovernor.decide(listOf(digest), off, freshState(), morning)
        assertEquals("no_candidate", (d as ProactiveDecision.Skip).reason)
    }

    @Test fun newDayResetsBudget() {
        val yesterday = ProactiveState(today.minusDays(1), deliveredCount = 3, deliveredKeys = setOf("b:x"))
        val d = ProactiveGovernor.decide(listOf(battery), on().copy(maxPerDay = 3), yesterday, morning)
        val deliver = assertIs<ProactiveDecision.Deliver>(d)
        assertEquals(1, deliver.newState.deliveredCount) // reset then +1
        assertEquals(today, deliver.newState.day)
    }

    @Test fun quietWindowWrapsMidnight() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(8, 0)
        assertTrue(ProactiveGovernor.inQuietHours(LocalTime.of(23, 0), start, end))
        assertTrue(ProactiveGovernor.inQuietHours(LocalTime.of(3, 0), start, end))
        assertTrue(!ProactiveGovernor.inQuietHours(LocalTime.of(12, 0), start, end))
    }

    // --- morning composer -----------------------------------------------------

    @Test fun morningDigestSaysSoWhenDayIsClear() {
        // The adaptive spec: a clear day is exactly as short as it sounds, not
        // silence and not padding.
        val s = ProactiveComposer.morningDigest(digestSnapshot())
        assertEquals("Buongiorno. Nessun impegno importante oggi.", s.message)
    }

    @Test fun morningDigestListsTodaysItems() {
        val snap = digestSnapshot(todayAppointments = listOf("dentista 15:00"), todayTasks = listOf("chiamare Marco"))
        val s = ProactiveComposer.morningDigest(snap)
        assertEquals("Buongiorno. Oggi: dentista 15:00, chiamare Marco.", s.message)
    }

    @Test fun morningDigestCallsOutBirthdaysSeparatelyFromTheAgenda() {
        val snap = digestSnapshot(todayBirthdays = listOf("Marco"), todayTasks = listOf("chiamare il medico"))
        val s = ProactiveComposer.morningDigest(snap)
        assertEquals("Buongiorno. Oggi è il compleanno di Marco. Oggi: chiamare il medico.", s.message)
    }

    @Test fun morningDigestJoinsMultipleBirthdaysAndSkipsTheEmptyDayLine() {
        val snap = digestSnapshot(todayBirthdays = listOf("Marco", "Giulia"))
        val s = ProactiveComposer.morningDigest(snap)
        // A birthday IS something on the day, so "nessun impegno" would be false.
        assertEquals("Buongiorno. Oggi è il compleanno di Marco e Giulia.", s.message)
    }

    @Test fun morningDigestMentionsRainOnlyWhenForecast() {
        assertTrue("pioggia" !in ProactiveComposer.morningDigest(digestSnapshot(rainToday = null)).message)
        assertTrue("pioggia" !in ProactiveComposer.morningDigest(digestSnapshot(rainToday = false)).message)
        assertTrue("pioggia" in ProactiveComposer.morningDigest(digestSnapshot(rainToday = true)).message)
    }

    @Test fun morningDigestAddsTheWeatherEmojiWhenKnown() {
        // No "." between the emoji and what follows (§ richiesta esplicita
        // dell'utente) — only the no-weather case still closes with one.
        assertEquals(
            "Buongiorno ☀️ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(digestSnapshot(todayWeather = WeatherCategory.CLEAR)).message,
        )
        assertEquals(
            "Buongiorno ⛅ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(digestSnapshot(todayWeather = WeatherCategory.PARTLY_CLOUDY)).message,
        )
        assertEquals(
            "Buongiorno ☁️ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(digestSnapshot(todayWeather = WeatherCategory.CLOUDY)).message,
        )
    }

    @Test fun morningDigestSkipsTheEmojiWhenTheForecastIsUnknown() {
        assertEquals(
            "Buongiorno. Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(digestSnapshot(todayWeather = null)).message,
        )
    }

    @Test fun morningDigestNamesThunderstormsSeparatelyFromPlainRain() {
        val storm = ProactiveComposer.morningDigest(
            digestSnapshot(todayWeather = WeatherCategory.THUNDERSTORM, rainToday = true),
        ).message
        assertEquals("Buongiorno ⛈️ Nessun impegno importante oggi. Oggi sono previsti temporali.", storm)

        val rain = ProactiveComposer.morningDigest(
            digestSnapshot(todayWeather = WeatherCategory.RAIN, rainToday = true),
        ).message
        assertEquals("Buongiorno 🌧️ Nessun impegno importante oggi. Oggi è prevista pioggia.", rain)
    }

    // --- E01/D/F — never TODAY data under a MORNING label, never a
    // stale/unavailable read presented as verified-empty --------------------

    @Test fun morningDigestNeverClaimsEmptyOnASourceFailure() {
        // § §7 A — SUCCESS_EMPTY vs agenda failure.
        val ok = ProactiveComposer.morningDigest(digestSnapshot(todayStatus = ToolOutcomeStatus.SUCCESS_EMPTY)).message
        assertEquals("Buongiorno. Nessun impegno importante oggi.", ok)

        val failed = ProactiveComposer.morningDigest(digestSnapshot(todayStatus = ToolOutcomeStatus.SOURCE_FAILURE)).message
        assertEquals("Buongiorno. Agenda non verificabile al momento.", failed)
        assertFalse("Nessun impegno" in failed)

        val unavailable = ProactiveComposer.morningDigest(digestSnapshot(todayStatus = ToolOutcomeStatus.DATA_UNAVAILABLE)).message
        assertFalse("Nessun impegno" in unavailable)
    }

    @Test fun morningDigestNeverMentionsTomorrowsAppointment() {
        // § E01/D — a tomorrow-only appointment must never leak into the
        // morning digest, which only ever reads `snapshot.today`.
        val snap = digestSnapshot(todayAppointments = listOf("dentista 10:30"), tomorrowAppointments = listOf("visita 09:00"))
        val s = ProactiveComposer.morningDigest(snap).message
        assertTrue("dentista" in s)
        assertFalse("visita" in s)
    }

    @Test fun morningDigestWeatherEmojiOmittedOnStaleOrUnavailable() {
        // § F — weather unknown/stale => no morning weather emoji.
        val stale = digestSnapshot().copy(
            todayWeather = ProactiveWeatherFacts(today, WeatherCategory.CLEAR, ToolOutcomeStatus.STALE),
        )
        assertEquals("Buongiorno. Nessun impegno importante oggi.", ProactiveComposer.morningDigest(stale).message)

        val unavailable = digestSnapshot().copy(
            todayWeather = ProactiveWeatherFacts(today, WeatherCategory.CLEAR, ToolOutcomeStatus.DATA_UNAVAILABLE),
        )
        assertEquals("Buongiorno. Nessun impegno importante oggi.", ProactiveComposer.morningDigest(unavailable).message)
    }

    // --- evening composer (§8 Buonasera contract) --------------------------

    @Test fun eveningDigestAlwaysGreetsWithTheDeterministicBuonasera() {
        val s = ProactiveComposer.eveningDigest(digestSnapshot())
        assertTrue(s.message.startsWith("Buonasera 🌙"))
        assertEquals(ProactiveKind.EVENING_DIGEST, s.kind)
        assertEquals("EVENING_DIGEST:$today", s.dedupKey)
    }

    @Test fun eveningDigestListsOnlyTomorrowsItems_neverTodays() {
        // § C/D/E01 — the exact §4 defect this work package closes: TODAY
        // data must never appear under the evening "Domani:" label.
        val snap = digestSnapshot(
            todayAppointments = listOf("dentista 10:30"),
            tomorrowAppointments = listOf("visita 09:00"),
            tomorrowTasks = listOf("palestra"),
        )
        val s = ProactiveComposer.eveningDigest(snap).message
        assertTrue("visita 09:00" in s)
        assertTrue("palestra" in s)
        assertFalse("dentista" in s)
        assertEquals("Buonasera 🌙 Domani: visita 09:00, palestra.", s)
    }

    @Test fun eveningDigestVerifiedEmptyTomorrowSaysSoExplicitly() {
        val s = ProactiveComposer.eveningDigest(digestSnapshot(tomorrowStatus = ToolOutcomeStatus.SUCCESS_EMPTY)).message
        assertEquals("Buonasera 🌙 Domani non risultano impegni in agenda.", s)
    }

    @Test fun eveningDigestNeverClaimsEmptyTomorrowOnAFailedRead() {
        // § §8/B — a failed/unavailable tomorrow read must never say "no impegni".
        val failed = ProactiveComposer.eveningDigest(digestSnapshot(tomorrowStatus = ToolOutcomeStatus.SOURCE_FAILURE)).message
        assertFalse("non risultano impegni" in failed)
        assertEquals("Buonasera 🌙 Agenda di domani non verificabile al momento.", failed)

        val unavailable = ProactiveComposer.eveningDigest(digestSnapshot(tomorrowStatus = ToolOutcomeStatus.DATA_UNAVAILABLE)).message
        assertFalse("non risultano impegni" in unavailable)
    }

    @Test fun eveningDigestSeparatesTodayCarryoverFromTomorrow() {
        // § §9 — never "Domani: [today's unfinished task]".
        val snap = digestSnapshot(
            tomorrowAppointments = listOf("visita 09:00"),
            carryover = listOf("pagare bolletta"),
        )
        val s = ProactiveComposer.eveningDigest(snap).message
        assertEquals("Buonasera 🌙 Domani: visita 09:00. Da oggi restano: pagare bolletta.", s)
        // The carryover item is never rendered inside the "Domani:" clause itself.
        assertFalse(s.contains("Domani: visita 09:00, pagare bolletta"))
    }

    @Test fun eveningDigestRendersOpenPrioritiesAsASeparateGroupingNeverAFabricatedDeadline() {
        // § §10 — a starred, undated task is priority, not a date.
        val snap = digestSnapshot(
            tomorrowAppointments = listOf("visita 09:00"),
            priorities = listOf("rinnovare passaporto"),
        )
        val s = ProactiveComposer.eveningDigest(snap).message
        assertTrue("Priorità aperte: rinnovare passaporto." in s)
        // Never merged into the tomorrow commitments clause.
        assertFalse(s.contains("Domani: visita 09:00, rinnovare passaporto"))
    }

    @Test fun eveningDigestBirthdayTomorrowIsSeparateFromTheAgendaClause() {
        val snap = digestSnapshot(tomorrowBirthdays = listOf("Giulia"), tomorrowAppointments = listOf("visita 09:00"))
        val s = ProactiveComposer.eveningDigest(snap).message
        assertEquals("Buonasera 🌙 Domani è il compleanno di Giulia. Domani: visita 09:00.", s)
    }

    @Test fun eveningDigestTomorrowWeatherEmojiNeverReplacesTheMoonGreeting() {
        // § §13 — placed next to the tomorrow weather fact, not instead of
        // "Buonasera 🌙", and only when known-and-fresh.
        val snap = digestSnapshot(tomorrowAppointments = listOf("visita 09:00"), tomorrowWeather = WeatherCategory.RAIN)
        val s = ProactiveComposer.eveningDigest(snap).message
        assertTrue(s.startsWith("Buonasera 🌙 "))
        assertEquals("Buonasera 🌙 Domani: visita 09:00. Meteo domani: 🌧️.", s)
    }

    @Test fun eveningDigestOmitsTomorrowWeatherWhenUnknownOrStale() {
        val stale = digestSnapshot().copy(tomorrowWeather = ProactiveWeatherFacts(tomorrow, WeatherCategory.RAIN, ToolOutcomeStatus.STALE))
        assertFalse("Meteo domani" in ProactiveComposer.eveningDigest(stale).message)
    }

    @Test fun eveningDigestNeverUsesWeatherEmojiAsAlertEvidence() {
        // The evening digest's weather line is a display concern only — no
        // WeatherHazard/WeatherAlertPolicy involvement at all.
        val snap = digestSnapshot(tomorrowWeather = WeatherCategory.THUNDERSTORM)
        val s = ProactiveComposer.eveningDigest(snap).message
        assertTrue("⛈️" in s)
        assertFalse("temporal" in s.lowercase().replace("⛈️", ""))
    }

    // --- battery composer -----------------------------------------------------

    @Test fun batteryBeforeAlarmOnlyWhenItHelps() {
        val early = LocalTime.of(6, 30)
        assertNull(ProactiveComposer.batteryBeforeAlarm(digestSnapshot(charging = true, batteryPercent = 10, nextAlarm = early)))
        assertNull(ProactiveComposer.batteryBeforeAlarm(digestSnapshot(batteryPercent = 80, nextAlarm = early)))
        assertNull(ProactiveComposer.batteryBeforeAlarm(digestSnapshot(batteryPercent = 10, nextAlarm = null)))
        assertNull(ProactiveComposer.batteryBeforeAlarm(digestSnapshot(batteryPercent = 10, nextAlarm = LocalTime.of(11, 0))))
        val hit = ProactiveComposer.batteryBeforeAlarm(digestSnapshot(batteryPercent = 15, nextAlarm = early))
        assertTrue(hit != null && "15%" in hit.message && "06:30" in hit.message)
    }

    // --- E02 — starred/undated/completed/birthday grouping ------------------

    @Test fun starredButDatedNextWeekTaskIsNeverPresentedAsTomorrow() {
        // A future-starred task dated next week must not appear as tomorrow
        // merely because it is starred (§10/§E) — this snapshot simply never
        // places it in `tomorrow` at all (the manager is what enforces that
        // a next-week date never lands in the tomorrow section — this test
        // pins the composer side: whatever isn't in `tomorrow`/`openPriorities`
        // never appears).
        val snap = digestSnapshot(tomorrowAppointments = listOf("visita 09:00"), priorities = listOf("rinnovare passaporto"))
        val s = ProactiveComposer.eveningDigest(snap).message
        assertFalse(s.contains("prossima settimana"))
        assertTrue("Priorità aperte: rinnovare passaporto." in s)
    }

    // --- E05 — greeting/emoji snapshots --------------------------------------

    @Test fun morningKnownCategoriesMapToTheExactEmojiSet() {
        val expected = mapOf(
            WeatherCategory.CLEAR to "☀️",
            WeatherCategory.PARTLY_CLOUDY to "⛅",
            WeatherCategory.CLOUDY to "☁️",
            WeatherCategory.RAIN to "🌧️",
            WeatherCategory.THUNDERSTORM to "⛈️",
        )
        expected.forEach { (category, emoji) ->
            val message = ProactiveComposer.morningDigest(digestSnapshot(todayWeather = category)).message
            assertTrue(emoji in message, "expected $emoji in \"$message\" for $category")
        }
    }

    @Test fun eveningGreetingIsAlwaysDeterministicRegardlessOfWeather() {
        val noWeather = ProactiveComposer.eveningDigest(digestSnapshot()).message
        val withWeather = ProactiveComposer.eveningDigest(digestSnapshot(tomorrowWeather = WeatherCategory.CLEAR)).message
        assertTrue(noWeather.startsWith("Buonasera 🌙"))
        assertTrue(withWeather.startsWith("Buonasera 🌙"))
    }

    // --- I/J — midnight boundary + explicit-clock discipline ----------------

    @Test fun eveningAgendaTargetDateIsExactlyDeliveryDatePlusOne() {
        val d = LocalDate.of(2026, 12, 31)
        val snap = digestSnapshot(deliveryDate = d, tomorrowAppointments = listOf("capodanno 00:00"))
        assertEquals(d.plusDays(1), snap.tomorrow.date)
        val s = ProactiveComposer.eveningDigest(snap)
        assertEquals("EVENING_DIGEST:$d", s.dedupKey)
    }

    @Test fun composerNeverRecomputesFromTheSystemClock_onlyFromTheInjectedSnapshot() {
        // § J — the composer takes no clock/`now` parameter at all; every
        // date it ever renders comes from the snapshot's own embedded dates,
        // proven here with a delivery date far from the real calendar date.
        val farFuture = LocalDate.of(2099, 1, 1)
        val snap = digestSnapshot(deliveryDate = farFuture, tomorrowAppointments = listOf("evento 10:00"))
        assertEquals("MORNING_DIGEST:$farFuture", ProactiveComposer.morningDigest(snap).dedupKey)
        assertEquals("EVENING_DIGEST:$farFuture", ProactiveComposer.eveningDigest(snap).dedupKey)
    }

    // --- weatherAlert composer (§ PASSAGGIO 14.2, unchanged carve-out) ------

    @Test fun weatherAlertNeverComposedForNoAlert() {
        assertFailsWith<IllegalArgumentException> {
            ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.NO_ALERT, today.plusDays(1))
        }
    }

    @Test fun weatherAlertMessagesAreDistinctPerHazardTier() {
        val target = today.plusDays(1)
        val rain = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.RAIN_EXPECTED, target).message
        val heavy = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.HEAVY_RAIN, target).message
        val storm = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.THUNDERSTORM, target).message
        assertTrue(setOf(rain, heavy, storm).size == 3)
        assertTrue("pioggia" in rain)
        assertTrue("pioggia" in heavy)
        assertTrue("temporal" in storm)
    }

    @Test fun weatherAlertKindAndDedupKeyAreKeyedByTargetDate() {
        val target = LocalDate.of(2026, 9, 12)
        val suggestion = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.THUNDERSTORM, target)
        assertEquals(ProactiveKind.WEATHER_ALERT, suggestion.kind)
        assertEquals("WEATHER_ALERT:2026-09-12", suggestion.dedupKey)
    }

    // --- governor: WEATHER_ALERT is quiet-hours exempt ----------------------

    @Test fun weatherAlertIsNeverSilencedByQuietHours() {
        val alert = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.RAIN_EXPECTED, today.plusDays(1))
        // 23:00 is inside the default 22:00-08:00 quiet window (same moment `quietHoursStaySilent` uses).
        val d = ProactiveGovernor.decide(listOf(alert), on(), freshState(), evening)
        val deliver = assertIs<ProactiveDecision.Deliver>(d)
        assertEquals(ProactiveKind.WEATHER_ALERT, deliver.suggestion.kind)
    }

    @Test fun optionalSuggestionsStillYieldToQuietHoursEvenWhenWeatherAlertIsPresent() {
        // A genuinely optional suggestion in the SAME candidate list as a
        // quiet-hours-exempt weather alert still cannot itself bypass quiet
        // hours — only the alert's own eligibility is exempt, not the whole call.
        val alert = ProactiveComposer.weatherAlert(com.simone.jarvismobile.core.weather.WeatherHazard.RAIN_EXPECTED, today.plusDays(1))
        val d = ProactiveGovernor.decide(listOf(battery, alert), on(), freshState(), evening)
        val deliver = assertIs<ProactiveDecision.Deliver>(d)
        assertEquals(ProactiveKind.WEATHER_ALERT, deliver.suggestion.kind)
    }
}
