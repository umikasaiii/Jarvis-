package com.simone.jarvismobile.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.simone.jarvismobile.ui.MainActivity

/**
 * One place that builds every JARVIS intent/deep link, so widgets, notifications
 * and tests all trigger the SAME entry points — no duplicated launch logic.
 *
 *   jarvis://voice → open + start a voice session
 *   jarvis://chat  → open the chat, focused
 *   jarvis://stop  → cancel LLM/TTS/voice (a broadcast, never opens the app)
 */
object JarvisIntents {

    const val SCHEME = "jarvis"
    const val HOST_VOICE = "voice"
    const val HOST_CHAT = "chat"
    const val HOST_STOP = "stop"

    fun voiceIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("$SCHEME://$HOST_VOICE")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun chatIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("$SCHEME://$HOST_CHAT")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun stopBroadcast(context: Context): Intent =
        Intent(context, JarvisActionReceiver::class.java).apply {
            action = JarvisActionReceiver.ACTION_STOP
        }

    // --- PendingIntents (widgets / notifications) -----------------------------

    private const val REQ_VOICE = 4101
    private const val REQ_CHAT = 4102
    private const val REQ_STOP = 4103

    private val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun voicePending(context: Context): PendingIntent =
        PendingIntent.getActivity(context, REQ_VOICE, voiceIntent(context), immutable)

    fun chatPending(context: Context): PendingIntent =
        PendingIntent.getActivity(context, REQ_CHAT, chatIntent(context), immutable)

    fun stopPending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQ_STOP, stopBroadcast(context), immutable)
}
