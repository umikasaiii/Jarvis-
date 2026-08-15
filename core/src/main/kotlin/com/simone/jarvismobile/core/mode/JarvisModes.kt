package com.simone.jarvismobile.core.mode

/**
 * The assistant's modes and what each one *means* (§12).
 *
 * The profile is pure data — which ringer, whether Do-Not-Disturb, whether the
 * wake word listens — so the mapping "SLEEP silences the phone and lets alarms
 * through" is unit-tested here, and the Android layer only has to translate a
 * [RingerPreference] into an `AudioManager` call. A mode is a single exclusive
 * state (the engine's `jarvis_mode` resource): being in one is being in exactly
 * one, and switching is what rules and the user do.
 */
enum class RingerPreference { RING, VIBRATE, SILENT }

/**
 * How much GNSS effort the mode implies. This is not a poller to throttle —
 * the app already requests live GPS only while a navigation session is
 * actively [com.simone.jarvismobile.navigation.NavigationRepository.start]ed,
 * and geofencing is the OS's own passive `addProximityAlert`, not polling —
 * so [LOW_POWER] and [BALANCED] mean the same "no continuous fix" today.
 * What the mode *can* change is the fix interval once a session is running:
 * [HIGH_PRECISION] asks for fixes more often (DRIVING), [LOW_POWER] asks less
 * often (e.g. walking a route at HOME/SLEEP), [BALANCED] is the prior default.
 */
enum class LocationPrecision { LOW_POWER, BALANCED, HIGH_PRECISION }

data class ModeProfile(
    val id: String,
    val label: String,
    /** How the phone should ring in this mode. */
    val ringer: RingerPreference,
    /**
     * Do-Not-Disturb (priority filter): silence notifications, keep alarms and
     * priority calls. This is what makes SLEEP a "Zen" mode rather than a mute.
     */
    val doNotDisturb: Boolean,
    /** Whether the hands-free wake word should be listening (DRIVING). */
    val wakeWord: Boolean,
    /** GNSS effort implied by this mode; see [LocationPrecision]. */
    val locationPrecision: LocationPrecision,
)

object JarvisModes {

    const val HOME = "HOME"
    const val WORK = "WORK"
    const val EVENING = "EVENING"
    const val SLEEP = "SLEEP"
    const val DRIVING = "DRIVING"

    val all: List<ModeProfile> = listOf(
        ModeProfile(
            HOME, "Casa", RingerPreference.RING, doNotDisturb = false, wakeWord = false,
            locationPrecision = LocationPrecision.LOW_POWER,
        ),
        ModeProfile(
            WORK, "Lavoro", RingerPreference.VIBRATE, doNotDisturb = false, wakeWord = false,
            locationPrecision = LocationPrecision.BALANCED,
        ),
        ModeProfile(
            EVENING, "Sera", RingerPreference.RING, doNotDisturb = false, wakeWord = false,
            locationPrecision = LocationPrecision.BALANCED,
        ),
        ModeProfile(
            SLEEP, "Notte", RingerPreference.SILENT, doNotDisturb = true, wakeWord = false,
            locationPrecision = LocationPrecision.LOW_POWER,
        ),
        // Filtered notifications while driving: the same priority filter as
        // SLEEP, so a rule of the road (only urgent things reach you) reuses one
        // mechanism instead of inventing a second notion of "quiet".
        ModeProfile(
            DRIVING, "Guida", RingerPreference.RING, doNotDisturb = true, wakeWord = true,
            locationPrecision = LocationPrecision.HIGH_PRECISION,
        ),
    )

    private val byId = all.associateBy { it.id }

    /** Case-insensitive so a rule storing "home" or "Home" still resolves. */
    fun profile(id: String?): ModeProfile? = id?.let { byId[it.trim().uppercase()] }

    fun isKnown(id: String?): Boolean = profile(id) != null

    /** The mode ids, for validation and the picker. */
    val ids: List<String> get() = all.map { it.id }
}
