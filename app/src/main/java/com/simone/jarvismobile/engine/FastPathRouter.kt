package com.simone.jarvismobile.engine

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
 * A miss is not a rejection: [tryFastPath] returning null means only "no
 * deterministic pattern matched", never "this request is invalid" — natural
 * semantic requests like "Qui è troppo buio" always reach the model, exactly
 * because they were never a [Match] to begin with.
 */
@Singleton
class FastPathRouter @Inject constructor(
    private val settings: SettingsRepository,
) {
    suspend fun tryFastPath(transcript: String, recentContext: String? = null): Match.Run? {
        if (!settings.jarvisFastPathEnabled.first()) return null
        return CommandMatcher.match(transcript, recentContext = recentContext) as? Match.Run
    }
}
