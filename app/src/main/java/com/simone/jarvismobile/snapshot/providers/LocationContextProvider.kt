package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.snapshot.LocationContext
import com.simone.jarvismobile.core.snapshot.MovementState
import com.simone.jarvismobile.core.snapshot.PlaceLabel
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads [ContextEngine]'s already-fused place/activity state and resolves it
 * to a semantic label via [PlaceRepository] — no new location tracking, no
 * raw coordinates ever leave this provider (§ richiesta esplicita: "NON
 * introdurre tracking aggiuntivo... non inviare coordinate GPS quando non
 * necessarie"). "casa"/"lavoro" are conventions the user picks when naming
 * a saved place, not a fixed system enum — matched by substring on the
 * saved display name, same honesty as the rest of this project's place
 * handling (§ `PlaceProximityReceiver` already does the same for "casa").
 */
fun interface LocationContextProvider {
    suspend fun provide(): LocationContext?
}

@Singleton
class DefaultLocationContextProvider @Inject constructor(
    private val contextEngine: ContextEngine,
    private val places: PlaceRepository,
) : LocationContextProvider {

    override suspend fun provide(): LocationContext {
        val state = contextEngine.state.value
        val placeId = state.placeId
        val displayName = placeId?.let { id -> runCatching { places.byId(id) }.getOrNull()?.displayName }

        val label = when {
            placeId == null -> if (state.driving) PlaceLabel.TRAVELLING else PlaceLabel.UNKNOWN
            displayName != null && (displayName.contains("casa", ignoreCase = true) || displayName.contains("home", ignoreCase = true)) -> PlaceLabel.HOME
            displayName != null && (displayName.contains("lavoro", ignoreCase = true) || displayName.contains("work", ignoreCase = true) || displayName.contains("ufficio", ignoreCase = true)) -> PlaceLabel.WORK
            else -> PlaceLabel.KNOWN_PLACE
        }

        val movement = when {
            state.driving -> MovementState.MOVING
            state.activity == null -> MovementState.UNKNOWN
            state.activity.equals("still", ignoreCase = true) -> MovementState.STATIONARY
            else -> MovementState.MOVING
        }

        return LocationContext(
            currentPlaceLabel = label,
            currentPlaceName = displayName,
            lastRelevantPlace = displayName,
            movementState = movement,
            capturedAt = Instant.now(),
        )
    }
}
