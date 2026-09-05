package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SemanticOutputParserTest {

    private fun valid(raw: String): SemanticFrame {
        val result = SemanticOutputParser.parse(raw)
        return assertIs<SemanticInterpretation.Valid>(result).frame
    }

    @Test
    fun `a well-formed weather line parses to a valid capability query frame`() {
        val frame = valid("SFV1|CAPABILITY_QUERY|WEATHER|GET|domani|-|-|NONE|0.9")
        assertEquals(SemanticIntent.CAPABILITY_QUERY, frame.intent)
        assertEquals(setOf(ToolFamily.WEATHER), frame.domains)
        assertEquals(SemanticOperation.GET, frame.operation)
        assertEquals("domani", frame.temporalExpression)
        assertEquals(ReferenceMode.NONE, frame.referenceMode)
        assertEquals(0.9, frame.confidence)
        assertTrue(SemanticSlot.DOMAINS in frame.explicitSlots)
        assertTrue(SemanticSlot.OPERATION in frame.explicitSlots)
        assertTrue(SemanticSlot.TEMPORAL_EXPRESSION in frame.explicitSlots)
        assertTrue(SemanticSlot.METRIC !in frame.explicitSlots)
    }

    @Test
    fun `a domain-less ellipsis follow-up parses with no explicit domain slot`() {
        val frame = valid("SFV1|CAPABILITY_QUERY|-|GET|dopodomani|-|-|ELLIPSIS|0.85")
        assertEquals(emptySet(), frame.domains)
        assertTrue(SemanticSlot.DOMAINS !in frame.explicitSlots)
        assertEquals(ReferenceMode.ELLIPSIS, frame.referenceMode)
    }

    @Test
    fun `multi-domain multi-source line parses both domains`() {
        val frame = valid("SFV1|MULTI_SOURCE_REASONING|HEALTH,AGENDA|RECOMMEND|domani|-|-|NONE|0.8")
        assertEquals(setOf(ToolFamily.HEALTH, ToolFamily.AGENDA), frame.domains)
    }

    @Test
    fun `wrong field count is invalid`() {
        val result = SemanticOutputParser.parse("SFV1|CAPABILITY_QUERY|WEATHER")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `wrong schema marker is invalid`() {
        val result = SemanticOutputParser.parse("SFV2|CAPABILITY_QUERY|WEATHER|GET|-|-|-|NONE|0.9")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `an unrecognized intent token is invalid, never silently coerced`() {
        val result = SemanticOutputParser.parse("SFV1|SOMETHING_ELSE|WEATHER|GET|-|-|-|NONE|0.9")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `an unparsable confidence is invalid`() {
        val result = SemanticOutputParser.parse("SFV1|CAPABILITY_QUERY|WEATHER|GET|-|-|-|NONE|alta")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `an unknown domain token is dropped, not fatal on its own`() {
        val result = SemanticOutputParser.parse("SFV1|CAPABILITY_QUERY|SPACESHIP|GET|-|-|-|NONE|0.5")
        // Falls back to the "no domain, no reference" invalid case from the
        // validator — proving the bad token was dropped rather than crashing
        // the whole parse, and that an empty result still fails safe.
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `garbage text entirely unrelated to the schema is invalid`() {
        val result = SemanticOutputParser.parse("Ciao Simone, come posso aiutarti oggi?")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `a truncated json fragment from a confused model is invalid, never partially trusted`() {
        val result = SemanticOutputParser.parse("{\"assistant_text\":\"...")
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `only the first non-blank line is read, tolerating a leading blank line`() {
        val frame = valid("\nSFV1|CAPABILITY_QUERY|WEATHER|GET|domani|-|-|NONE|0.9\nqualcos'altro")
        assertEquals(setOf(ToolFamily.WEATHER), frame.domains)
    }
}
