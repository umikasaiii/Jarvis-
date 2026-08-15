package com.simone.jarvismobile.automation.rule

import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.ActionSpec
import com.simone.jarvismobile.core.mode.JarvisModes
import com.simone.jarvismobile.mode.JarvisModeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches the assistant's mode from a rule ("quando arrivo a casa → HOME").
 *
 * Only a known mode is accepted; an unknown one is a failure, not a silent
 * no-op, so a stale rule pointing at a renamed mode says so. The real work — and
 * whether Do-Not-Disturb could actually be applied — lives in [JarvisModeManager].
 */
@Singleton
class SetJarvisModeActionHandler @Inject constructor(
    private val modes: JarvisModeManager,
) : ActionHandler {
    override val type = ActionRegistry.SET_JARVIS_MODE

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        val mode = spec.param("mode") ?: return ActionOutcome.Failed("modalità mancante")
        if (!JarvisModes.isKnown(mode)) return ActionOutcome.Failed("modalità sconosciuta: $mode")
        if (dryRun) return ActionOutcome.Skipped("dry-run: passerei a $mode")
        return if (modes.setMode(mode)) ActionOutcome.Done else ActionOutcome.Failed("cambio modalità non riuscito")
    }
}
