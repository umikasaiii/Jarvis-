package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.protocol.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.5-bis AUDIT PARSE ERROR — pins the exact classification a caller
 * (`JarvisBrain`/`ConversationalJarvisEngine`) relies on to distinguish a
 * genuine protocol failure from the model correctly answering in plain text.
 */
class ParseOutcomeTest {

    @Test
    fun `a clean decode is VALID`() {
        val result = ParseResult.Valid(AssistantResponse(assistantText = "ok"))
        assertEquals(ParseOutcome.VALID, ParseOutcome.fromParseResult(result))
    }

    @Test
    fun `a repaired decode is REPAIRED`() {
        val result = ParseResult.Repaired(AssistantResponse(assistantText = "ok"))
        assertEquals(ParseOutcome.REPAIRED, ParseOutcome.fromParseResult(result))
    }

    @Test
    fun `plain text with no attempted JSON is PLAIN_TEXT, not an error`() {
        val result = ParseResult.PlainText("Ciao!", looksLikeAttemptedJson = false)
        assertEquals(ParseOutcome.PLAIN_TEXT, ParseOutcome.fromParseResult(result))
    }

    @Test
    fun `plain text from an attempted-but-unrepairable JSON is MALFORMED_JSON`() {
        val result = ParseResult.PlainText("{broken", looksLikeAttemptedJson = true)
        assertEquals(ParseOutcome.MALFORMED_JSON, ParseOutcome.fromParseResult(result))
    }
}
