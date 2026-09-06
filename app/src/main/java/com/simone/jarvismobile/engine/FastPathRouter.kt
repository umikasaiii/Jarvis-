package com.simone.jarvismobile.engine

import com.simone.jarvismobile.core.tools.HARD_COMMAND_TOOL_NAMES
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.Match
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-confidence-command accelerator for the conversational engine (spec
 * §5), reusing the exact same deterministic matcher Classic mode already
 * uses — no second pattern set to keep in sync. Only [Match.Run] (an
 * immediately executable tool call) counts as a fast-path hit; [Match.Ask]
 * (Classic's own slot-filling follow-up) is deliberately NOT treated as one
 * here — the conversational engine's own multi-turn handling
 * (`ConversationManager`/`ContextAssembler`) is the intended path for an
 * under-specified request, so a partial Classic match still falls through to
 * `JarvisBrain` rather than reusing Classic's separate `pendingSlot` state
 * machine.
 *
 * § FASE 2A.10 SEMANTIC ROUTER AUTHORITATIVE — a [Match.Run] is only treated
 * as a fast-path hit when its tool name is in [HARD_COMMAND_TOOL_NAMES]
 * (torch, media transport): `CommandMatcher.match()` also recognizes plenty
 * of other natural language (agenda queries, reminders, alarms, memory, …)
 * that this engine must NOT shortcut past the Semantic Interpreter just
 * because a regex happens to match it — root cause fixed: "Che impegni ho
 * domani?" used to answer "praticamente istantaneamente" from
 * `CommandMatcher.AGENDA_RE` alone, never from real understanding. Modalità
 * Classica is untouched — it still calls `CommandMatcher.match()` directly
 * with its own full command set; only this conversational fast path narrows.
 *
 * A miss is not a rejection: [tryFastPath] returning null means only "no
 * HARD-command pattern matched", never "this request is invalid" — every
 * other natural-language request (including a genuine agenda/weather/health
 * one) always reaches the Semantic Interpreter next, exactly because it was
 * never a hard-command [Match] to begin with.
 */
@Singleton
class FastPathRouter @Inject constructor(
    private val settings: SettingsRepository,
) {
    suspend fun tryFastPath(transcript: String, recentContext: String? = null): Match.Run? {
        if (!settings.jarvisFastPathEnabled.first()) return null
        val match = CommandMatcher.match(transcript, recentContext = recentContext) as? Match.Run ?: return null
        return match.takeIf { it.call.name in HARD_COMMAND_TOOL_NAMES }
    }
}
