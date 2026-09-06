package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * § FASE 2A.11 §12 — the corpus itself is data, but its STRUCTURE (parses,
 * has real coverage per class, has no accidental split leakage) is a real
 * regression to guard: a future edit that breaks the JSON or silently
 * removes a whole intent's examples should fail a test, not surface only as
 * a device-side classification regression weeks later.
 */
class PrototypeCorpusCodecTest {

    private val corpus = TestCorpusFixture.corpus

    @Test
    fun `the real shipped corpus parses without error and is non-trivial`() {
        assertTrue(corpus.prototypes.size >= 100, "expected a substantial hand-authored corpus, got ${corpus.prototypes.size}")
    }

    @Test
    fun `no exact-duplicate text leaks across train validation and test splits`() {
        corpus.assertNoExactDuplicateAcrossSplits()
    }

    @Test
    fun `every core intent has real coverage, not just one token example`() {
        val byIntent = corpus.prototypes.groupBy { it.intent }
        listOf(
            SemanticIntent.CONVERSATION,
            SemanticIntent.CAPABILITY_QUERY,
            SemanticIntent.KNOWLEDGE_QUERY,
            SemanticIntent.MULTI_SOURCE_REASONING,
            SemanticIntent.CLARIFICATION,
        ).forEach { intent ->
            val count = byIntent[intent]?.size ?: 0
            assertTrue(count >= 5, "intent $intent has only $count prototypes")
        }
    }

    @Test
    fun `every core domain has real coverage`() {
        val byDomain = mutableMapOf<ToolFamily, Int>()
        corpus.prototypes.forEach { p -> p.domains.forEach { d -> byDomain[d] = (byDomain[d] ?: 0) + 1 } }
        listOf(ToolFamily.WEATHER, ToolFamily.HEALTH, ToolFamily.AGENDA, ToolFamily.DEVICE_INFO, ToolFamily.KNOWLEDGE).forEach { domain ->
            val count = byDomain[domain] ?: 0
            assertTrue(count >= 8, "domain $domain has only $count prototypes")
        }
    }

    @Test
    fun `at least one multi-domain (multi-label) prototype exists per required combination`() {
        val multiDomainSets = corpus.prototypes.filter { it.domains.size >= 2 }.map { it.domains }
        assertTrue(multiDomainSets.any { it == setOf(ToolFamily.HEALTH, ToolFamily.AGENDA) })
        assertTrue(multiDomainSets.any { it == setOf(ToolFamily.WEATHER, ToolFamily.AGENDA) })
        assertTrue(multiDomainSets.any { it == setOf(ToolFamily.WEATHER, ToolFamily.HEALTH) })
    }

    @Test
    fun `the RAM-vs-VRAM hard negative pair from the spec is present`() {
        val texts = corpus.prototypes.map { it.text.lowercase() }
        assertTrue(texts.any { "che differenza" in it && "vram" in it })
        assertTrue(texts.any { "quanta ram ha" in it })
        assertTrue(texts.any { "quanta vram ha" in it })
        assertTrue(texts.any { "come funziona la vram" in it })
    }

    @Test
    fun `every split is actually used`() {
        val splits = corpus.prototypes.map { it.split }.toSet()
        assertTrue(splits.containsAll(setOf("train", "validation", "test")))
    }
}
