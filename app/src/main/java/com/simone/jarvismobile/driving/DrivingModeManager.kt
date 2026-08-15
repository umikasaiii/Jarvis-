package com.simone.jarvismobile.driving

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.audio.WakeWordController
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingMediaState
import com.simone.jarvismobile.core.driving.DrivingModeState
import com.simone.jarvismobile.core.driving.DrivingNavigationState
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.core.driving.DrivingVoiceState
import com.simone.jarvismobile.core.mode.JarvisModes
import com.simone.jarvismobile.mode.JarvisModeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

sealed interface DrivingStartResult {
    data object Started : DrivingStartResult
    data object MissingOverlayPermission : DrivingStartResult
}

/**
 * Single source of truth for Modalità Guida (spec §20): the state the overlay UI
 * renders, and the entry/exit points every caller (Home screen, voice command,
 * rule-engine `OPEN_DRIVING_HUD` action) goes through.
 *
 * Deliberately holds no audio/notification/media logic of its own — those stay
 * in [WakeWordController], `JarvisNotificationListener` and the media
 * controllers this session already has. This class only orchestrates: flips
 * [JarvisModeManager] to DRIVING, pre-arms the wake word for the overlay (not
 * the home screen — see [WakeWordController.setDrivingModeActive]), and starts
 * or stops [DrivingModeService].
 *
 * [wakeWordController] is a [Provider]: the tool registry (which every voice
 * command, including "attiva modalità guida", runs through) depends on this
 * class, and [WakeWordController] depends on the voice pipeline that runs
 * through the tool registry — a direct dependency here would be a cycle. The
 * provider defers resolution to first use, after the graph is built, exactly
 * like [JarvisModeManager]'s own `Provider<AutomationExecutor>`.
 */
@Singleton
class DrivingModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeManager: JarvisModeManager,
    private val wakeWordController: Provider<WakeWordController>,
    private val places: PlaceRepository,
) {
    private val _state = MutableStateFlow(DrivingModeState())
    val state: StateFlow<DrivingModeState> = _state.asStateFlow()

    /** Destination prepared by "imposta navigazione per X", consumed by "avvia percorso". */
    @Volatile private var pendingDestinationQuery: String? = null

    /** The mode active before DRIVING, restored on close (spec §18: "riporta JARVIS alla modalità normale"). */
    @Volatile private var previousMode: String? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Messages and Spotify both need this; the overlay still works without it. */
    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts Modalità Guida: without overlay permission this does nothing but
     * report [DrivingStartResult.MissingOverlayPermission] so the caller can send
     * the user to the real grant screen — it never draws a fake overlay or
     * pretends the mode is active.
     */
    suspend fun start(): DrivingStartResult {
        if (_state.value.active) return DrivingStartResult.Started
        if (!hasOverlayPermission()) return DrivingStartResult.MissingOverlayPermission
        previousMode = modeManager.mode.value
        runCatching { modeManager.setMode(JarvisModes.DRIVING) }
        ContextCompat.startForegroundService(context, Intent(context, DrivingModeService::class.java))
        return DrivingStartResult.Started
    }

    /** Part of the graceful shutdown sequence — called before the service actually stops. */
    suspend fun restorePreviousMode() {
        val mode = previousMode ?: return
        previousMode = null
        runCatching { modeManager.setMode(mode) }
    }

    /**
     * Sends an explicit stop action rather than calling `stopService` directly,
     * so [DrivingModeService] gets a chance to run its graceful shutdown first —
     * saving the parking note (spec §18.7) — before it actually tears down.
     */
    fun stop() {
        context.startService(Intent(context, DrivingModeService::class.java).setAction(DrivingModeService.ACTION_STOP))
    }

    /** Called by [DrivingModeService] once the overlay window is actually up. */
    fun onServiceStarted() {
        _state.update { DrivingModeState(active = true) }
        wakeWordController.get().setDrivingModeActive(true)
    }

    /** Called by [DrivingModeService] on teardown, whatever the reason. */
    fun onServiceStopped() {
        _state.update { DrivingModeState(active = false) }
        wakeWordController.get().setDrivingModeActive(false)
        pendingDestinationQuery = null
    }

    fun setVoiceState(v: DrivingVoiceState) {
        _state.update { it.copy(voiceState = v) }
    }

    fun togglePanel(panel: DrivingExpandedPanel) {
        _state.update { it.toggle(panel) }
    }

    fun showPanel(panel: DrivingExpandedPanel) {
        _state.update { it.expand(panel) }
    }

    fun collapsePanels() {
        _state.update { it.collapse() }
    }

    fun updateNotifications(items: List<DrivingNotification>) {
        _state.update { it.copy(notifications = items) }
    }

    fun updateMedia(media: DrivingMediaState?) {
        _state.update { it.copy(media = media) }
    }

    private fun updateNavigation(nav: DrivingNavigationState?) {
        _state.update { it.copy(navigation = nav) }
    }

    /**
     * "imposta navigazione per X": resolves a saved place (casa/lavoro/any place
     * saved in Automazioni › Luoghi) by name first, otherwise hands the spoken
     * text straight to Maps as a search query — never guesses coordinates for an
     * address it cannot resolve itself.
     */
    suspend fun prepareNavigation(destinationText: String): DrivingNavigationState {
        val saved = places.byId(slug(destinationText))
        val query = if (saved != null) "${saved.latitude},${saved.longitude}" else destinationText
        val label = saved?.displayName ?: destinationText
        pendingDestinationQuery = query
        val nav = DrivingNavigationState(destinationLabel = label, routePrepared = true, routeStarted = false)
        updateNavigation(nav)
        return nav
    }

    /** The query [prepareNavigation] resolved, so the caller can launch the preview intent. */
    fun lastPreparedQuery(): String? = pendingDestinationQuery

    /** "avvia percorso": nothing to start if no destination was prepared first. */
    fun pendingDestination(): String? = pendingDestinationQuery

    fun markRouteStarted() {
        _state.update { s ->
            s.navigation?.let { s.copy(navigation = it.copy(routeStarted = true)) } ?: s
        }
    }

    private fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_')
}
