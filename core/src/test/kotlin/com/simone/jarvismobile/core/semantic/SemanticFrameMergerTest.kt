package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § FASE 2A.9 §16 A/B — INVARIANT tests, not phrase-specific ones: the same
 * shape of frame must merge the same way regardless of which real sentence
 * produced it. This is the actual release gate for the phase's success
 * criterion ("a new phrase never seen before still routes by meaning").
 */
class SemanticFrameMergerTest {

    private fun frame(
        intent: SemanticIntent = SemanticIntent.CAPABILITY_QUERY,
        domains: Set<ToolFamily> = emptySet(),
        operation: SemanticOperation = SemanticOperation.GET,
        temporal: String? = null,
        metric: String? = null,
        aggregation: String? = null,
        reference: ReferenceMode = ReferenceMode.NONE,
        explicit: Set<SemanticSlot> = emptySet(),
        confidence: Double = 0.8,
    ) = SemanticFrame(
        intent = intent,
        domains = domains,
        operation = operation,
        temporalExpression = temporal,
        metric = metric,
        aggregation = aggregation,
        entities = emptyList(),
        referenceMode = reference,
        requiresGrounding = domains.isNotEmpty(),
        confidence = confidence,
        explicitSlots = explicit,
    )

    // --- A) CURRENT TURN OVERRIDES CONTEXT -----------------------------------

    @Test
    fun `previous HEALTH never survives an explicit current WEATHER domain`() {
        val previous = frame(domains = setOf(ToolFamily.HEALTH), explicit = setOf(SemanticSlot.DOMAINS))
        val current = frame(
            domains = setOf(ToolFamily.WEATHER),
            temporal = "domani",
            explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.WEATHER), result.frame.domains)
        assertTrue(ToolFamily.HEALTH !in result.frame.domains)
        assertTrue(result.currentOverridesPrevious)
        assertTrue(SemanticSlot.DOMAINS !in result.inheritedSlots)
    }

    @Test
    fun `previous WEATHER never survives an explicit current HEALTH domain`() {
        val previous = frame(domains = setOf(ToolFamily.WEATHER), explicit = setOf(SemanticSlot.DOMAINS))
        val current = frame(domains = setOf(ToolFamily.HEALTH), explicit = setOf(SemanticSlot.DOMAINS))
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.HEALTH), result.frame.domains)
    }

    @Test
    fun `previous AGENDA never survives an explicit current DEVICE_INFO domain`() {
        val previous = frame(domains = setOf(ToolFamily.AGENDA), explicit = setOf(SemanticSlot.DOMAINS))
        val current = frame(domains = setOf(ToolFamily.DEVICE_INFO), metric = "ram", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC))
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.DEVICE_INFO), result.frame.domains)
        assertEquals("ram", result.frame.metric)
    }

    // --- B) SLOT INHERITANCE --------------------------------------------------

    @Test
    fun `AGENDA tomorrow then a bare day-after-tomorrow follow-up inherits only the domain`() {
        val previous = frame(
            domains = setOf(ToolFamily.AGENDA),
            temporal = "domani",
            explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val current = frame(
            domains = emptySet(),
            temporal = "dopodomani",
            reference = ReferenceMode.ELLIPSIS,
            explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.AGENDA), result.frame.domains)
        assertEquals("dopodomani", result.frame.temporalExpression) // current's own word wins, never overwritten
        assertTrue(SemanticSlot.DOMAINS in result.inheritedSlots)
        assertTrue(SemanticSlot.TEMPORAL_EXPRESSION !in result.inheritedSlots)
    }

    @Test
    fun `AGENDA tomorrow then a next-week-range follow-up inherits domain, keeps the new range`() {
        val previous = frame(domains = setOf(ToolFamily.AGENDA), temporal = "domani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION))
        val current = frame(
            domains = emptySet(),
            temporal = "la settimana prossima",
            reference = ReferenceMode.ELLIPSIS,
            explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.AGENDA), result.frame.domains)
        assertEquals("la settimana prossima", result.frame.temporalExpression)
    }

    @Test
    fun `HEALTH sleep this-week total then a bare average follow-up inherits domain metric and range, changes only aggregation`() {
        val previous = frame(
            domains = setOf(ToolFamily.HEALTH),
            metric = "sonno",
            temporal = "questa settimana",
            aggregation = "totale",
            explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC, SemanticSlot.TEMPORAL_EXPRESSION, SemanticSlot.AGGREGATION),
        )
        val current = frame(
            domains = emptySet(),
            aggregation = "media",
            reference = ReferenceMode.ELLIPSIS,
            explicit = setOf(SemanticSlot.AGGREGATION),
        )
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.HEALTH), result.frame.domains)
        assertEquals("sonno", result.frame.metric)
        assertEquals("questa settimana", result.frame.temporalExpression)
        assertEquals("media", result.frame.aggregation)
    }

    @Test
    fun `HEALTH sleep this-week total then a bare last-week follow-up inherits domain metric and aggregation, changes only range`() {
        val previous = frame(
            domains = setOf(ToolFamily.HEALTH),
            metric = "sonno",
            temporal = "questa settimana",
            aggregation = "totale",
            explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC, SemanticSlot.TEMPORAL_EXPRESSION, SemanticSlot.AGGREGATION),
        )
        val current = frame(
            domains = emptySet(),
            temporal = "la settimana scorsa",
            reference = ReferenceMode.ELLIPSIS,
            explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals("sonno", result.frame.metric)
        assertEquals("totale", result.frame.aggregation)
        assertEquals("la settimana scorsa", result.frame.temporalExpression)
    }

    @Test
    fun `a fully self-sufficient WEATHER question never inherits from a HEALTH previous frame`() {
        val previous = frame(domains = setOf(ToolFamily.HEALTH), metric = "sonno", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC))
        val current = frame(domains = setOf(ToolFamily.WEATHER), temporal = "domani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION))
        val result = SemanticFrameMerger.merge(current, previous)
        assertEquals(setOf(ToolFamily.WEATHER), result.frame.domains)
        assertEquals(null, result.frame.metric) // HEALTH's metric never leaks into an unrelated WEATHER frame
        assertTrue(result.inheritedSlots.isEmpty())
    }

    @Test
    fun `no previous frame means nothing is inherited at all`() {
        val current = frame(domains = emptySet(), reference = ReferenceMode.NONE)
        val result = SemanticFrameMerger.merge(current, null)
        assertEquals(current, result.frame)
        assertTrue(result.inheritedSlots.isEmpty())
        assertFalse(result.currentOverridesPrevious)
    }

    @Test
    fun `CONVERSATION intent never inherits a domain even with an empty domain set`() {
        val previous = frame(domains = setOf(ToolFamily.HEALTH), explicit = setOf(SemanticSlot.DOMAINS))
        val current = frame(intent = SemanticIntent.CONVERSATION, domains = emptySet(), operation = SemanticOperation.UNKNOWN)
        val result = SemanticFrameMerger.merge(current, previous)
        assertTrue(result.frame.domains.isEmpty())
    }
}
