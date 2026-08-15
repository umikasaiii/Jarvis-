package com.simone.jarvismobile.automation.rule

import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.ActionSpec
import com.simone.jarvismobile.driving.DrivingModeManager
import com.simone.jarvismobile.driving.DrivingStartResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts Modalità Guida from a rule (e.g. a future "quando mi connetto al
 * Bluetooth dell'auto" trigger), through the exact same [DrivingModeManager]
 * entry point the Home-screen button and the voice command use — never a
 * second overlay-launch path.
 */
@Singleton
class OpenDrivingHudActionHandler @Inject constructor(
    private val drivingMode: DrivingModeManager,
) : ActionHandler {
    override val type = ActionRegistry.OPEN_DRIVING_HUD

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        if (dryRun) return ActionOutcome.Skipped("dry-run: avvierei la Modalità Guida")
        return when (drivingMode.start()) {
            DrivingStartResult.Started -> ActionOutcome.Done
            DrivingStartResult.MissingOverlayPermission -> ActionOutcome.Failed("permesso di sovrapposizione mancante")
        }
    }
}
