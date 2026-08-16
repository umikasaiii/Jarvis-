package com.simone.jarvismobile.ui.navigation

import com.simone.jarvismobile.core.navigation.ManeuverType
import java.util.Locale

/**
 * Shared rendering helpers for anything that shows a maneuver/distance/ETA —
 * today the offline Navigation screen, and JARVIS Drive's ManeuverCard/EtaBar
 * (spec: don't duplicate the same glyph/formatting logic in two places).
 */
fun maneuverGlyph(type: ManeuverType?): String = when (type) {
    ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.KEEP_LEFT -> "↰"
    ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_RIGHT -> "↱"
    ManeuverType.UTURN -> "⤺"
    ManeuverType.ROUNDABOUT -> "⟳"
    ManeuverType.ARRIVE -> "◎"
    else -> "↑"
}

fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.ITALY, "%.1f km", meters / 1000.0)
    else -> "${(meters / 10).toInt() * 10} m"
}

fun formatDuration(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m >= 60 -> "${m / 60} h ${m % 60} min"
        m >= 1 -> "$m min"
        else -> "<1 min"
    }
}
