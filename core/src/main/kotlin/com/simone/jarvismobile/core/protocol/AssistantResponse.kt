package com.simone.jarvismobile.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Structured protocol the local LLM is asked to emit.
 *
 * See docs/MODELS.md §"Protocollo del modello". The model is instructed to
 * return exactly this JSON object. Anything the model says that is meant to be
 * spoken lives in [assistantText]; tool invocations are explicit and validated
 * before execution — the model never runs anything itself.
 */
@Serializable
data class AssistantResponse(
    @SerialName("assistant_text")
    val assistantText: String = "",
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall> = emptyList(),
    @SerialName("memory_proposal")
    val memoryProposal: MemoryProposal? = null,
    @SerialName("follow_up_expected")
    val followUpExpected: Boolean = false,
)

/**
 * A single tool invocation requested by the model. [requiresConfirmation] is a
 * *hint* from the model; the authoritative confirmation requirement comes from
 * the tool's own policy in the registry and always wins over the model.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    @SerialName("requires_confirmation")
    val requiresConfirmation: Boolean = true,
)

/**
 * A proposal to persist something into the Obsidian vault. Never written
 * without explicit user confirmation (see docs/PRIVACY.md).
 */
@Serializable
data class MemoryProposal(
    val classification: MemoryClassification,
    /** Target note (relative vault path). Null means "let the user choose". */
    @SerialName("target_note")
    val targetNote: String? = null,
    val content: String,
    val reason: String = "",
)

@Serializable
enum class MemoryClassification {
    @SerialName("temporary")
    TEMPORARY,

    @SerialName("permanent")
    PERMANENT,

    @SerialName("sensitive")
    SENSITIVE,
}

/** Convenience: the element type used for raw tool arguments in the codebase. */
typealias ToolArguments = JsonElement
