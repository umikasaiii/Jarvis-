package com.simone.jarvismobile.core.bench

import kotlin.test.Test
import kotlin.test.assertEquals

class CitationResolverTest {
    private val cands = listOf(
        CitationResolver.Candidate("a.fiume#lead", "Fiume (Fixture A)"),
        CitationResolver.Candidate("a.fiume#Portata", "Fiume (Fixture A)"),
        CitationResolver.Candidate("a.monte#lead", "Monte (Fixture A)"),
    )

    @Test fun uniqueLabelResolvesToItsPassage() {
        val r = CitationResolver.resolve(listOf("[1] Monte (Fixture A)"), cands)
        assertEquals(listOf("a.monte#lead"), r.passageIds); assertEquals(0, r.ambiguous)
    }

    @Test fun articleLabelSharedByTwoSectionsIsAmbiguousAndNeverAssigned() {
        // the footer names only the article: no section may be invented, whatever the gold says
        val r = CitationResolver.resolve(listOf("[1] Fiume (Fixture A)"), cands)
        assertEquals(emptyList(), r.passageIds); assertEquals(1, r.ambiguous)
    }

    @Test fun explicitPassageHintDisambiguatesByRetrievalOrder() {
        val r = CitationResolver.resolve(listOf("[1] Fiume (Fixture A) (brano E2)"), cands)
        assertEquals(listOf("a.fiume#Portata"), r.passageIds)
    }

    @Test fun unknownLabelIsUnresolved() {
        val r = CitationResolver.resolve(listOf("[1] Altro (Fixture B)"), cands)
        assertEquals(1, r.unresolved); assertEquals(emptyList(), r.passageIds)
    }

    @Test fun ambiguousCitationFailsScoringEvenWithTheRightArticle() {
        val c = BenchCase(id = "x", category = "c", turns = listOf("q"), source = "s", sourceVersion = "1", criterion = "KNOWLEDGE_ANSWER", split = "DEV",
            requiredClaims = listOf("8 metri"), supportingEvidence = listOf("a.fiume#Portata"), expectedCitations = listOf("a.fiume#Portata"))
        val wrongSection = BenchOutcome("La portata è 8 metri [1]", "MODEL", listOf("a.fiume#lead", "a.fiume#Portata"), listOf("a.fiume#lead"))
        assertEquals(false, BenchScoring.score(c, wrongSection).dims[Dim.CITATION_VALID])
        val ambiguous = BenchOutcome("La portata è 8 metri [1]", "MODEL", listOf("a.fiume#lead", "a.fiume#Portata"), emptyList(), citationsAmbiguous = 1)
        assertEquals(false, BenchScoring.score(c, ambiguous).dims[Dim.CITATION_VALID])
        val right = BenchOutcome("La portata è 8 metri [1]", "MODEL", listOf("a.fiume#lead", "a.fiume#Portata"), listOf("a.fiume#Portata"))
        assertEquals(true, BenchScoring.score(c, right).dims[Dim.CITATION_VALID])
    }
}
