package com.simone.jarvismobile.proactive

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.proactive.ProactiveComposer
import com.simone.jarvismobile.core.proactive.ProactiveDecision
import com.simone.jarvismobile.core.proactive.ProactiveGovernor
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.core.proactive.ProactiveSettings
import com.simone.jarvismobile.core.proactive.ProactiveSnapshot
import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of proactivity: it gathers the real signals, asks the pure
 * [ProactiveGovernor] whether to say anything, and — if so — posts one discreet
 * "Suggerimenti" notification. It never decides on its own what is allowed; the
 * governor and the user's saved settings do. Called periodically by a worker.
 */
@Singleton
class ProactiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val agenda: AgendaRepository,
    private val store: ProactiveStore,
    private val notifier: ProactiveNotifier,
    private val coordinator: SessionCoordinator,
    private val contextEngine: ContextEngine,
) {
    /** Called periodically by the worker as a coarse fallback (see [evaluateOnUnlock]). */
    suspend fun evaluate(now: LocalDateTime = LocalDateTime.now()) =
        run(now, forceMorning = false)

    /**
     * Called at the real first-unlock-of-the-day event (§ "primo sblocco utile
     * della giornata"). The morning digest is offered outside the coarse 6-10
     * window used by [evaluate] — someone unlocking at 05:40 still gets their
     * one "Buongiorno" — but never before [MORNING_EARLIEST_HOUR] (see
     * [candidatesFor]): `ACTION_USER_PRESENT` fires on *every* unlock, so
     * without a floor, checking the phone at 00:05 — still awake, not yet
     * asleep — would consume the calendar day's one "Buongiorno" right then,
     * and the real wake-up hours later would find it already delivered
     * (`MORNING_DIGEST:<date>` in [ProactiveState] dedups per calendar date,
     * not per sleep cycle). The governor's per-day dedup still does the rest:
     * once past the floor, this stays exactly the "once a day" digest.
     */
    suspend fun evaluateOnUnlock(now: LocalDateTime = LocalDateTime.now()) =
        run(now, forceMorning = true)

    private suspend fun run(now: LocalDateTime, forceMorning: Boolean) {
        val config = readSettings()
        if (!config.enabled) return
        val today = now.toLocalDate()
        val candidates = candidatesFor(now, snapshot(today, now), today, forceMorning)
        if (candidates.isEmpty()) return
        val state = store.load().rolledTo(today)
        when (val decision = ProactiveGovernor.decide(candidates, config, state, now)) {
            is ProactiveDecision.Deliver -> {
                notifier.show(decision.suggestion)
                store.save(decision.newState)
                // Spoken too, same opt-in path a new-engine SPEAK action uses — an
                // adaptive briefing that only JARVIS reads silently isn't a briefing.
                runCatching { coordinator.speakBackgroundResponse(decision.suggestion.message) }
                Log.i(TAG, "proactive_deliver ${decision.suggestion.kind}")
            }
            is ProactiveDecision.Skip -> Log.i(TAG, "proactive_skip ${decision.reason}")
        }
    }

    /**
     * Digests in their natural window, so a midday periodic run stays quiet — but
     * [forceMorning] (the real unlock event) offers the morning digest outside
     * the [MORNING_FROM]-[MORNING_TO] window too, down to [MORNING_EARLIEST_HOUR]
     * — never earlier, so a late-night unlock right after midnight is not
     * mistaken for waking up.
     */
    private fun candidatesFor(
        now: LocalDateTime,
        snap: ProactiveSnapshot,
        today: LocalDate,
        forceMorning: Boolean,
    ): List<ProactiveSuggestion> {
        val out = ArrayList<ProactiveSuggestion>()
        val hour = now.hour
        val isMorningUnlock = forceMorning && hour >= MORNING_EARLIEST_HOUR
        if (isMorningUnlock || hour in MORNING_FROM..MORNING_TO) out += ProactiveComposer.morningDigest(snap, today)
        if (hour in EVENING_FROM..EVENING_TO) {
            ProactiveComposer.batteryBeforeAlarm(snap, today)?.let { out += it }
            ProactiveComposer.eveningDigest(snap, today)?.let { out += it }
        }
        return out
    }

    private suspend fun readSettings(): ProactiveSettings {
        fun kinds(names: Set<String>): Set<ProactiveKind> =
            names.mapNotNull { runCatching { ProactiveKind.valueOf(it) }.getOrNull() }.toSet()
        return ProactiveSettings(
            enabled = settings.proactiveEnabled.first(),
            maxPerDay = settings.proactiveMaxPerDay.first(),
            quietStart = LocalTime.of(settings.proactiveQuietStart.first(), 0),
            quietEnd = LocalTime.of(settings.proactiveQuietEnd.first(), 0),
            disabledKinds = kinds(settings.proactiveDisabledKinds.first()),
            mutedKinds = kinds(settings.proactiveMutedKinds.first()),
        )
    }

    private suspend fun snapshot(today: LocalDate, now: LocalDateTime): ProactiveSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val entries = runCatching { agenda.reload() }.getOrDefault(agenda.entries.value)
        val appointments = entries
            .filter { !it.done && it.date == today && it.time != null }
            .sortedBy { it.time }
            .map { "${it.text} ${clock(it.time!!)}" }
        val tasks = entries
            .filter { !it.done && it.time == null && (it.date == today || it.starred) && !isBirthday(it.text) }
            .map { (if (it.starred) "⭐ " else "") + it.text }
        // No dedicated birthday feature exists (§ honesty ledger): this reads
        // agenda items the user already wrote for today whose text names a
        // birthday, so "il compleanno di Marco" on today's date surfaces on its
        // own line instead of blending into the task list.
        val birthdays = entries
            .filter { it.date == today && isBirthday(it.text) }
            .map { birthdayName(it.text) }

        // Reuses ContextEngine's own staleness cutoff, so a refresher that has
        // stopped working reads as "unknown" here too, not as a frozen forecast.
        val rain = runCatching { contextEngine.evaluationContext(now = now) }.getOrNull()

        return ProactiveSnapshot(
            batteryPercent = percent,
            charging = charging,
            nextAlarm = nextAlarmTime(),
            todayAppointments = appointments,
            todayTasks = tasks,
            birthdaysToday = birthdays,
            rainToday = rain?.rainToday,
        )
    }

    private fun isBirthday(text: String): Boolean = text.contains("complean", ignoreCase = true)

    /** Best-effort name after "di"/"of", or the whole line if none is found. */
    private fun birthdayName(text: String): String {
        val afterDi = Regex("""complean\w*\s+di\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        return afterDi?.groupValues?.get(1)?.trim()?.trim('.', '!') ?: text.trim()
    }

    private fun nextAlarmTime(): LocalTime? = runCatching {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.nextAlarmClock?.triggerTime?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
        }
    }.getOrNull()

    private fun clock(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    private companion object {
        const val TAG = "JarvisProactive"
        // Real unlocks between midnight and this hour never count as "waking up"
        // (§ evaluateOnUnlock) — that is still the previous night, not morning.
        const val MORNING_EARLIEST_HOUR = 5
        const val MORNING_FROM = 6
        const val MORNING_TO = 10
        const val EVENING_FROM = 19
        const val EVENING_TO = 21
    }
}
