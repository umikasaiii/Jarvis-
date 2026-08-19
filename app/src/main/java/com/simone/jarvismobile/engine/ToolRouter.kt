package com.simone.jarvismobile.engine

import com.simone.jarvismobile.core.engine.ToolCallBudget
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.tools.ToolRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The uniform layer between `JarvisBrain` and the existing tool-execution
 * seam (spec §4). Adds exactly what the conversational loop needs and
 * nothing `ToolRunner`/`ToolRegistry` already does — name/argument/network
 * validation and the confirmation gate stay entirely in
 * [ToolRunner.run]/`ToolRegistry.resolve`, unchanged and unduplicated:
 *
 *  - enforces the per-turn [ToolCallBudget] (spec §11's loop cap) before ever
 *    reaching [ToolRunner];
 *  - never itself contains LLM/prompt code, and `JarvisBrain` never itself
 *    calls [ToolRunner] — this is the one seam between reasoning and
 *    execution, matching spec §4's separation requirement structurally.
 */
@Singleton
class ToolRouter @Inject constructor(
    private val tools: ToolRunner,
) {
    suspend fun execute(
        call: ToolCall,
        budget: ToolCallBudget,
        online: Boolean = false,
        confirmed: Boolean = false,
    ): ToolOutcome {
        if (!budget.tryConsume()) {
            return ToolOutcome.Failed(
                code = "tool_loop_cap",
                spoken = "Ho eseguito il numero massimo di operazioni per questo turno.",
            )
        }
        return tools.run(call, online, confirmed)
    }
}
