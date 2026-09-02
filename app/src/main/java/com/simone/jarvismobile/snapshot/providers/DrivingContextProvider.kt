package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.core.navigation.NavState
import com.simone.jarvismobile.core.snapshot.DrivingContext
import com.simone.jarvismobile.driving.DrivingModeManager
import com.simone.jarvismobile.navigation.NavigationRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Combines the two existing driving sources rather than rebuilding either:
 * [DrivingModeManager] (overlay path — real "is driving"/destination label,
 * ETA/distance deliberately null by design, "never fakes an ETA") and
 * [NavigationRepository] (internal-engine path — real eta/remaining
 * distance when that mode is live). No routing/navigation logic lives here,
 * only reads (§ richiesta esplicita: "Non ricostruire navigazione o
 * routing. Leggere solamente lo stato già prodotto").
 */
fun interface DrivingContextProvider {
    suspend fun provide(): DrivingContext?
}

@Singleton
class DefaultDrivingContextProvider @Inject constructor(
    private val drivingModeManager: DrivingModeManager,
    private val navigationRepository: NavigationRepository,
) : DrivingContextProvider {

    override suspend fun provide(): DrivingContext {
        val drivingState = drivingModeManager.state.value
        val navState = navigationRepository.navState.value
        val progress = navigationRepository.progress.value
        val activeNavStates = setOf(NavState.NAVIGATING, NavState.REROUTING, NavState.ARRIVING)

        val isDriving = drivingState.active || navState in activeNavStates
        val navigationActive = navigationRepository.route.value != null || drivingState.navigation?.routeStarted == true
        val relevantState = if (navState != NavState.IDLE) navState.name else drivingState.voiceState.name

        return DrivingContext(
            isDriving = isDriving,
            destination = drivingState.navigation?.destinationLabel,
            etaMinutes = progress?.etaSeconds?.let { (it / 60).roundToInt() },
            remainingDistanceMeters = progress?.remainingDistanceMeters?.roundToInt(),
            navigationActive = navigationActive,
            relevantDrivingState = relevantState,
            capturedAt = Instant.now(),
        )
    }
}
