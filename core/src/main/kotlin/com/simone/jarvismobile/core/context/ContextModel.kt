package com.simone.jarvismobile.core.context

import java.time.LocalDateTime

/**
 * What JARVIS believes is going on right now (§1).
 *
 * Every field that can be unknown *is* nullable, and unknown means unknown — not
 * "false". A missing place is not "not at home"; a missing charging state is not
 * "on battery". The automation conditions rely on that distinction to refuse to
 * fire on a guess, so collapsing it here would quietly undo that protection.
 *
 * Confidences are 0..1 and travel with the value they qualify: knowing the phone
 * thinks it is home is not the same as knowing how sure it is.
 */
data class ContextState(
    val placeId: String? = null,
    val placeConfidence: Float = 0f,
    val activity: String? = null,
    val activityConfidence: Float = 0f,
    val jarvisMode: String? = null,
    val connectedBluetooth: Set<String> = emptySet(),
    val bluetoothKnown: Boolean = false,
    val networkAvailable: Boolean? = null,
    val charging: Boolean? = null,
    val batteryPercent: Int? = null,
    val interactive: Boolean? = null,
    /** True once the first unlock of today has been seen. */
    val firstUnlockDone: Boolean = false,
    val driving: Boolean = false,
    /**
     * Null unless the weather opt-in is on and a refresh has actually
     * succeeded — off, offline, or stale all read the same as "unknown" here,
     * exactly like every other fact in this state.
     */
    val rainToday: Boolean? = null,
    val rainTomorrow: Boolean? = null,
    val weatherUpdatedAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
) {
    /** Privacy-safe summary for diagnostics: ids and confidences, never coordinates. */
    fun describe(): String = buildString {
        append("place=").append(placeId ?: "?")
        append(" (").append("%.2f".format(placeConfidence)).append(")")
        append(" activity=").append(activity ?: "?")
        append(" driving=").append(driving)
        append(" bt=").append(if (bluetoothKnown) connectedBluetooth.size.toString() else "?")
        append(" rain=").append(rainToday?.toString() ?: "?").append("/").append(rainTomorrow?.toString() ?: "?")
    }
}

/** Where an observation came from. Used for fusion weights and for diagnostics. */
enum class SignalSource {
    GEOFENCE,
    WIFI,
    ACTIVITY,
    BLUETOOTH,
    MANUAL,
    ;

    /**
     * How much this source is trusted about *place*, calibrated against
     * `PlaceFusion.Config.enterThreshold` (0.6 by default):
     *
     *  - a **geofence** is the direct measurement and, sustained for the dwell,
     *    is an arrival on its own — requiring corroboration would make the
     *    feature useless to anyone without a saved Wi-Fi at that place;
     *  - a **Wi-Fi** network is strong but not conclusive: it can be a
     *    neighbour's repeater, or reachable from the street, so alone it stays
     *    below the bar and only holds a place already confirmed;
     *  - **Bluetooth** and **activity** never establish a place by themselves;
     *    they corroborate. Wi-Fi plus Bluetooth together do clear the bar.
     */
    val placeWeight: Float
        get() = when (this) {
            MANUAL -> 1.0f
            GEOFENCE -> 0.65f
            WIFI -> 0.5f
            BLUETOOTH -> 0.3f
            ACTIVITY -> 0.15f
        }
}

/** One observation that says something about where the phone is. */
data class PlaceSignal(
    val placeId: String?,
    val source: SignalSource,
    val at: LocalDateTime,
    /** How sure the source itself is, 0..1. */
    val strength: Float = 1f,
)

/** A change worth telling the automation engine about. */
data class ContextTransition(
    val from: String?,
    val to: String?,
    val confidence: Float,
    val at: LocalDateTime,
    val reason: String,
)
