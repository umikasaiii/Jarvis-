package com.simone.jarvismobile.core.protocol

import kotlinx.serialization.json.Json

/**
 * Outcome of trying to interpret a raw LLM completion as an [AssistantResponse].
 */
sealed interface ParseResult {
    /** Parsed cleanly on the first attempt. */
    data class Valid(val response: AssistantResponse) : ParseResult

    /** First parse failed but a single controlled repair succeeded. */
    data class Repaired(val response: AssistantResponse) : ParseResult

    /**
     * Could not be parsed as protocol JSON even after one repair attempt.
     * Per docs/MODELS.md the caller MUST treat [rawText] as a plain spoken
     * reply and MUST NOT execute any tools.
     */
    data class PlainText(val rawText: String) : ParseResult
}

/**
 * Parses model output into the strict tool protocol.
 *
 * Contract (docs/MODELS.md §12):
 *  1. If the model produces invalid JSON we do NOT execute tools.
 *  2. We attempt exactly one controlled repair (strip code fences / prose,
 *     extract the outermost JSON object).
 *  3. If that fails, the content is treated as a plain textual answer.
 *  4. Errors are technical only — no personal data is ever surfaced here.
 */
class ResponseParser(
    private val json: Json = DEFAULT_JSON,
) {
    fun parse(raw: String): ParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParseResult.PlainText("")

        decodeOrNull(trimmed)?.let { return ParseResult.Valid(it) }

        val repaired = extractJsonObject(trimmed)
        if (repaired != null && repaired != trimmed) {
            decodeOrNull(repaired)?.let { return ParseResult.Repaired(it) }
        }
        return ParseResult.PlainText(trimmed)
    }

    private fun decodeOrNull(candidate: String): AssistantResponse? =
        runCatching { json.decodeFromString<AssistantResponse>(candidate) }.getOrNull()

    /**
     * Best-effort extraction of the outermost balanced JSON object. Handles the
     * two dominant failure modes: ```json fences and leading/trailing prose.
     * String-literal aware so braces inside quoted text don't confuse it.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
