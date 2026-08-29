package com.simone.jarvismobile.core.proactive

import com.simone.jarvismobile.core.weather.WeatherCategory
import java.time.LocalDate

/**
 * Turns the everyday [ProactiveSnapshot] into candidate suggestions. The *content*
 * comes deterministically from the data (the agenda, the battery, the alarm) —
 * nothing is invented here. The Android layer may later re-phrase a message more
 * naturally with the local model, but the facts are fixed by these functions, so
 * a proactive line can never say something that isn't in the user's own data.
 */
object ProactiveComposer {

    /** Battery threshold under which a "charge before your early alarm" is worth it. */
    const val LOW_BATTERY = 30

    /** An alarm at or before this hour counts as "early", worth a heads-up the night before. */
    const val EARLY_HOUR = 8

    /**
     * "Buongiorno" plus what the day actually holds — birthdays, appointments,
     * tasks, a rain/storm warning if one is known — and nothing else. An empty
     * day says so in one line rather than staying silent or padding it out: the
     * point of an *adaptive* briefing is that a clear day is exactly as short
     * as it sounds. Meant to be offered at the first morning unlock.
     */
    fun morningDigest(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion {
        val items = snapshot.todayAppointments + snapshot.todayTasks
        val message = buildString {
            append("Buongiorno")
            // Only when the category is actually known — an unknown forecast
            // says nothing rather than defaulting to a guessed icon. When it IS
            // known, no "." between the emoji and what follows (§ richiesta
            // esplicita dell'utente) — the period only closes "Buongiorno" as
            // its own sentence when there is no emoji after it.
            val weatherEmoji = snapshot.todayWeather?.greetingEmoji()
            if (weatherEmoji != null) append(" $weatherEmoji") else append(".")
            if (snapshot.birthdaysToday.isNotEmpty()) {
                append(" Oggi è il compleanno di ")
                append(snapshot.birthdaysToday.joinToString(" e "))
                append(".")
            }
            if (items.isNotEmpty()) {
                append(" Oggi: ")
                append(items.joinToString(", "))
                append(".")
            } else if (snapshot.birthdaysToday.isEmpty()) {
                append(" Nessun impegno importante oggi.")
            }
            // Thunderstorm gets its own, more specific wording — distinct from
            // plain rain since it is the more hazardous of the two and the user
            // explicitly asked to be told which kind is coming. Only ever said
            // when actually forecast; unknown/no rain stays silent either way.
            when {
                snapshot.todayWeather == WeatherCategory.THUNDERSTORM -> append(" Oggi sono previsti temporali.")
                snapshot.rainToday == true -> append(" Oggi è prevista pioggia.")
            }
        }
        return ProactiveSuggestion(
            kind = ProactiveKind.MORNING_DIGEST,
            message = message,
            priority = 50,
            dedupKey = "${ProactiveKind.MORNING_DIGEST}:$today",
        )
    }

    /** ☀️ clear, ⛅ partly cloudy, ☁️ cloudy, 🌧️ rain, ⛈️ thunderstorm — one per [WeatherCategory]. */
    private fun WeatherCategory.greetingEmoji(): String = when (this) {
        WeatherCategory.CLEAR -> "☀️"
        WeatherCategory.PARTLY_CLOUDY -> "⛅"
        WeatherCategory.CLOUDY -> "☁️"
        WeatherCategory.RAIN -> "🌧️"
        WeatherCategory.THUNDERSTORM -> "⛈️"
    }

    /**
     * Suggests charging when the battery is low, the phone isn't already charging,
     * and there is an early alarm the next morning. Returns null when it wouldn't
     * be useful, so the governor simply has nothing to deliver.
     */
    fun batteryBeforeAlarm(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion? {
        if (snapshot.charging) return null
        if (snapshot.batteryPercent !in 0..LOW_BATTERY) return null
        val alarm = snapshot.nextAlarm ?: return null
        if (alarm.hour > EARLY_HOUR) return null
        val clock = "%02d:%02d".format(alarm.hour, alarm.minute)
        return ProactiveSuggestion(
            kind = ProactiveKind.BATTERY_BEFORE_ALARM,
            message = "Batteria al ${snapshot.batteryPercent}% e domani sveglia alle $clock: " +
                "ti conviene metterla in carica stanotte.",
            priority = 80,
            dedupKey = "${ProactiveKind.BATTERY_BEFORE_ALARM}:$today",
        )
    }

    /**
     * An evening recap of tomorrow-relevant items. Only offered when there is
     * something to say; otherwise null.
     */
    fun eveningDigest(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion? {
        val items = snapshot.todayAppointments + snapshot.todayTasks
        if (items.isEmpty()) return null
        return ProactiveSuggestion(
            kind = ProactiveKind.EVENING_DIGEST,
            message = "Per domani: " + items.joinToString(", ") + ".",
            priority = 40,
            dedupKey = "${ProactiveKind.EVENING_DIGEST}:$today",
        )
    }

    /** Convenience for the caller: all non-null candidates for [now]'s date. */
    fun candidates(snapshot: ProactiveSnapshot, today: LocalDate): List<ProactiveSuggestion> =
        listOfNotNull(
            morningDigest(snapshot, today),
            batteryBeforeAlarm(snapshot, today),
            eveningDigest(snapshot, today),
        )
}
