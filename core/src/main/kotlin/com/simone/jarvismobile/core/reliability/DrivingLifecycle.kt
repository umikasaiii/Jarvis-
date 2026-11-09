package com.simone.jarvismobile.core.reliability

enum class DrivingPhase { IDLE, STARTING, ACTIVE, STOPPING }

/**
 * BETA 7 — the bookkeeping behind Modalità Guida start/stop, kept pure so its edge cases are testable.
 *
 * Bugs it closes:
 *  - two start requests before the service was up (voice command + button) overwrote the remembered previous mode with
 *    DRIVING itself, so closing the mode "restored" DRIVING for good;
 *  - a stop request while nothing ran started the whole service just to stop it;
 *  - if the service died without a graceful stop, or failed to start, the mode was left on DRIVING.
 */
class DrivingLifecycle(private val drivingMode: String) {
    var phase: DrivingPhase = DrivingPhase.IDLE
        private set
    private var previousMode: String? = null

    /** @return true when the caller should really start the service; false when a start is already underway/active. */
    fun requestStart(currentMode: String): Boolean {
        if (phase != DrivingPhase.IDLE) return false
        phase = DrivingPhase.STARTING
        previousMode = currentMode.takeIf { it != drivingMode }
        return true
    }

    fun serviceUp() {
        if (phase == DrivingPhase.STARTING) phase = DrivingPhase.ACTIVE
    }

    /** @return true when a stop action should really be sent to the service. */
    fun requestStop(): Boolean {
        if (phase == DrivingPhase.IDLE || phase == DrivingPhase.STOPPING) return false
        phase = DrivingPhase.STOPPING
        return true
    }

    /** The service is gone, gracefully or not. Returns the mode to restore (once), or null. */
    fun serviceDown(): String? {
        phase = DrivingPhase.IDLE
        val restore = previousMode
        previousMode = null
        return restore
    }

    /** The service could not even start (e.g. foreground start refused). Same as [serviceDown]. */
    fun startFailed(): String? = serviceDown()
}
