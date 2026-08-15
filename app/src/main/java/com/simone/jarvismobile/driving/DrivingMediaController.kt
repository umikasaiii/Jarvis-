package com.simone.jarvismobile.driving

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.core.driving.DrivingMediaState
import com.simone.jarvismobile.tools.JarvisNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live view of Android's own active media session (Spotify or anything else
 * publishing one) — the same [MediaSessionManager]/[MediaController] pair
 * [com.simone.jarvismobile.tools.MediaControlTool] already uses for one-shot
 * control, kept attached with a callback here so the overlay updates as the
 * track changes instead of only on the next poll. Playlist/queue/recent browsing
 * has no equivalent in the plain `MediaController` API most sessions expose —
 * [queueTitles] returns whatever the session actually publishes (often nothing)
 * rather than a library JARVIS cannot really see (spec §12).
 */
@Singleton
class DrivingMediaController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _media = MutableStateFlow<DrivingMediaState?>(null)
    val media: StateFlow<DrivingMediaState?> = _media.asStateFlow()

    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { attach() }
    }

    fun start() = attach()

    fun stop() {
        runCatching { controller?.unregisterCallback(callback) }
        controller = null
        _media.value = null
    }

    /** Re-checks for an active session; call after a fresh notification-access grant too. */
    fun attach() {
        runCatching { controller?.unregisterCallback(callback) }
        controller = null
        if (!hasAccess()) {
            _media.value = null
            return
        }
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(context, JarvisNotificationListener::class.java)
        val active = manager?.let { runCatching { it.getActiveSessions(component).firstOrNull() }.getOrNull() }
        if (active == null) {
            _media.value = null
            return
        }
        controller = active
        runCatching { active.registerCallback(callback) }
        publish()
    }

    fun art(): Bitmap? = controller?.metadata?.let { meta ->
        meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    /** Whatever the session actually publishes as an upcoming queue; empty if none. */
    fun queueTitles(): List<String> = controller?.queue.orEmpty()
        .mapNotNull { it.description?.title?.toString() }

    fun previous() = controller?.transportControls?.skipToPrevious()
    fun next() = controller?.transportControls?.skipToNext()
    fun pause() = controller?.transportControls?.pause()
    fun play() = controller?.transportControls?.play()

    fun toggle() {
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) pause() else play()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _media.value = null
            return
        }
        val meta = c.metadata
        val state = c.playbackState
        _media.value = DrivingMediaState(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            hasArt = art() != null,
        )
    }

    private fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
