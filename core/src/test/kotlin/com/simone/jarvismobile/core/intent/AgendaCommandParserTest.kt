package com.simone.jarvismobile.core.intent

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planner CRUD parser is the one place where misunderstanding rewrites or
 * deletes real user data, so it is tested on the shapes people actually speak:
 * aliases, word order, colloquial imperatives, and the collisions between them.
 */
class AgendaCommandParserTest {

    // Thursday 6 August 2026, 10:00 — a fixed "now" so weekday maths is stable.
    private val now = LocalDateTime.of(2026, 8, 6, 10, 0)

    private fun parse(s: String) = AgendaCommandParser.parse(s, now)

    // --- DELETE --------------------------------------------------------

    @Test
    fun deleteAliasesAllReachTheSameIntent() {
        val phrases = listOf(
            "elimina l'appuntamento dal dentista",
            "cancella l'appuntamento dal dentista",
            "rimuovi l'appuntamento dal dentista",
            "togli l'appuntamento dal dentista",
            "disdici l'appuntamento dal dentista",
            "Elimina L'Appuntamento Dal Dentista!",
            "puoi eliminare l'appuntamento dal dentista, per favore?",
            "jarvis, elimina l'appuntamento dal dentista",
        )
        for (p in phrases) {
            val intent = assertNotNull(parse(p), "no intent for: $p")
            assertEquals(Domain.AGENDA, intent.domain, p)
            assertEquals(Action.DELETE, intent.action, p)
            assertTrue(intent.target.needle.contains("dentista"), "needle for '$p' = ${intent.target.needle}")
        }
    }

    @Test
    fun deleteKeepsTheDayThatIdentifiesTheEntry() {
        val intent = assertNotNull(parse("cancella dentista di domani"))
        assertEquals(Action.DELETE, intent.action)
        assertEquals("dentista", intent.target.needle)
        assertEquals("2026-08-07", intent.target.date)
    }

    @Test
    fun deleteByTimeAloneKeepsTheHourAsTheQualifier() {
        val intent = assertNotNull(parse("togli l'impegno delle 18"))
        assertEquals(Action.DELETE, intent.action)
        assertEquals("18:00", intent.target.time)
    }

    @Test
    fun deleteKeepsThePlannerNounWhenItIsAllTheUserSaid() {
        val intent = assertNotNull(parse("rimuovi la riunione di lunedì"))
        assertEquals(Action.DELETE, intent.action)
        assertTrue(intent.target.needle.contains("riunione"), intent.target.needle)
        assertEquals("2026-08-10", intent.target.date)
    }

    @Test
    fun verbAtTheEndWithEncliticStillDeletes() {
        val intent = assertNotNull(parse("domani dentista, toglilo"))
        assertEquals(Action.DELETE, intent.action)
        assertTrue(intent.target.needle.contains("dentista"), intent.target.needle)
    }

    @Test
    fun demonstrativePlusEncliticIsUnderstood() {
        val intent = assertNotNull(parse("quello del dentista cancellalo"))
        assertEquals(Action.DELETE, intent.action)
        assertTrue(intent.target.needle.contains("dentista"), intent.target.needle)
        assertTrue(intent.target.fromContext)
    }

    @Test
    fun statedAsAPreferenceIsStillADeletion() {
        val intent = assertNotNull(parse("non voglio più l'appuntamento dal dentista domani"))
        assertEquals(Action.DELETE, intent.action)
        assertTrue(intent.target.needle.contains("dentista"), intent.target.needle)
    }

    @Test
    fun bareDestructiveVerbWithNoTargetIsRefused() {
        // Nothing names what to remove and there is no pronoun: must not resolve
        // to a delete that the app could then apply to an arbitrary entry.
        assertNull(parse("elimina"))
        assertNull(parse("cancella"))
    }

    // --- MOVE ----------------------------------------------------------

    @Test
    fun moveAliasesAllReachTheSameIntent() {
        val phrases = listOf(
            "sposta il dentista a venerdì",
            "rimanda il dentista a venerdì",
            "posticipa il dentista a venerdì",
            "riprogramma il dentista a venerdì",
            "porta il dentista a venerdì",
        )
        for (p in phrases) {
            val intent = assertNotNull(parse(p), "no intent for: $p")
            assertEquals(Action.MOVE, intent.action, p)
            assertTrue(intent.target.needle.contains("dentista"), "$p → ${intent.target.needle}")
            assertEquals("2026-08-07", intent.changes.date, p)
        }
    }

