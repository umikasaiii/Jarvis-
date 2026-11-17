package com.simone.jarvismobile.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deterministic, lexical claim-safety rules added in the M4 safety closure. They are general (no dataset strings); their limits are documented in docs/M4_BENCHMARK.md. */
class ClaimSafetyRulesTest {
    private fun verify(passage: String, claim: String, title: String = "Voce di prova", q: String = "domanda") =
        ClaimVerifier.verify(q, claim, EvidencePackage.of(listOf(EvidencePackage.Companion.Raw(title, "", passage, "a#lead")))).claims.first().status

    @Test fun nearAcceptsInflectionButNotADifferentWord() {
        assertTrue(ClaimVerifier.near("gatt", "gatti")); assertTrue(ClaimVerifier.near("produc", "prodott"))
        assertFalse(ClaimVerifier.near("madre", "padre")); assertFalse(ClaimVerifier.near("larghezza", "lunghezza")); assertFalse(ClaimVerifier.near("2020", "2021"))
    }

    @Test fun aSwappedContentWordIsNotSeventyPercentOverlap() {
        assertEquals(ClaimStatus.UNVERIFIED, verify("L'insulina è un ormone prodotto dal pancreas.", "L'insulina è un ormone prodotto dal fegato."))
        assertEquals(ClaimStatus.EXPLICITLY_SUPPORTED, verify("L'insulina è un ormone prodotto dal pancreas.", "L'insulina è un ormone prodotto dal pancreas."))
    }

    @Test fun identifiersMustMatchExactly() {
        assertEquals(ClaimStatus.UNVERIFIED, verify("La pressione della caldaia Termo-Z è di 1,5 bar.", "La pressione della caldaia Termo-Y è di 1,5 bar."))
    }

    @Test fun aNumberBoundToAnotherPropertyIsNotSupported() {
        val p = "La stanza misura 5 metri di lunghezza e 3 metri di larghezza."
        assertTrue(verify(p, "La larghezza della stanza è di 5 metri.", "Stanza di prova") != ClaimStatus.EXPLICITLY_SUPPORTED)
        assertEquals(ClaimStatus.EXPLICITLY_SUPPORTED, verify(p, "La larghezza della stanza è di 3 metri.", "Stanza di prova"))
    }

    @Test fun rolesAroundTheSameRelationCannotBeSwapped() {
        assertTrue(ClaimVerifier.rolesSwapped(listOf("topo", "insegu", "gatt"), listOf("gatt", "insegu", "topo")))
        assertFalse(ClaimVerifier.rolesSwapped(listOf("gatt", "insegu", "topo"), listOf("gatt", "insegu", "topo")))
        assertTrue(verify("Il gatto insegue il topo.", "Il topo insegue il gatto.") != ClaimStatus.EXPLICITLY_SUPPORTED)
    }

    @Test fun negationAndAddedCauseAreNotSupported() {
        assertTrue(verify("Il pomodoro è un frutto.", "Il pomodoro non è un frutto.") != ClaimStatus.EXPLICITLY_SUPPORTED)
        assertTrue(verify("Il ponte crollò nel 1990.", "Il ponte crollò nel 1990 perché era vecchio.") != ClaimStatus.EXPLICITLY_SUPPORTED)
    }

    @Test fun aStoredFactIsVerifiedEvenWhenTheQuestionUsesAVerbTheNoteDoesNotContain() {
        val note = "Il codice del mio progetto di prova è Orione."
        val memory = EvidencePackage.of(listOf(EvidencePackage.Companion.Raw("Memoria", "", note, "n0", EvidenceKind.MEMORY)))
        val q = "Come avevo chiamato il progetto di prova?"
        assertEquals(ClaimStatus.EXPLICITLY_SUPPORTED, ClaimVerifier.verify(q, "Il codice del tuo progetto di prova è Orione.", memory).claims.first().status)
        // the claim itself must still match the note: a different value is not verified
        assertTrue(ClaimVerifier.verify(q, "Il codice del tuo progetto di prova è Sirio.", memory).claims.first().status != ClaimStatus.EXPLICITLY_SUPPORTED)
    }
}
