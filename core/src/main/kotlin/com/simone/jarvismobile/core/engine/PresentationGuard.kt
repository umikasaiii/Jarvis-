package com.simone.jarvismobile.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 2 (Structured Grounding +
 * Internal/User Presentation Boundary), §3 "internal vs user-facing channel".
 *
 * [GroundingGate] already blocks the case where the model's raw completion
 * never parsed as protocol JSON at all ([ParseOutcome.MALFORMED_JSON] — the
 * "Accendi la luce della camera" bug, where a truncated `{"tool_calls":[`
 * fragment used to reach the user verbatim). A narrower, previously
 * unaddressed leak survives even a SUCCESSFUL parse: `assistant_text` is just
 * a string field the model fills in — nothing stops a small/confused model
 * from putting an entire nested protocol-shaped JSON object literally INSIDE
 * that string (e.g. `assistant_text: "{\"tool_calls\": [...], ...}"`), which
 * decodes fine at the outer envelope level and would otherwise be returned to
 * the user as-is.
 *
 * [isInternalSchemaLeak] catches exactly this, STRUCTURALLY: it parses
 * [text] itself as JSON (reusing the same parser this app's own protocol
 * already uses, never a second one) and checks whether the result is an
 * object carrying one of THIS APP'S OWN protocol field names as a key. This
 * is deliberately NOT the blacklist/sanitization approach the spec forbids
 * ("do NOT fix leakage by removing 'metric'/'range'/'aggregation'/'{'/JSON
 * etc.") — it never inspects arbitrary substrings or words in ordinary
 * prose, and a sentence that happens to contain a brace or the word "JSON"
 * without actually BEING a parseable object carrying our schema's keys is
 * never flagged. Only a string that is *itself* structurally our protocol,
 * nested one level deeper than it belongs, is a leak.
 */
object PresentationGuard {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The exact protocol field names ([com.simone.jarvismobile.core.protocol.AssistantResponse]'s own keys) whose presence as a JSON object's key structurally identifies internal/schema material, never a blacklist of prose words. */
    private val PROTOCOL_KEYS = setOf("tool_calls", "assistant_text")

    fun isInternalSchemaLeak(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return false
        return PROTOCOL_KEYS.any { it in element }
    }
}
