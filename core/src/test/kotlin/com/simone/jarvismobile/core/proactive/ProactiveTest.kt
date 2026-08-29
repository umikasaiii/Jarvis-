package com.simone.jarvismobile.core.proactive

import com.simone.jarvismobile.core.weather.WeatherCategory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ProactiveTest {

    private val today = LocalDate.of(2026, 8, 11)
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

    // --- composer -----------------------------------------------------------

    @Test fun morningDigestSaysSoWhenDayIsClear() {
        // The adaptive spec: a clear day is exactly as short as it sounds, not
        // silence and not padding.
        val s = ProactiveComposer.morningDigest(ProactiveSnapshot(), today)
        assertEquals("Buongiorno. Nessun impegno importante oggi.", s.message)
    }

    @Test fun morningDigestListsTodaysItems() {
        val snap = ProactiveSnapshot(
            todayAppointments = listOf("dentista 15:00"),
            todayTasks = listOf("chiamare Marco"),
        )
        val s = ProactiveComposer.morningDigest(snap, today)
        assertEquals("Buongiorno. Oggi: dentista 15:00, chiamare Marco.", s.message)
    }

    @Test fun morningDigestCallsOutBirthdaysSeparatelyFromTheAgenda() {
        val snap = ProactiveSnapshot(birthdaysToday = listOf("Marco"), todayTasks = listOf("chiamare il medico"))
        val s = ProactiveComposer.morningDigest(snap, today)
        assertEquals("Buongiorno. Oggi è il compleanno di Marco. Oggi: chiamare il medico.", s.message)
    }

    @Test fun morningDigestJoinsMultipleBirthdaysAndSkipsTheEmptyDayLine() {
        val snap = ProactiveSnapshot(birthdaysToday = listOf("Marco", "Giulia"))
        val s = ProactiveComposer.morningDigest(snap, today)
        // A birthday IS something on the day, so "nessun impegno" would be false.
        assertEquals("Buongiorno. Oggi è il compleanno di Marco e Giulia.", s.message)
    }

    @Test fun morningDigestMentionsRainOnlyWhenForecast() {
        assertTrue("pioggia" !in ProactiveComposer.morningDigest(ProactiveSnapshot(rainToday = null), today).message)
        assertTrue("pioggia" !in ProactiveComposer.morningDigest(ProactiveSnapshot(rainToday = false), today).message)
        assertTrue("pioggia" in ProactiveComposer.morningDigest(ProactiveSnapshot(rainToday = true), today).message)
    }

    @Test fun morningDigestAddsTheWeatherEmojiWhenKnown() {
        // No "." between the emoji and what follows (§ richiesta esplicita
        // dell'utente) — only the no-weather case still closes with one.
        assertEquals(
            "Buongiorno ☀️ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(ProactiveSnapshot(todayWeather = WeatherCategory.CLEAR), today).message,
        )
        assertEquals(
            "Buongiorno ⛅ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(ProactiveSnapshot(todayWeather = WeatherCategory.PARTLY_CLOUDY), today).message,
        )
        assertEquals(
            "Buongiorno ☁️ Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(ProactiveSnapshot(todayWeather = WeatherCategory.CLOUDY), today).message,
        )
    }

    @Test fun morningDigestSkipsTheEmojiWhenTheForecastIsUnknown() {
        assertEquals(
            "Buongiorno. Nessun impegno importante oggi.",
            ProactiveComposer.morningDigest(ProactiveSnapshot(todayWeather = null), today).message,
        )
    }

    @Test fun morningDigestNamesThunderstormsSeparatelyFromPlainRain() {
        val storm = ProactiveComposer.morningDigest(
            ProactiveSnapshot(todayWeather = WeatherCategory.THUNDERSTORM, rainToday = true),
            today,
        ).message
        assertEquals("Buongiorno ⛈️ Nessun impegno importante oggi. Oggi sono previsti temporali.", storm)

        val rain = ProactiveComposer.morningDigest(
            ProactiveSnapshot(todayWeather = WeatherCategory.RAIN, rainToday = true),
            today,
        ).message
        assertEquals("Buongiorno 🌧️ Nessun impegno importante oggi. Oggi è prevista pioggia.", rain)
    }

    @Test fun batteryBeforeAlarmOnlyWhenItHelps() {
        val early = LocalTime.of(6, 30)
        assertNull(ProactiveComposer.batteryBeforeAlarm(ProactiveSnapshot(charging = true, batteryPercent = 10, nextAlarm = early), today))
        assertNull(ProactiveComposer.batteryBeforeAlarm(ProactiveSnapshot(batteryPercent = 80, nextAlarm = early), today))
        assertNull(ProactiveComposer.batteryBeforeAlarm(ProactiveSnapshot(batteryPercent = 10, nextAlarm = null), today))
        assertNull(ProactiveComposer.batteryBeforeAlarm(ProactiveSnapshot(batteryPercent = 10, nextAlarm = LocalTime.of(11, 0)), today))
        val hit = ProactiveComposer.batteryBeforeAlarm(ProactiveSnapshot(batteryPercent = 15, nextAlarm = early), today)
        assertTrue(hit != null && "15%" in hit.message && "06:30" in hit.message)
    }
}
