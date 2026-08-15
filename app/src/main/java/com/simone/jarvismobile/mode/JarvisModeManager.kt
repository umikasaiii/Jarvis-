package com.simone.jarvismobile.mode

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.simone.jarvismobile.automation.rule.AutomationExecutor
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.core.mode.JarvisModes
import com.simone.jarvismobile.core.mode.LocationPrecision
import com.simone.jarvismobile.core.mode.ModeProfile
import com.simone.jarvismobile.core.mode.RingerPreference
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The assistant's current mode and the phone behaviour it implies (§12).
 *
 * Modes are the engine's single exclusive `jarvis_mode` state: the manager holds
 * which one is active, applies its [ModeProfile] to the phone (ringer, Do-Not-
 * Disturb), and — because a mode change is itself something rules react to —
 * fires `JARVIS_MODE_ENTER/EXIT` so "quando entro in SLEEP…" can run.
 *
 * The executor is injected as a [Provider] on purpose: an action handler sets the
 * mode, the handler is bound into the executor, so a direct dependency would be a
 * cycle. The provider is resolved lazily at call time, after the graph is built.
 *
 * What it deliberately does NOT force: it applies Do-Not-Disturb only when the
 * user has granted policy access, and never pretends it did otherwise — a mode
 * that silently failed to silence the phone would be exactly the dishonesty the
 * rest of the engine avoids. Nor does DRIVING start any background microphone:
 * it only pre-arms the existing, opt-in, foreground-only wake word, restoring
 * whatever the user had set once driving ends.
 */
@Singleton
class JarvisModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextEngine: ContextEngine,
    private val executor: Provider<AutomationExecutor>,
    private val settings: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _mode = MutableStateFlow(prefs.getString(KEY, JarvisModes.HOME) ?: JarvisModes.HOME)
    val mode: StateFlow<String> = _mode.asStateFlow()

    /**
     * GNSS effort implied by the current mode (§ Location Engine power modes).
     * Consumed by location callers to size an active fix interval — see
     * [LocationPrecision] for what this can and cannot mean today.
     */
    val locationPrecision: StateFlow<LocationPrecision> = _mode
        .map { JarvisModes.profile(it)?.locationPrecision ?: LocationPrecision.BALANCED }
        .stateIn(scope, SharingStarted.Eagerly, LocationPrecision.BALANCED)

    init {
        // Seed the context so a "se in modalità X" condition is answerable at once.
        contextEngine.onMode(_mode.value)
    }

    /** True when Do-Not-Disturb can actually be set (a settings-screen grant). */
    fun hasDndAccess(): Boolean =
        runCatching {
            (context.getSystemService(NotificationManager::class.java))?.isNotificationPolicyAccessGranted == true
        }.getOrDefault(false)

    /**
     * Switches to [id], applies its effects and fires the mode triggers. Returns
     * false for an unknown mode rather than guessing one.
     */
    suspend fun setMode(id: String): Boolean {
        val profile = JarvisModes.profile(id) ?: run {
            Log.w(TAG, "unknown_mode $id")
            return false
        }
        val old = _mode.value
        applyEffects(profile)
        _mode.value = profile.id
        prefs.edit().putString(KEY, profile.id).apply()
        contextEngine.onMode(profile.id)

        if (!old.equals(profile.id, ignoreCase = true)) {
            val now = LocalDateTime.now()
            val ctx = runCatching { contextEngine.evaluationContext(now = now) }.getOrNull()
            if (ctx != null) {
                runCatching {
                    executor.get().onTrigger(
                        TriggerEvent(TriggerRegistry.JARVIS_MODE_EXIT, now, dedupKey = old.uppercase()),
                        ctx,
                    )
                }
                runCatching {
                    executor.get().onTrigger(
                        TriggerEvent(TriggerRegistry.JARVIS_MODE_ENTER, now, dedupKey = profile.id),
                        ctx,
                    )
                }
            }
        }
        Log.i(TAG, "mode ${old} -> ${profile.id}")
        return true
    }

    private suspend fun applyEffects(profile: ModeProfile) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            audio?.ringerMode = when (profile.ringer) {
                RingerPreference.RING -> AudioManager.RINGER_MODE_NORMAL
                RingerPreference.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                RingerPreference.SILENT -> AudioManager.RINGER_MODE_SILENT
            }
        }.onFailure { Log.w(TAG, "ringer_failed ${it.javaClass.simpleName}") }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)) {
            runCatching {
                nm.setInterruptionFilter(
                    if (profile.doNotDisturb) {
                        // Priority filter: silence the noise, keep alarms and
                        // priority calls — a Zen mode, not a mute.
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    } else {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    },
                )
            }.onFailure { Log.w(TAG, "dnd_failed ${it.javaClass.simpleName}") }
        }
        applyWakeWordPreArm(profile)
    }

    /**
     * DRIVING pre-arms the existing, foreground-only wake word and spoken
     * background replies, so a phone mounted on the dash with the screen on
     * starts listening hands-free the moment the app is in view. This does
     * **not** start any background microphone: [WakeWordController][com.simone.jarvismobile.audio.WakeWordController]
     * still gates on the app being foregrounded, exactly as before — this only
     * flips the same toggle the user could flip in Impostazioni. The prior
     * values are remembered once and restored on leaving DRIVING, so the mode
     * never permanently overwrites a preference the user set themselves.
     */
    private suspend fun applyWakeWordPreArm(profile: ModeProfile) {
        val wasDriving = prefs.contains(KEY_PRE_DRIVE_WAKE)
        if (profile.id == JarvisModes.DRIVING) {
            if (!wasDriving) {
                val wake = runCatching { settings.wakeWordEnabled.first() }.getOrDefault(false)
                val speak = runCatching { settings.speakBackgroundResponses.first() }.getOrDefault(false)
                prefs.edit()
                    .putBoolean(KEY_PRE_DRIVE_WAKE, wake)
                    .putBoolean(KEY_PRE_DRIVE_SPEAK, speak)
                    .apply()
            }
            runCatching { settings.setWakeWordEnabled(true) }
            runCatching { settings.setSpeakBackgroundResponses(true) }
        } else if (wasDriving) {
            val wake = prefs.getBoolean(KEY_PRE_DRIVE_WAKE, false)
            val speak = prefs.getBoolean(KEY_PRE_DRIVE_SPEAK, false)
            runCatching { settings.setWakeWordEnabled(wake) }
            runCatching { settings.setSpeakBackgroundResponses(speak) }
            prefs.edit().remove(KEY_PRE_DRIVE_WAKE).remove(KEY_PRE_DRIVE_SPEAK).apply()
        }
    }

    private companion object {
        const val TAG = "JarvisMode"
        const val PREFS = "jarvis_mode"
        const val KEY = "current_mode"
        const val KEY_PRE_DRIVE_WAKE = "pre_drive_wake"
        const val KEY_PRE_DRIVE_SPEAK = "pre_drive_speak"
    }
}
