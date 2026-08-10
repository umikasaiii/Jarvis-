package com.simone.jarvismobile.proactive

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
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
) {
    suspend fun evaluate(now: LocalDateTime = LocalDateTime.now()) {
        val config = readSettings()
        if (!config.enabled) return
        val today = now.toLocalDate()
        val candidates = candidatesFor(now, snapshot(today), today)
        if (candidates.isEmpty()) return
        val state = store.load().rolledTo(today)
        when (val decision = ProactiveGovernor.decide(candidates, config, state, now)) {
            is ProactiveDecision.Deliver -> {
                notifier.show(decision.suggestion)
                store.save(decision.newState)
                Log.i(TAG, "proactive_deliver ${decision.suggestion.kind}")
            }
            is ProactiveDecision.Skip -> Log.i(TAG, "proactive_skip ${decision.reason}")
        }
    }

    /** Only offer digests in their natural window, so a midday run stays quiet. */
    private fun candidatesFor(now: LocalDateTime, snap: ProactiveSnapshot, today: LocalDate): List<ProactiveSuggestion> {
        val out = ArrayList<ProactiveSuggestion>()
        val hour = now.hour
        if (hour in MORNING_FROM..MORNING_TO) out += ProactiveComposer.morningDigest(snap, today)
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

    private suspend fun snapshot(today: LocalDate): ProactiveSnapshot {
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
            .filter { !it.done && it.time == null && (it.date == today || it.starred) }
            .map { (if (it.starred) "⭐ " else "") + it.text }

        return ProactiveSnapshot(
            batteryPercent = percent,
            charging = charging,
            nextAlarm = nextAlarmTime(),
            todayAppointments = appointments,
            todayTasks = tasks,
        )
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
        const val MORNING_FROM = 6
        const val MORNING_TO = 10
        const val EVENING_FROM = 19
        const val EVENING_TO = 21
    }
}
