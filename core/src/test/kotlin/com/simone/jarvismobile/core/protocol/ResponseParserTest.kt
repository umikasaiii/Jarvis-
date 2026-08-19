package com.simone.jarvismobile.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
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
