package com.simone.jarvismobile.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.agenda.AgendaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "Segna come completato" action on a reminder notification: it ticks the
 * agenda item off from the shade — no app open — via [AgendaRepository.setDone]
 * (which also cancels its remaining alerts), then dismisses the notification so
 * the shade reflects the choice at once.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)

        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderActionEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                deps.agenda().setDone(entryId, true)
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderActionEntryPoint {
        fun agenda(): AgendaRepository
    }

    companion object {
        const val ACTION_DONE = "com.simone.jarvismobile.action.REMINDER_DONE"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
