package com.simone.jarvismobile.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Non avvisarmi più di questo" action on a suggestion: it mutes that
 * category (an explicit, reversible setting) and dismisses the notification. This
 * is the per-message control from the spec — one tap and that kind stops.
 */
class ProactiveActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MUTE) return
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: return
        val kind = runCatching { ProactiveKind.valueOf(kindName) }.getOrNull() ?: return
        val settings = EntryPointAccessors
            .fromApplication(context.applicationContext, ProactiveActionEntryPoint::class.java)
            .settings()
        NotificationManagerCompat.from(context).cancel(ProactiveNotifier.notificationId(kind))
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                settings.setProactiveKindMuted(kindName, true)
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProactiveActionEntryPoint {
        fun settings(): SettingsRepository
    }

    companion object {
        const val ACTION_MUTE = "com.simone.jarvismobile.action.PROACTIVE_MUTE"
        const val EXTRA_KIND = "kind"
    }
}
