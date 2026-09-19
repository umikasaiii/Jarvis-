package com.simone.jarvismobile.core.proactive

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherHazard

/**
 * Turns the typed [ProactiveDigestSnapshot] into candidate suggestions. The *content*
 * comes deterministically from the data (the agenda, the battery, the alarm) —
 * nothing is invented here. The Android layer may later re-phrase a message more
 * naturally with the local model, but the facts are fixed by these functions, so
 * a proactive line can never say something that isn't in the user's own data.
 *
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE C — remains the ONE canonical presentation owner (§18): no second
 * composer, no LLM composer, no alternative renderer anywhere else in the
 * app. [weatherAlert] stays a separate, pre-existing, un-redesigned renderer
 * (§18's explicit carve-out).
 */
object ProactiveComposer {

    /** Battery threshold under which a "charge before your early alarm" is worth it. */
    const val LOW_BATTERY = 30

    /** An alarm at or before this hour counts as "early", worth a heads-up the night before. */
    const val EARLY_HOUR = 8

    /**
     * "Buongiorno" plus what today actually holds — birthdays, appointments,
     * tasks, a rain/storm warning if one is known-and-fresh — and nothing
     * else. TODAY data is the only data this ever presents (§4/§5: never
     * silently reusing another date's facts). An empty day says so
     * explicitly, but ONLY when [ProactiveDaySection.agendaStatus] confirms
     * the read genuinely succeeded and found nothing — a source failure or
     * an unavailable read must never render as "Nessun impegno importante
     * oggi" (§7's critical invariant: EMPTY != FAILURE). Meant to be offered
     * at the first morning unlock.
     */
    fun morningDigest(snapshot: ProactiveDigestSnapshot): ProactiveSuggestion {
        val today = snapshot.today
        val items = today.appointments + today.datedTasks
        val weather = snapshot.todayWeather
        val freshWeather = weather?.status == ToolOutcomeStatus.SUCCESS_DATA
        val message = buildString {
            append("Buongiorno")
            // Only when the category is actually known AND fresh — an
            // unknown/stale forecast says nothing rather than defaulting to
            // a guessed icon (§7/§12). When it IS known, no "." between the
            // emoji and what follows (§ richiesta esplicita dell'utente) —
            // the period only closes "Buongiorno" as its own sentence when
            // there is no emoji after it.
            val weatherEmoji = if (freshWeather) weather?.category?.greetingEmoji() else null
            if (weatherEmoji != null) append(" $weatherEmoji") else append(".")
            if (today.birthdays.isNotEmpty()) {
                append(" Oggi è il compleanno di ")
                append(today.birthdays.joinToString(" e "))
                append(".")
            }
            if (items.isNotEmpty()) {
                append(" Oggi: ")
                append(items.joinToString(", "))
                append(".")
            } else if (today.birthdays.isEmpty()) {
                // § §7 — an empty-day statement is only ever licensed by a
                // verified-empty read; any other status renders a concise,
                // honest "cannot verify" line instead of guessing silence.
                when (today.agendaStatus) {
                    ToolOutcomeStatus.SUCCESS_EMPTY -> append(" Nessun impegno importante oggi.")
                    else -> append(" Agenda non verificabile al momento.")
                }
            }
            // Thunderstorm gets its own, more specific wording — distinct from
            // plain rain since it is the more hazardous of the two and the user
            // explicitly asked to be told which kind is coming. Only ever said
            // when actually forecast AND fresh; unknown/stale/no rain stays silent.
            when {
                freshWeather && weather?.category == WeatherCategory.THUNDERSTORM -> append(" Oggi sono previsti temporali.")
                freshWeather && weather?.rain == true -> append(" Oggi è prevista pioggia.")
            }
        }
        return ProactiveSuggestion(
            kind = ProactiveKind.MORNING_DIGEST,
            message = message,
            priority = 50,
            dedupKey = "${ProactiveKind.MORNING_DIGEST}:${snapshot.deliveryDate}",
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
    fun batteryBeforeAlarm(snapshot: ProactiveDigestSnapshot): ProactiveSuggestion? {
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
            dedupKey = "${ProactiveKind.BATTERY_BEFORE_ALARM}:${snapshot.deliveryDate}",
        )
    }

    /**
     * An evening recap of what TOMORROW actually holds — never today's data
     * relabeled (§4/§8, the exact verified defect Work Package C closes).
     * Deterministic "Buonasera 🌙" greeting (§8) — the moon is a
     * greeting/daypart marker, NOT a weather claim (§13). Always returns a
     * suggestion: a verified-empty tomorrow says so explicitly rather than
     * staying silent (§8's "Verified empty tomorrow" contract), and a
     * failed/unavailable read renders a concise unavailable line rather than
     * ever claiming "no impegni" (§8's critical invariant, mirroring §7 for
     * the morning). Today's still-open items and undated starred priorities
     * are ALWAYS rendered under their own separate headings — never as if
     * they were dated tomorrow (§9/§10).
     */
    fun eveningDigest(snapshot: ProactiveDigestSnapshot): ProactiveSuggestion {
        val tomorrow = snapshot.tomorrow
        val items = tomorrow.appointments + tomorrow.datedTasks
        val message = buildString {
            append("Buonasera 🌙")
            if (tomorrow.birthdays.isNotEmpty()) {
                append(" Domani è il compleanno di ")
                append(tomorrow.birthdays.joinToString(" e "))
                append(".")
            }
            if (items.isNotEmpty()) {
                append(" Domani: ")
                append(items.joinToString(", "))
                append(".")
            } else if (tomorrow.birthdays.isEmpty()) {
                when (tomorrow.agendaStatus) {
                    ToolOutcomeStatus.SUCCESS_EMPTY -> append(" Domani non risultano impegni in agenda.")
                    else -> append(" Agenda di domani non verificabile al momento.")
                }
            }
            // § §9 — a separate, explicit heading; never folded into "Domani:".
            if (snapshot.todayCarryoverForEvening.isNotEmpty()) {
                append(" Da oggi restano: ")
                append(snapshot.todayCarryoverForEvening.joinToString(", "))
                append(".")
            }
            // § §10 — undated starred priorities, their own grouping, never a
            // fabricated tomorrow deadline.
            if (snapshot.openPriorities.isNotEmpty()) {
                append(" Priorità aperte: ")
                append(snapshot.openPriorities.joinToString(", "))
                append(".")
            }
            // § §13 — a tomorrow-weather emoji, when shown, sits next to the
            // tomorrow weather fact, never replacing the moon greeting above,
            // and only when known-and-fresh (same discipline as the morning).
            val w = snapshot.tomorrowWeather
            if (w?.status == ToolOutcomeStatus.SUCCESS_DATA && w.category != null) {
                append(" Meteo domani: ${w.category.greetingEmoji()}.")
            }
        }
        return ProactiveSuggestion(
            kind = ProactiveKind.EVENING_DIGEST,
            message = message,
            priority = 40,
            dedupKey = "${ProactiveKind.EVENING_DIGEST}:${snapshot.deliveryDate}",
        )
    }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. The evening-before
     * rain/storm warning — composed only from a decided, real
     * [WeatherHazard] that is not [WeatherHazard.NO_ALERT]; the caller (§
     * [com.simone.jarvismobile.proactive.ProactiveManager]) is the one that
     * decides eligibility from [com.simone.jarvismobile.core.weather.WeatherAlertPolicy],
     * so [hazard] arrives here already meaningful — [require] documents that
     * contract instead of silently returning something meaningless.
     *
     * Deliberately no time-of-day window in the message: the underlying
     * forecast is a daily aggregate (§ `WeatherAlertPolicy`), which does not
     * support one — claiming a window here would overstate the evidence the
     * app actually has. No emoji, no natural-language parsing anywhere in
     * the decision that got us here — this function only ever RENDERS an
     * already-decided [WeatherHazard] enum value, never re-derives one from
     * text. § WORK PACKAGE C §18/§12 — unchanged, un-redesigned in this
     * work package; not turned into factual tomorrow-weather truth.
     */
    fun weatherAlert(hazard: WeatherHazard, targetDate: java.time.LocalDate): ProactiveSuggestion {
        require(hazard != WeatherHazard.NO_ALERT) { "weatherAlert must never be composed for NO_ALERT" }
        val message = when (hazard) {
            WeatherHazard.THUNDERSTORM -> "Domani sono previsti temporali."
            WeatherHazard.HEAVY_RAIN -> "Domani è prevista pioggia intensa."
            WeatherHazard.RAIN_EXPECTED -> "Domani è prevista pioggia."
            WeatherHazard.NO_ALERT -> error("unreachable — guarded by require() above")
        }
        return ProactiveSuggestion(
            kind = ProactiveKind.WEATHER_ALERT,
            message = message,
            priority = 70,
            dedupKey = "${ProactiveKind.WEATHER_ALERT}:$targetDate",
        )
    }

    /** Convenience for the caller: all candidates derived purely from [snapshot]. */
    fun candidates(snapshot: ProactiveDigestSnapshot): List<ProactiveSuggestion> =
        listOfNotNull(
            morningDigest(snapshot),
            batteryBeforeAlarm(snapshot),
            eveningDigest(snapshot),
        )
}
