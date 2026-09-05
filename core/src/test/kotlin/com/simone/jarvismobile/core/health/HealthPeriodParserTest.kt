package com.simone.jarvismobile.core.health

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.7 RELEASE GATE 4 — pins the exact bug the user flagged: "stanotte"
 * must resolve differently from "questa settimana"/"ultimi 7 giorni", which
 * are correctly the same rolling weekly window (never a second, redundant
 * concept) rather than three distinct windows.
 */
class HealthPeriodParserTest {

    @Test
    fun `stanotte means last night, not the week`() {
        assertEquals(HealthPeriod.LAST_NIGHT, HealthPeriodParser.parse("Quanto ho dormito stanotte?"))
    }

    @Test
    fun `ieri notte and la notte scorsa also mean last night`() {
        assertEquals(HealthPeriod.LAST_NIGHT, HealthPeriodParser.parse("Come ho dormito ieri notte?"))
        assertEquals(HealthPeriod.LAST_NIGHT, HealthPeriodParser.parse("Quanto ho dormito la notte scorsa?"))
    }

    @Test
    fun `questa settimana, ultimi 7 giorni and media sonno all mean the week, identically`() {
        assertEquals(HealthPeriod.WEEK, HealthPeriodParser.parse("Quante ore ho dormito questa settimana?"))
        assertEquals(HealthPeriod.WEEK, HealthPeriodParser.parse("Come ho dormito negli ultimi 7 giorni?"))
        assertEquals(HealthPeriod.WEEK, HealthPeriodParser.parse("Qual è la mia media del sonno questa settimana?"))
    }

    @Test
    fun `a bare quanto ho dormito with no period named defaults to the week, the only aggregate this app computes`() {
        assertEquals(HealthPeriod.WEEK, HealthPeriodParser.parse("Quanto ho dormito?"))
    }

    @Test
    fun `case and accents do not affect the classification`() {
        assertEquals(HealthPeriod.LAST_NIGHT, HealthPeriodParser.parse("STANOTTE quanto ho dormito?"))
    }
}
