package com.simone.jarvismobile.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseParserTest {

    private val parser = ResponseParser()

    @Test
    fun `parses a well formed response`() {
        val raw = """
            {
              "assistant_text": "Va bene, Simone.",
              "tool_calls": [],
              "memory_proposal": null,
              "follow_up_expected": true
            }
        """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Valid)
        val r = (result as ParseResult.Valid).response
        assertEquals("Va bene, Simone.", r.assistantText)
        assertTrue(r.followUpExpected)
        assertTrue(r.toolCalls.isEmpty())
    }

    @Test
    fun `parses tool calls with arguments`() {
        val raw = """
            {
              "assistant_text": "Imposto un timer.",
              "tool_calls": [
                {"id": "a1", "name": "set_local_timer",
                 "arguments": {"minutes": 5}, "requires_confirmation": false}
              ]
            }
        """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Valid)
        val call = (result as ParseResult.Valid).response.toolCalls.single()
        assertEquals("set_local_timer", call.name)
        assertEquals("5", call.arguments["minutes"].toString())
    }

    @Test
    fun `repairs response wrapped in a markdown code fence`() {
        val raw = """
            Certo! Ecco la risposta:
            ```json
            {"assistant_text": "Fatto.", "follow_up_expected": false}
            ```
            Spero sia utile.
        """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Repaired, "expected Repaired but got $result")
        assertEquals("Fatto.", (result as ParseResult.Repaired).response.assistantText)
    }

    @Test
    fun `braces inside strings do not break extraction`() {
        val raw = """prefisso {"assistant_text": "usa la sintassi {chiave}", "follow_up_expected": false} suffisso"""
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Repaired || result is ParseResult.Valid)
        val text = when (result) {
            is ParseResult.Valid -> result.response.assistantText
            is ParseResult.Repaired -> result.response.assistantText
            else -> ""
        }
        assertEquals("usa la sintassi {chiave}", text)
    }

    @Test
    fun `falls back to plain text on unrepairable output`() {
        val raw = "Non sono sicuro di aver capito la domanda."
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.PlainText)
        assertEquals(raw, (result as ParseResult.PlainText).rawText)
    }

    @Test
    fun `empty output yields empty plain text and never tools`() {
        val result = parser.parse("   ")
        assertTrue(result is ParseResult.PlainText)
        assertEquals("", (result as ParseResult.PlainText).rawText)
    }

    // --- § FASE 2A.5-bis AUDIT PARSE ERROR — looksLikeAttemptedJson ------------------
    // A caller needs to tell apart "the model correctly answered in plain
    // text because no tool was needed" (the common, non-error case) from
    // "the model tried to produce JSON and it came out malformed" (a real
    // protocol-following failure). These pin the general heuristic — never a
    // specific test phrase.

    @Test
    fun `ordinary conversational text is not flagged as an attempted JSON`() {
        val result = parser.parse("Ciao Simone, come stai oggi?")
        assertTrue(result is ParseResult.PlainText)
        assertFalse((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `a brace-shaped fragment that still fails to decode is flagged as an attempted JSON`() {
        // Missing closing quote makes this genuinely unparsable, unlike the
        // repairable cases above (fences/prose around valid JSON).
        val raw = """{"assistant_text: "rotto", "tool_calls": []}"""
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `a truncated tool-call attempt with no closing brace is still flagged as an attempted JSON`() {
        // § FASE 2A.6 §5 — the exact case the old "has both { and }" heuristic
        // missed: a generation cut off mid-JSON (e.g. hit an output budget)
        // never even reaches a closing brace, but it very obviously WAS an
        // attempt at the tool-call protocol, not ordinary prose.
        val result = parser.parse("""{"tool_calls":[""")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `a bare unmatched opening brace in prose is not flagged as an attempted JSON`() {
        val result = parser.parse("Il valore x { non è definito qui, chiedimi altro.")
        assertTrue(result is ParseResult.PlainText)
        assertFalse((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `unknown keys are ignored rather than failing`() {
        val raw = """{"assistant_text": "ok", "future_field": 42}"""
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Valid)
        assertEquals("ok", (result as ParseResult.Valid).response.assistantText)
    }

    // --- ConversationalJarvisEngine's closed response shapes ----------------
    // No schema change was needed for §3 of the Conversational AI spec: the
    // four required shapes (Response / ToolCall / Clarification /
    // MultiToolPlan) already fall out of the existing fields. These tests pin
    // the exact combination each shape reads as, so a future field change
    // cannot silently blur the distinction.

    @Test
    fun `a plain response has no tool calls and does not expect a follow-up`() {
        val raw = """{"assistant_text": "Fatto.", "tool_calls": [], "follow_up_expected": false}"""
        val result = parser.parse(raw)
        val response = (result as ParseResult.Valid).response
        assertTrue(response.toolCalls.isEmpty())
        assertTrue(!response.followUpExpected)
    }

    @Test
    fun `a clarification has no tool calls but does expect a follow-up`() {
        val raw = """{"assistant_text": "Per quando lo sposto?", "tool_calls": [], "follow_up_expected": true}"""
        val result = parser.parse(raw)
        val response = (result as ParseResult.Valid).response
        assertTrue(response.toolCalls.isEmpty())
        assertTrue(response.followUpExpected)
    }

    // --- § FASE 2A.7 RELEASE GATE 7 — malformed-JSON matrix -----------------
    // The exact simulated-model-output list from the spec: every truncated
    // or malformed tool-call ATTEMPT must be flagged as an attempted JSON
    // (never confused with ordinary plain-text prose), while genuinely valid/
    // repairable JSON keeps parsing as before. No raw malformed text is ever
    // executed as a tool — `looksLikeAttemptedJson` is purely a diagnostic
    // signal for the caller (§ FASE 2A.6 §6's fail-closed message), never
    // itself a trigger for any tool execution.

    @Test
    fun `a bare opening brace alone is flagged as an attempted JSON`() {
        val result = parser.parse("{")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `an unterminated tool_calls array with no closing brace is flagged`() {
        val result = parser.parse("""{"tool_calls":[""")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `a bare markdown json fence opener with no content is flagged`() {
        val result = parser.parse("```json\n{\"tool_calls\":")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `an unterminated assistant_text field is flagged`() {
        val result = parser.parse("""{"assistant_text":""")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `the bare literal field name tool_calls with no braces at all is flagged`() {
        val result = parser.parse("tool_calls")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `the bare literal field name assistant_text with no braces at all is flagged`() {
        val result = parser.parse("assistant_text")
        assertTrue(result is ParseResult.PlainText)
        assertTrue((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `valid JSON naming an unknown tool still parses - ToolRegistry, not the parser, is the authority`() {
        val raw = """
            {"assistant_text": "", "tool_calls": [
              {"id": "a1", "name": "does_not_exist", "arguments": {}, "requires_confirmation": false}
            ]}
        """.trimIndent()
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Valid)
        assertEquals("does_not_exist", (result as ParseResult.Valid).response.toolCalls.single().name)
    }

    @Test
    fun `valid JSON with missing arguments still parses - an empty arguments object, never a crash`() {
        val raw = """{"assistant_text": "", "tool_calls": [{"id": "a1", "name": "get_weather"}]}"""
        val result = parser.parse(raw)
        assertTrue(result is ParseResult.Valid)
        val call = (result as ParseResult.Valid).response.toolCalls.single()
        assertEquals("get_weather", call.name)
        assertTrue(call.arguments.isEmpty())
    }

    @Test
    fun `ordinary plain text prose is never flagged as an attempted JSON, even when it mentions a brace conceptually`() {
        val result = parser.parse("Non serve nessuno strumento per questa richiesta, rispondo direttamente.")
        assertTrue(result is ParseResult.PlainText)
        assertFalse((result as ParseResult.PlainText).looksLikeAttemptedJson)
    }

    @Test
    fun `a single tool call and a multi tool plan share the same shape, differing only in count`() {
        val single = """
            {"assistant_text": "", "tool_calls": [
              {"id": "a1", "name": "move_agenda", "arguments": {"id": "x"}, "requires_confirmation": false}
            ]}
        """.trimIndent()
        val plan = """
            {"assistant_text": "", "tool_calls": [
              {"id": "a1", "name": "move_agenda", "arguments": {"id": "x"}, "requires_confirmation": false},
              {"id": "a2", "name": "update_agenda_notes", "arguments": {"id": "x"}, "requires_confirmation": false}
            ]}
        """.trimIndent()
        val singleCalls = ((parser.parse(single)) as ParseResult.Valid).response.toolCalls
        val planCalls = ((parser.parse(plan)) as ParseResult.Valid).response.toolCalls
        assertEquals(1, singleCalls.size)
        assertEquals(2, planCalls.size)
    }
}
