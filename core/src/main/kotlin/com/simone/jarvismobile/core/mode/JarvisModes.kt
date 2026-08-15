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
)

object JarvisModes {

    const val HOME = "HOME"
    const val WORK = "WORK"
    const val EVENING = "EVENING"
    const val SLEEP = "SLEEP"
    const val DRIVING = "DRIVING"

    val all: List<ModeProfile> = listOf(
        ModeProfile(HOME, "Casa", RingerPreference.RING, doNotDisturb = false, wakeWord = false),
        ModeProfile(WORK, "Lavoro", RingerPreference.VIBRATE, doNotDisturb = false, wakeWord = false),
        ModeProfile(EVENING, "Sera", RingerPreference.RING, doNotDisturb = false, wakeWord = false),
        ModeProfile(SLEEP, "Notte", RingerPreference.SILENT, doNotDisturb = true, wakeWord = false),
        // Filtered notifications while driving: the same priority filter as
        // SLEEP, so a rule of the road (only urgent things reach you) reuses one
        // mechanism instead of inventing a second notion of "quiet".
        ModeProfile(DRIVING, "Guida", RingerPreference.RING, doNotDisturb = true, wakeWord = true),
    )

    private val byId = all.associateBy { it.id }

    /** Case-insensitive so a rule storing "home" or "Home" still resolves. */
    fun profile(id: String?): ModeProfile? = id?.let { byId[it.trim().uppercase()] }

    fun isKnown(id: String?): Boolean = profile(id) != null

    /** The mode ids, for validation and the picker. */
    val ids: List<String> get() = all.map { it.id }
}
