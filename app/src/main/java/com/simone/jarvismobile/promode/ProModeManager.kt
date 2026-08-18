package com.simone.jarvismobile.promode

import com.simone.jarvismobile.core.promode.ProModeCommand
import com.simone.jarvismobile.core.promode.ProModeCommands
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single NORMAL/PRO switch (spec §1). Deliberately thin: this class
 * only tracks *state* and recognises the on/off voice phrases; the actual
 * PRO-mode conversation loop lives in [ProModeCoordinator]. Keeping the two
 * apart means [com.simone.jarvismobile.audio.SessionCoordinator] needs exactly
 * one `if` to integrate Pro Mode (see its `generateAnswer`), instead of
 * `if (proMode)` scattered through the codebase.
 *
 * Backed by [SettingsRepository] so the state survives a process death —
 * without that, a background app kill mid-PRO-session would silently drop
 * back to NORMAL and start alias-matching the user's next sentence again.
 */
@Singleton
class ProModeManager @Inject constructor(
    private val settings: SettingsRepository,
    private val llmRouter: LlmRouter,
) {
    /** Cold flow for UI badges — a ViewModel turns this into a StateFlow with `stateIn`, same as every other settings-backed flow in this app. */
    val isActive: Flow<Boolean> = settings.proModeActive

    /** Authoritative check for routing decisions; always reads the persisted value. */
    suspend fun currentlyActive(): Boolean = settings.proModeActive.first()

    /**
     * Applies [active]. A real transition resets the local model's running
     * conversation: NORMAL and PRO use different system prompts (PRO's
     * includes the tool-call JSON protocol), and [com.simone.jarvismobile.llm.LlmEngine.chat]
     * only honours a new system prompt on the first call after a reset — see
     * its own doc comment. A no-op call (already in that state) changes
     * nothing, so toggling twice by accident never wipes context for free.
     */
    suspend fun setActive(active: Boolean) {
        if (currentlyActive() == active) return
        settings.setProModeActive(active)
        llmRouter.resetConversation()
    }

    /**
     * Recognises "attiva/disattiva modalità pro" et al. and applies them,
     * returning what JARVIS should say. Called unconditionally, before any
     * NORMAL/PRO routing decision, so the activation phrase works from NORMAL
     * and the exit phrase always works from PRO (spec: "esci dalla modalità
     * pro funziona sempre") — including while a Pro Mode tool confirmation is
     * pending, since the caller checks this before consulting the coordinator.
     */
    suspend fun interceptSystemCommand(text: String): String? {
        val command = ProModeCommands.parse(text) ?: return null
        return applyVoiceCommand(command)
    }

    suspend fun applyVoiceCommand(command: ProModeCommand): String {
        val active = currentlyActive()
        return when (command) {
            ProModeCommand.ACTIVATE -> if (active) {
                "La modalità Pro è già attiva."
            } else {
                setActive(true)
                "Modalità Pro attivata. Da qui rispondo solo con il modello locale, senza automatismi."
            }
            ProModeCommand.DEACTIVATE -> if (!active) {
                "La modalità Pro non è attiva."
            } else {
                setActive(false)
                "Modalità Pro disattivata. Torno al funzionamento normale."
            }
        }
    }
}
