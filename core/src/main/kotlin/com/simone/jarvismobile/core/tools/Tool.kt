package com.simone.jarvismobile.core.tools

import kotlinx.serialization.json.JsonObject

/** Structured result of executing a [Tool]. Never throws across the boundary. */
sealed interface ToolResult {
    data class Success(val output: JsonObject) : ToolResult
    /** [code] is a technical error code; [message] must not contain personal data. */
    data class Failure(val code: String, val message: String = "") : ToolResult
}

/**
 * A single capability the assistant can invoke. Tools are the ONLY way the
 * model can affect the world — it can never call arbitrary code, build Intents,
 * run shells, read files outside the vault, or hit arbitrary URLs
 * (docs/SECURITY.md §15).
 */
interface Tool {
    /** Stable machine name the model refers to (e.g. `get_current_time`). */
    val name: String

    /** Human description surfaced to the user and used in the model prompt. */
    val description: String

    val policy: ToolPolicy
    val sensitivity: SensitivityLevel

    /** Whether the tool needs network. Used by the router when offline. */
    val requiresNetwork: Boolean

    /** Execution timeout in milliseconds. */
    val timeoutMs: Long

    /**
     * Validates [arguments] against the tool's schema. Returns null when valid,
     * or a technical reason string when invalid. Must not throw.
     */
    fun validate(arguments: JsonObject): String?

    /** Executes the tool. Implementations must honor [timeoutMs] via the caller. */
    suspend fun execute(arguments: JsonObject): ToolResult
}

/**
 * Effective confirmation requirement for a tool, combining the tool's own
 * policy with the model's (advisory) hint. The stricter of the two wins — the
 * model can never *downgrade* a confirmation requirement.
 */
fun Tool.confirmationRequired(modelHint: Boolean): Boolean =
    policy.requiresConfirmation || modelHint
