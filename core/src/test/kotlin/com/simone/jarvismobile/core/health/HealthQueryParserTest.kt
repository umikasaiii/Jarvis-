package com.simone.jarvismobile.core.health

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.8 RELEASE GATE D — pins the exact real-device bug: "il 2
 * settembre" (asked a few days later, in September) must resolve to THIS
 * September, not next year, and TOTAL vs. AVERAGE vs. a specific night must
 * never collapse into the same weekly-average answer.
 */
class HealthQueryParserTest {

    // Friday 5 September 2026, matching the real device report.
    private val now = LocalDateTime.of(2026, 9, 5, 9, 0)

    @Test
    fun `a specific past day-month date resolves to THIS year, not next year`() {
        val spec = HealthQueryParser.parse("Quanto ho dormito il 2 settembre?", now)
        assertEquals(HealthMetric.SLEEP_DURATION, spec.metric)
        assertEquals(HealthRange.Night(LocalDate.of(2026, 9, 2)), spec.range)
        assertEquals(HealthAggregation.TOTAL, spec.aggregation)
    }

    @Test
    fun `questa settimana without media means the week, TOTAL`() {
        val spec = HealthQueryParser.parse("Quante ore ho dormito questa settimana?", now)
        assertEquals(HealthRange.Week, spec.range)
        assertEquals(HealthAggregation.TOTAL, spec.aggregation)
    }

    @Test
    fun `media del sonno questa settimana means the week, AVERAGE - distinct from the bare total`() {
        val spec = HealthQueryParser.parse("Qual è la media del sonno questa settimana?", now)
        assertEquals(HealthRange.Week, spec.range)
        assertEquals(HealthAggregation.AVERAGE, spec.aggregation)
    }

    @Test
    fun `ultimi 7 giorni is the same week window, not a fourth concept`() {
        val spec = HealthQueryParser.parse("Come ho dormito negli ultimi 7 giorni?", now)
        assertEquals(HealthRange.Week, spec.range)
    }

    @Test
    fun `stanotte resolves to tonight's own date, never the week`() {
        val spec = HealthQueryParser.parse("Quanto ho dormito stanotte?", now)
        assertEquals(HealthRange.Night(LocalDate.of(2026, 9, 5)), spec.range)
    }

    @Test
    fun `ieri resolves to yesterday's own date`() {
        val spec = HealthQueryParser.parse("Come ho dormito ieri?", now)
        assertEquals(HealthRange.Night(LocalDate.of(2026, 9, 4)), spec.range)
    }

    @Test
    fun `heart rate wording selects the resting heart rate metric`() {
        val spec = HealthQueryParser.parse("Quanto era il mio battito questa settimana?", now)
        assertEquals(HealthMetric.RESTING_HEART_RATE, spec.metric)
    }

    @Test
    fun `no period named at all defaults to the week, the only safe aggregate`() {
        val spec = HealthQueryParser.parse("Come ho dormito?", now)
        assertEquals(HealthRange.Week, spec.range)
    }

    @Test
    fun `a day-month date still ahead this year resolves to the most recent PAST occurrence instead - health data about the future cannot exist`() {
        // 20 dicembre asked on 5 settembre: this year's 20 dicembre has not
        // happened yet, so no sleep data could possibly exist for it - the
        // only sensible reading for a retrospective health question is the
        // most recent occurrence, last year's 20 dicembre.
        val spec = HealthQueryParser.parse("Quanto ho dormito il 20 dicembre?", now)
        assertEquals(HealthRange.Night(LocalDate.of(2025, 12, 20)), spec.range)
    }
}
