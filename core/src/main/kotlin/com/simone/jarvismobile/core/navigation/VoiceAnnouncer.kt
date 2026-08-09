package com.simone.jarvismobile.core.navigation

import kotlin.math.roundToInt

/**
 * Turns route maneuvers into spoken Italian instructions and decides *when* to
 * announce them. The wording is derived strictly from the computed maneuver —
 * type, distance, roundabout exit, road name — so the AI voice only reads what
 * the route says and can never alter direction, distance or destination (§11, §19).
 *
 * Announcements are anticipated by distance that grows with speed (more warning
 * on a motorway than in town) and de-duplicated so the same turn isn't repeated.
 */
class VoiceAnnouncer {

    private var lastPhase: Phase? = null
    private var lastManeuverIndex = -1

    private enum class Phase { FAR, NEAR, IMMEDIATE, DONE }

    /**
     * Returns the phrase to speak now, or null if nothing new should be said.
     * [maneuverIndex] identifies the upcoming maneuver so repeats are suppressed.
     */
    fun onProgress(
        maneuverIndex: Int,
        maneuver: Maneuver,
        distanceToManeuverMeters: Double,
        speedMps: Double,
    ): String? {
        if (maneuverIndex != lastManeuverIndex) {
            lastManeuverIndex = maneuverIndex
            lastPhase = null
        }
        val phase = phaseFor(distanceToManeuverMeters, speedMps)
        if (phase == lastPhase || phase == Phase.FAR) {
            lastPhase = phase
            return null
        }
        lastPhase = phase
        return when (phase) {
            Phase.NEAR -> "Tra ${roundedDistance(distanceToManeuverMeters)}, ${action(maneuver)}."
            Phase.IMMEDIATE -> capitalize("${action(maneuver)}.")
            Phase.DONE -> null
            Phase.FAR -> null
        }
    }

    fun arrival(): String = "Hai raggiunto la destinazione."

    fun recalculating(): String = "Ricalcolo percorso."

    fun reset() { lastPhase = null; lastManeuverIndex = -1 }

    // Anticipation windows scale with speed: ~20 s of travel for the "near" cue,
    // ~4 s for the "now" cue, clamped so town and motorway both read naturally.
    private fun phaseFor(distance: Double, speedMps: Double): Phase {
        val near = (speedMps.coerceAtLeast(3.0) * 20.0).coerceIn(150.0, 600.0)
        val immediate = (speedMps.coerceAtLeast(3.0) * 4.0).coerceIn(30.0, 80.0)
        return when {
            distance <= immediate -> Phase.IMMEDIATE
            distance <= near -> Phase.NEAR
            else -> Phase.FAR
        }
    }

    private fun action(m: Maneuver): String = when (m.type) {
        ManeuverType.TURN_LEFT -> "gira a sinistra" + road(m)
        ManeuverType.TURN_RIGHT -> "gira a destra" + road(m)
        ManeuverType.SLIGHT_LEFT -> "mantieni leggermente la sinistra" + road(m)
        ManeuverType.SLIGHT_RIGHT -> "mantieni leggermente la destra" + road(m)
        ManeuverType.SHARP_LEFT -> "svolta decisamente a sinistra" + road(m)
        ManeuverType.SHARP_RIGHT -> "svolta decisamente a destra" + road(m)
        ManeuverType.KEEP_LEFT -> "mantieni la sinistra" + road(m)
        ManeuverType.KEEP_RIGHT -> "mantieni la destra" + road(m)
        ManeuverType.UTURN -> "fai inversione a U"
        ManeuverType.ROUNDABOUT -> "alla rotonda prendi la ${ordinal(m.roundaboutExit ?: 1)} uscita" + road(m)
        ManeuverType.CONTINUE -> "prosegui dritto" + road(m)
        ManeuverType.DEPART -> "parti" + road(m)
        ManeuverType.ARRIVE -> "sei arrivato a destinazione"
    }

    private fun road(m: Maneuver): String = if (m.roadName.isBlank()) "" else " su ${m.roadName}"

    private fun roundedDistance(meters: Double): String {
        val m = meters.roundToInt()
        return when {
            m >= 1000 -> {
                val km = (m / 100) / 10.0
                if (km == km.roundToInt().toDouble()) "${km.roundToInt()} chilometri" else "$km chilometri"
            }
            m >= 100 -> "${(m / 50) * 50} metri"
            else -> "${(m / 10).coerceAtLeast(1) * 10} metri"
        }
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "prima"; 2 -> "seconda"; 3 -> "terza"; 4 -> "quarta"; 5 -> "quinta"
        else -> "${n}ª"
    }

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s.replaceFirstChar { it.uppercase() }
}
