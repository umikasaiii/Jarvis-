package com.simone.jarvismobile.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simone.jarvismobile.audio.SessionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the "Stop" action from a widget or a notification WITHOUT opening the
 * app: stopping should be instant and silent, not a screen. It cancels the
 * current LLM generation, TTS and any voice session through the existing
 * [SessionCoordinator] — no controller is duplicated.
 */
@AndroidEntryPoint
class JarvisActionReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: SessionCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> runCatching { coordinator.cancel() }
        }
    }

    companion object {
        const val ACTION_STOP = "com.simone.jarvismobile.action.STOP"
    }
}
