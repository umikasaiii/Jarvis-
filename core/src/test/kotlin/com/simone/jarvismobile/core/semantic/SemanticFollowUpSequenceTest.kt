package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.agenda.TemporalScope
import com.simone.jarvismobile.core.agenda.TemporalScopeResolver
import com.simone.jarvismobile.core.tools.ToolFamily
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.10 test category D — "agenda/weather/health follow-up sequences
 * as end-to-end scenario tests, not just the individual pieces". Each test
 * chains several real turns (merge → route, feeding one turn's merged frame
 * as the next turn's `previous`, exactly the way `ConversationManager`'s
 * single remembered frame works in the real engine — see
 * `ConversationalJarvisEngine.runSemanticPath`'s own doc comment) instead of
 * only asserting on two hand-picked frames in isolation, the way
 * [SemanticFrameMergerTest] already does. The HEALTH→WEATHER sequence below
 * is the exact "Domani farà caldo?" bug report reproduced turn-by-turn, not
 * just its two-frame reduction.
 */
class SemanticFollowUpSequenceTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 3, 10, 0) // a Thursday

    private fun frame(
        intent: SemanticIntent = SemanticIntent.CAPABILITY_QUERY,
        domains: Set<ToolFamily> = emptySet(),
        operation: SemanticOperation = SemanticOperation.GET,
        temporal: String? = null,
        metric: String? = null,
        aggregation: String? = null,
        reference: ReferenceMode = ReferenceMode.NONE,
        explicit: Set<SemanticSlot> = emptySet(),
        confidence: Double = 0.85,
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

    /** One step of a scripted conversation: the raw (pre-merge) frame the interpreter would have produced this turn. */
    private class Sequence {
        var previous: SemanticFrame? = null
        val merged = mutableListOf<SemanticFrame>()
        val routed = mutableListOf<SemanticRoutingOutcome>()

        fun step(raw: SemanticFrame) {
            val validated = SemanticFrameValidator.validate(raw)
            check(validated is SemanticInterpretation.Valid) { "test frame must validate: $raw" }
            val result = SemanticFrameMerger.merge(validated.frame, previous)
            previous = result.frame
            merged += result.frame
            routed += SemanticRouter.routeFrame(result.frame)
        }
    }

    // --- AGENDA: domani → dopodomani → venerdì → an explicit range --------

    @Test
    fun `AGENDA follow-up sequence keeps the domain across four turns and always routes directly`() {
        val seq = Sequence()
        seq.step(frame(domains = setOf(ToolFamily.AGENDA), temporal = "domani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, temporal = "dopodomani", explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, temporal = "venerdì", explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, temporal = "tra oggi e venerdì", explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION)))

        assertEquals(4, seq.merged.size)
        seq.merged.forEach { assertEquals(setOf(ToolFamily.AGENDA), it.domains) }
        assertEquals(listOf("domani", "dopodomani", "venerdì", "tra oggi e venerdì"), seq.merged.map { it.temporalExpression })
        seq.routed.forEach { assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.AGENDA), it) }

        // The last turn's temporal expression is a genuine range shape,
        // resolvable by TemporalScopeResolver into a real [start, end] —
        // proving the range support this phase added is reachable end-to-end
        // from the merged semantic frame, not just from raw transcript text.
        val scope = TemporalScopeResolver.resolve(seq.merged.last().temporalExpression!!, now)
        assertTrue(scope is TemporalScope.Range)
        assertEquals(LocalDate.of(2026, 9, 3), (scope as TemporalScope.Range).start) // oggi
        assertEquals(LocalDate.of(2026, 9, 4), scope.end) // venerdì
    }

    // --- WEATHER: oggi → domani → an explicit re-assertion -----------------

    @Test
    fun `WEATHER follow-up sequence keeps the domain and updates only the date each turn`() {
        val seq = Sequence()
        seq.step(frame(domains = setOf(ToolFamily.WEATHER), temporal = "oggi", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, temporal = "domani", explicit = setOf(SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(domains = setOf(ToolFamily.WEATHER), temporal = "dopodomani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION)))

        seq.merged.forEach { assertEquals(setOf(ToolFamily.WEATHER), it.domains) }
        assertEquals(listOf("oggi", "domani", "dopodomani"), seq.merged.map { it.temporalExpression })
        seq.routed.forEach { assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.WEATHER), it) }
    }

    // --- HEALTH: sonno/totale → media → battito -----------------------------

    @Test
    fun `HEALTH follow-up sequence inherits metric and range, then changes only what the turn itself asserts`() {
        val seq = Sequence()
        seq.step(
            frame(
                domains = setOf(ToolFamily.HEALTH), metric = "sonno", temporal = "questa settimana", aggregation = "totale",
                explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC, SemanticSlot.TEMPORAL_EXPRESSION, SemanticSlot.AGGREGATION),
            ),
        )
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, aggregation = "media", explicit = setOf(SemanticSlot.AGGREGATION)))
        seq.step(frame(reference = ReferenceMode.ELLIPSIS, metric = "battito", explicit = setOf(SemanticSlot.METRIC)))

        assertEquals(setOf(ToolFamily.HEALTH), seq.merged[1].domains)
        assertEquals("sonno", seq.merged[1].metric)
        assertEquals("media", seq.merged[1].aggregation) // only this turn's own slot changed

        assertEquals(setOf(ToolFamily.HEALTH), seq.merged[2].domains)
        assertEquals("battito", seq.merged[2].metric) // this turn's own explicit metric wins
        assertEquals("media", seq.merged[2].aggregation) // inherited from turn 2, never reset
        seq.routed.forEach { assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.HEALTH), it) }
    }

    // --- The exact reported bug, reproduced as a real turn sequence --------

    @Test
    fun `a HEALTH turn never leaks its domain into a later explicit WEATHER turn - the reported caldo bug, end-to-end`() {
        val seq = Sequence()
        seq.step(
            frame(
                domains = setOf(ToolFamily.HEALTH), metric = "sonno", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC),
            ),
        )
        // "Domani farà caldo?" — WEATHER, self-sufficient, mentions "domani"
        // but asserts its own domain explicitly; it must never be reclassified
        // as a HEALTH follow-up just because "domani" looks date-shaped.
        seq.step(frame(domains = setOf(ToolFamily.WEATHER), temporal = "domani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION)))

        val weatherTurn = seq.merged[1]
        assertEquals(setOf(ToolFamily.WEATHER), weatherTurn.domains)
        assertTrue(ToolFamily.HEALTH !in weatherTurn.domains)
        assertEquals(null, weatherTurn.metric) // HEALTH's "sonno" never leaks into the WEATHER frame
        assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.WEATHER), seq.routed[1])
    }

    // --- Cross-domain interleaving: AGENDA, then HEALTH, then back to AGENDA ellipsis --

    @Test
    fun `an AGENDA ellipsis after an intervening HEALTH turn inherits HEALTH, not the older AGENDA turn`() {
        val seq = Sequence()
        seq.step(frame(domains = setOf(ToolFamily.AGENDA), temporal = "domani", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION)))
        seq.step(frame(domains = setOf(ToolFamily.HEALTH), metric = "sonno", explicit = setOf(SemanticSlot.DOMAINS, SemanticSlot.METRIC)))
        // A bare ellipsis now — since ConversationManager only remembers ONE
        // previous frame (§ FASE 2A.9 doc comment), this correctly inherits
        // the MOST RECENT frame (HEALTH), never reaching back past it to the
        // older AGENDA turn — the single-remembered-frame design's own
        // documented behavior, pinned here as a real sequence.
        seq.step(frame(reference = ReferenceMode.ELLIPSIS))

        assertEquals(setOf(ToolFamily.HEALTH), seq.merged[2].domains)
        assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.HEALTH), seq.routed[2])
    }
}