    @Test
    fun theTargetsOwnDayIsNotConfusedWithTheDestination() {
        // "di domani" identifies the entry; "a lunedì" is where it goes.
        val intent = assertNotNull(parse("porta l'appuntamento di domani a lunedì"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals("2026-08-07", intent.target.date, "target day")
        assertEquals("2026-08-10", intent.changes.date, "destination day")
    }

    @Test
    fun moveToANewTimeKeepsTheTargetName() {
        val intent = assertNotNull(parse("rimanda la riunione alle 17"))
        assertEquals(Action.MOVE, intent.action)
        assertTrue(intent.target.needle.contains("riunione"), intent.target.needle)
        assertEquals("17:00", intent.changes.time)
        assertNull(intent.changes.date)
    }

    @Test
    fun anticipateIsAMoveToAnEarlierTime() {
        val intent = assertNotNull(parse("anticipa il dentista alle 15"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals("15:00", intent.changes.time)
    }

    @Test
    fun relativeShiftIsReadAsMinutesNotAsATarget() {
        val intent = assertNotNull(parse("sposta l'evento di Marco di due ore"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals(120L, intent.changes.shiftMinutes)
        assertTrue(intent.target.needle.contains("marco"), intent.target.needle)
    }

    @Test
    fun anticipateShiftsBackwards() {
        val intent = assertNotNull(parse("anticipalo di mezz'ora"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals(-30L, intent.changes.shiftMinutes)
        assertTrue(intent.target.fromContext)
    }

    @Test
    fun shiftByADayIsSupported() {
        val intent = assertNotNull(parse("rimanda la riunione di un giorno"))
        assertEquals(1440L, intent.changes.shiftMinutes)
    }

    @Test
    fun contextualMoveWithoutANamedTarget() {
        val intent = assertNotNull(parse("spostalo alle 16"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals("16:00", intent.changes.time)
        assertTrue(intent.target.fromContext, "must be flagged as needing context")
        assertTrue(intent.target.needle.isBlank())
    }

    @Test
    fun demonstrativeAndEncliticTogether() {
        val intent = assertNotNull(parse("quello di domani spostalo alle 16"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals("2026-08-07", intent.target.date)
        assertEquals("16:00", intent.changes.time)
    }

    @Test
    fun changingTheHourIsRoutedAsAMove() {
        val intent = assertNotNull(parse("cambia l'orario alle 16"))
        assertEquals(Action.MOVE, intent.action)
        assertEquals("16:00", intent.changes.time)
    }

    // --- UPDATE --------------------------------------------------------

    @Test
    fun renameKeepsOldAndNewSeparate() {
        val intent = assertNotNull(parse("modifica dentista in visita dentistica"))
        assertEquals(Action.UPDATE, intent.action)
        assertEquals("dentista", intent.target.needle)
        assertEquals("visita dentistica", intent.changes.title)
    }

    @Test
    fun renameWorksWithTheContextualForm() {
        val intent = assertNotNull(parse("cambialo in visita annuale"))
        assertEquals(Action.UPDATE, intent.action)
        assertEquals("visita annuale", intent.changes.title)
        assertTrue(intent.target.fromContext)
    }

    // --- COMPLETE --------------------------------------------------------

    @Test
    fun completeWithTargetBeforeTheStatusWords() {
        val intent = assertNotNull(parse("segna comprare il latte come completato"))
        assertEquals(Action.COMPLETE, intent.action)
        assertTrue(intent.target.needle.contains("comprare"), intent.target.needle)
        assertTrue(intent.target.needle.contains("latte"), intent.target.needle)
    }

    @Test
    fun completeWithStatusWordsBeforeTheTarget() {
        // The word order the user actually spoke: "segna come completata
        // l'attività X", not "segna X come completata".
        val intent = assertNotNull(parse("segna come completata l'attività sistemare le scadenze"))
        assertEquals(Action.COMPLETE, intent.action)
        assertTrue(intent.target.needle.contains("sistemare"), intent.target.needle)
        assertTrue(intent.target.needle.contains("scadenze"), intent.target.needle)
    }

    @Test
    fun completeAliasesAllReachTheSameIntent() {
        val phrases = listOf(
            "segna il dentista come fatto",
            "imposta il dentista come completato",
            "considera il dentista come concluso",
            "ho completato il dentista",
            "ho fatto il dentista",
            "ho finito il dentista",
            "finito il dentista",
            "spunta il dentista",
        )
        for (p in phrases) {
            val intent = assertNotNull(parse(p), "no intent for: $p")
            assertEquals(Action.COMPLETE, intent.action, p)
            assertTrue(intent.target.needle.contains("dentista"), "$p → ${intent.target.needle}")
        }
    }

    @Test
    fun completionNeedsNoPlannerNounUnlikeTheGenericVerbPath() {
        // Deliberately different from the generic MOVE/DELETE/UPDATE verb path
        // above, which refuses "cambia la lingua" with no planner noun and no
        // pronoun: completion is never destructive, so "ho finito il dentista"
        // is accepted even though "dentista" alone names no planner domain —
        // the router either finds a real matching entry or says it found none.
        val intent = assertNotNull(parse("ho finito il dentista"))
        assertEquals(Action.COMPLETE, intent.action)
        assertTrue(intent.target.needle.contains("dentista"), intent.target.needle)
    }

    // --- QUERY -----------------------------------------------------------

    @Test
    fun queryAliasesAllReachTheSameIntent() {
        val phrases = listOf(
            "quando ho il dentista",
            "quando è il dentista",
            "quando devo andare dal dentista",
            "a che ora ho il dentista",
            "a che ora è il dentista",
            "che giorno ho il dentista",
        )
        for (p in phrases) {
            val intent = assertNotNull(parse(p), "no intent for: $p")
            assertEquals(Action.QUERY, intent.action, p)
            assertTrue(intent.target.needle.contains("dentista"), "$p → ${intent.target.needle}")
        }
    }

    @Test
    fun queryDropsAndareSoItDoesNotBecomeARequiredNeedleWord() {
        // "quando devo andare dal dentista" must target "dentista" alone, not
        // "andare dentista" — a real saved title ("togliere le carie dal
        // dentista e fare pulizia denti") contains "dentista" but not "andare",
        // and TextNormalizer.matches() requires every needle word to appear.
        val intent = assertNotNull(parse("quando devo andare dal dentista"))
        assertEquals(Action.QUERY, intent.action)
        assertTrue(!intent.target.needle.contains("andare"), intent.target.needle)
    }

    @Test
    fun theGeneralAgendaListingIsNotHandledHere() {
        // "che impegni ho"/"cosa devo fare oggi" stay on the existing AGENDA_RE
        // path in CommandMatcher — this parser only owns a NAMED lookup.
        assertNull(parse("che impegni ho oggi"))
        assertNull(parse("cosa devo fare oggi pomeriggio"))
    }

    // --- Collisions and refusals ---------------------------------------

    @Test
    fun anOrdinaryErrandIsNotAReschedule() {
        // "porta … a casa" uses a move verb and a destination marker but is not
        // about the planner at all.
        assertNull(parse("porta il pane a casa"))
    }

    @Test
    fun unrelatedSettingsEditIsNotAnAgendaUpdate() {
        assertNull(parse("cambia la lingua"))
        assertNull(parse("cambia la voce di jarvis"))
    }

    @Test
    fun creatingIsNotHandledHere() {
        // Creation stays with the existing CommandMatcher path; this parser must
        // not steal it, or two components would both file the same appointment.
        assertNull(parse("aggiungi appuntamento domani alle 18 dal dentista"))
    }

    @Test
    fun aPlainQuestionIsNotACommand() {
        assertNull(parse("che impegni ho domani?"))
        assertNull(parse("come stai?"))
    }

    @Test
    fun noteWordsRouteToTheNoteDomain() {
        val intent = assertNotNull(parse("elimina l'appunto sulla moto"))
        assertEquals(Domain.NOTE, intent.domain)
        assertEquals(Action.DELETE, intent.action)
        assertTrue(intent.target.needle.contains("moto"), intent.target.needle)
    }

    // --- Confidence ----------------------------------------------------

    @Test
    fun namedTargetsAreHighConfidenceAndPronounsAreNot() {
        val named = assertNotNull(parse("cancella l'appuntamento dal dentista di domani"))
        assertEquals(Confidence.HIGH, named.confidence)
        assertTrue(named.mayAct())

        val pronoun = assertNotNull(parse("cancellalo"))
        assertTrue(pronoun.confidence != Confidence.HIGH, "score=${pronoun.score}")
        // A destructive action with no named target must never self-approve.
        assertTrue(!pronoun.mayAct())
    }
}
