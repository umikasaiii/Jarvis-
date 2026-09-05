package com.simone.jarvismobile.core.health

import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * § FASE 2A.8 RELEASE GATE D — real bug confirmed on-device: FASE 2A.7's
 * `HealthPeriodParser` only distinguished LAST_NIGHT vs. WEEK, so "Quante ore
 * ho dormito questa settimana?" (a TOTAL) and "Qual è la media del sonno
 * questa settimana?" (an AVERAGE) both landed on the same weekly-average
 * answer, and "Quanto ho dormito il 2 settembre?" (a SPECIFIC PAST NIGHT)
 * also fell back to the weekly aggregate since no specific-date support
 * existed at all. This replaces that parser with a genuinely generalized
 * METRIC + RANGE + AGGREGATION split — never hardcoded per phrase.
 */
enum class HealthMetric { SLEEP_DURATION, RESTING_HEART_RATE }

enum class HealthAggregation { TOTAL, AVERAGE }

sealed interface HealthRange {
    /** One specific calendar night/day — "stanotte"/"ieri"/"il 2 settembre" all resolve here, never averaged with the rest of the week. */
    data class Night(val date: LocalDate) : HealthRange

    /** The existing rolling weekly window ([HealthDailySeries.queryRange]) — "questa settimana"/"ultimi 7 giorni"/no range named all mean this SAME window, not three different ones. */
    data object Week : HealthRange
}

data class HealthQuerySpec(val metric: HealthMetric, val range: HealthRange, val aggregation: HealthAggregation)

object HealthQueryParser {
    private val heartRatePatterns = listOf(
        "battito", "battiti", "bpm", "frequenza cardiaca", "cuore",
    ).map { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }

    private val averagePatterns = listOf(
        "media", "in media", "mediamente",
    ).map { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }

    /**
     * [text]/[now] go through the same [ItalianDateTimeParser] AGENDA already
     * uses — reused, never a second date parser — but with one deliberate,
     * health-specific correction: that parser's explicit day+month branch
     * (no year given) rolls a date already in the past FORWARD into next
     * year, correct for AGENDA (a future reminder) but wrong for HEALTH,
     * where a date can never legitimately be in the future — no sleep/heart-
     * rate data can exist for a night that has not happened yet. Any date
     * [ItalianDateTimeParser] resolves to the future is therefore always
     * reinterpreted as the most recent PAST occurrence of that day+month
     * instead (one year back — "il 2 settembre" asked on 5 settembre means
     * the 2nd that just happened; "il 20 dicembre" asked in September, before
     * this year's 20 dicembre has occurred, means last year's).
     */
    fun parse(text: String, now: LocalDateTime): HealthQuerySpec {
        val metric = if (heartRatePatterns.any { it.containsMatchIn(text) }) {
            HealthMetric.RESTING_HEART_RATE
        } else {
            HealthMetric.SLEEP_DURATION
        }
        val aggregation = if (averagePatterns.any { it.containsMatchIn(text) }) {
            HealthAggregation.AVERAGE
        } else {
            HealthAggregation.TOTAL
        }
        val parsed = ItalianDateTimeParser.parse(text, now)
        val range = if (parsed.dateExplicit && parsed.date != null) {
            val today = now.toLocalDate()
            val date = if (parsed.date.isAfter(today)) parsed.date.minusYears(1) else parsed.date
            HealthRange.Night(date)
        } else {
            HealthRange.Week
        }
        return HealthQuerySpec(metric, range, aggregation)
    }
}
