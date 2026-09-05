package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertIs

class SemanticFrameValidatorTest {

    private fun frame(
        intent: SemanticIntent,
        domains: Set<ToolFamily> = emptySet(),
        confidence: Double = 0.8,
        reference: ReferenceMode = ReferenceMode.NONE,
    ) = SemanticFrame(
        intent = intent,
        domains = domains,
        operation = SemanticOperation.GET,
        temporalExpression = null,
        metric = null,
        aggregation = null,
        entities = emptyList(),
        referenceMode = reference,
        requiresGrounding = domains.isNotEmpty(),
        confidence = confidence,
        explicitSlots = if (domains.isNotEmpty()) setOf(SemanticSlot.DOMAINS) else emptySet(),
    )

    @Test
    fun `below-threshold confidence is always invalid regardless of shape`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.WEATHER), confidence = 0.1))
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `a capability query with no domain and no reference to resolve against is invalid`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.CAPABILITY_QUERY, domains = emptySet()))
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `a capability query with no domain but an ellipsis reference is valid (resolvable via merge)`() {
        val result = SemanticFrameValidator.validate(
            frame(SemanticIntent.CAPABILITY_QUERY, domains = emptySet(), reference = ReferenceMode.ELLIPSIS),
        )
        assertIs<SemanticInterpretation.Valid>(result)
    }

    @Test
    fun `knowledge query carrying a grounded domain like WEATHER is invalid`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.KNOWLEDGE_QUERY, domains = setOf(ToolFamily.WEATHER)))
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `knowledge query with the KNOWLEDGE domain itself is valid`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.KNOWLEDGE_QUERY, domains = setOf(ToolFamily.KNOWLEDGE)))
        assertIs<SemanticInterpretation.Valid>(result)
    }

    @Test
    fun `multi-source reasoning with only one domain is invalid`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.MULTI_SOURCE_REASONING, domains = setOf(ToolFamily.HEALTH)))
        assertIs<SemanticInterpretation.Invalid>(result)
    }

    @Test
    fun `multi-source reasoning with two domains is valid`() {
        val result = SemanticFrameValidator.validate(
            frame(SemanticIntent.MULTI_SOURCE_REASONING, domains = setOf(ToolFamily.HEALTH, ToolFamily.AGENDA)),
        )
        assertIs<SemanticInterpretation.Valid>(result)
    }

    @Test
    fun `a plain conversation frame with no domain is valid`() {
        val result = SemanticFrameValidator.validate(frame(SemanticIntent.CONVERSATION, domains = emptySet()))
        assertIs<SemanticInterpretation.Valid>(result)
    }
}
