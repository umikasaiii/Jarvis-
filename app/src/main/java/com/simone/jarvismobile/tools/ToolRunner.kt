package com.simone.jarvismobile.tools

import android.util.Log
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.ToolRejection
import com.simone.jarvismobile.core.tools.ToolResolution
import com.simone.jarvismobile.core.tools.ToolResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** What the assistant should say/do after attempting a tool. */
sealed interface ToolOutcome {
    /** Executed successfully; [spoken] is ready to be said aloud. */
    data class Done(val spoken: String) : ToolOutcome

    /** The tool needs explicit user confirmation before it may run. */
    data class NeedsConfirmation(val call: ToolCall, val prompt: String) : ToolOutcome

    /** Could not run; [spoken] explains it plainly, [code] is technical. */
    data class Failed(val code: String, val spoken: String) : ToolOutcome
}

/**
 * Executes tool calls against the [ToolRegistry], honouring the registry's
 * authoritative confirmation policy and each tool's timeout. The model can only
 * ever reach tools registered here — never arbitrary code (docs/SECURITY.md §15).
 */
@Singleton
class ToolRunner @Inject constructor(
    private val registry: ToolRegistry,
) {
    /** Names of the available tools, for the Commands screen. */
    fun available() = registry.tools.map { it.name to it.description }

    /**
     * Resolves and (unless confirmation is required) runs [call].
     * Pass [confirmed] = true to execute a call the user has just approved.
     */
    suspend fun run(call: ToolCall, online: Boolean = false, confirmed: Boolean = false): ToolOutcome {
        return when (val resolution = registry.resolve(call, online)) {
            is ToolResolution.Rejected -> ToolOutcome.Failed(
                code = rejectionCode(resolution.reason),
                spoken = rejectionMessage(resolution.reason),
            )

            is ToolResolution.Approved -> {
                if (resolution.needsConfirmation && !confirmed) {
                    return ToolOutcome.NeedsConfirmation(
                        call = call,
                        prompt = "Confermi: ${resolution.tool.description}?",
                    )
                }
                val tool = resolution.tool
                try {
                    when (val result = withTimeout(tool.timeoutMs) { tool.execute(call.arguments) }) {
                        is ToolResult.Success -> {
                            val spoken = result.output["spoken"]?.jsonPrimitive?.content
                                ?: result.output["result"]?.jsonPrimitive?.content?.let { "Risultato: $it" }
                                ?: "Fatto."
                            Log.i(TAG, "tool_ok ${tool.name}")
                            ToolOutcome.Done(spoken)
                        }
                        is ToolResult.Failure -> {
                            Log.w(TAG, "tool_fail ${tool.name} ${result.code}")
                            ToolOutcome.Failed(result.code, failureMessage(tool.name, result.code))
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    ToolOutcome.Failed("timeout", "L'operazione ha impiegato troppo tempo.")
                } catch (e: Exception) {
                    Log.w(TAG, "tool_crash ${tool.name} ${e.javaClass.simpleName}")
                    ToolOutcome.Failed("crash", "Non sono riuscito a completare l'operazione.")
                }
            }
        }
    }

    private fun rejectionCode(r: ToolRejection): String = when (r) {
        is ToolRejection.Unknown -> "unknown_tool"
        is ToolRejection.InvalidArguments -> "invalid_arguments"
        is ToolRejection.NetworkRequiredButOffline -> "offline"
    }

    private fun rejectionMessage(r: ToolRejection): String = when (r) {
        is ToolRejection.Unknown -> "Non ho uno strumento per farlo."
        is ToolRejection.InvalidArguments -> "Non ho capito i dettagli: ${r.reason}."
        is ToolRejection.NetworkRequiredButOffline -> "Serve la rete e siamo offline."
    }

    private fun failureMessage(tool: String, code: String): String = when (code) {
        "no_vault" -> "Non ho un vault collegato dove salvare. Aprilo in Impostazioni › Memoria."
        "no_clock_app" -> "Non trovo l'app Orologio per farlo."
        "no_torch" -> "Questo telefono non espone la torcia."
        else -> "Non sono riuscito a completare: $tool."
    }

    private companion object {
        const val TAG = "JarvisTools"
    }
}
