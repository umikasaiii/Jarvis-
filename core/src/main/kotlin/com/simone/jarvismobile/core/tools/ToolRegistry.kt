package com.simone.jarvismobile.core.tools

import com.simone.jarvismobile.core.protocol.ToolCall

/** Why a proposed [ToolCall] was rejected before execution. */
sealed interface ToolRejection {
    data class Unknown(val name: String) : ToolRejection
    data class InvalidArguments(val name: String, val reason: String) : ToolRejection
    data class NetworkRequiredButOffline(val name: String) : ToolRejection
}

/** Outcome of resolving a [ToolCall] against the registry, prior to execution. */
sealed interface ToolResolution {
    /** The call is valid and may run; [needsConfirmation] gates execution. */
    data class Approved(
        val tool: Tool,
        val call: ToolCall,
        val needsConfirmation: Boolean,
        val needsBiometric: Boolean,
    ) : ToolResolution

    data class Rejected(val reason: ToolRejection) : ToolResolution
}

/**
 * Typed registry of the tools the assistant may use. The model can only ever
 * reach tools registered here; anything else is rejected as [ToolRejection.Unknown].
 */
class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    val tools: Collection<Tool> get() = byName.values

    fun get(name: String): Tool? = byName[name]

    /**
     * Resolves a model-proposed [call] into an execution decision without
     * running anything. Validates existence, arguments, network availability
     * and computes the authoritative confirmation/biometric requirement.
     */
    fun resolve(call: ToolCall, online: Boolean): ToolResolution {
        val tool = byName[call.name]
            ?: return ToolResolution.Rejected(ToolRejection.Unknown(call.name))

        tool.validate(call.arguments)?.let { reason ->
            return ToolResolution.Rejected(ToolRejection.InvalidArguments(call.name, reason))
        }

        if (tool.requiresNetwork && !online) {
            return ToolResolution.Rejected(ToolRejection.NetworkRequiredButOffline(call.name))
        }

        return ToolResolution.Approved(
            tool = tool,
            call = call,
            needsConfirmation = tool.confirmationRequired(call.requiresConfirmation),
            needsBiometric = tool.policy.requiresBiometric,
        )
    }
}
