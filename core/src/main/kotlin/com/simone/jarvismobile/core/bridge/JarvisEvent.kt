package com.simone.jarvismobile.core.bridge

import com.simone.jarvismobile.core.tools.SensitivityLevel

/**
 * Event Bridge's pure data model — one significant Android happening JARVIS
 * publishes toward JARVIS Core. Android stays the source of truth and the
 * only thing that *acts* on these (geofencing, automations, notifications,
 * driving mode never move here — § vincolo esplicito); Event Bridge only
 * reports them onward.
 *
 * Privacy reuses [SensitivityLevel] (`PUBLIC`/`PERSONAL`/`SENSITIVE`) instead
 * of a second, near-identical three-tier enum — the same concept `ToolPolicy`
 * already uses for how sensitive the *data* a tool touches is.
 */
data class JarvisEvent(
    val id: String,
    val type: JarvisEventType,
    val timestampMs: Long,
    val source: String,
    val priority: EventPriority,
    /** Deliberately minimal — a reference/metadatum, not a full payload, especially for [SensitivityLevel.SENSITIVE]. */
    val payload: Map<String, String> = emptyMap(),
    val privacyLevel: SensitivityLevel,
)

enum class JarvisEventType {
    APP_STARTED,
    USER_UNLOCKED,
    LOCATION_CONTEXT_CHANGED,
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    BATTERY_LOW,
    BATTERY_CHARGING,
    NETWORK_CHANGED,
    HEADPHONES_CONNECTED,
    HEADPHONES_DISCONNECTED,
    CAR_MODE_ENTERED,
    CAR_MODE_EXITED,
    CALENDAR_CONTEXT_CHANGED,
    NOTIFICATION_CONTEXT,
    REMINDER_TRIGGERED,
    DEVICE_STATE_CHANGED,
}

enum class EventPriority { LOW, NORMAL, HIGH, CRITICAL }
