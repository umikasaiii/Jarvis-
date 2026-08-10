package com.simone.jarvismobile.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.simone.jarvismobile.agenda.AgendaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The quick actions on a reminder notification, handled from the shade:
 *  - "Segna come fatto" ticks the agenda item off (and cancels its alerts);
 *  - "Riprogramma" postpones the reminder by an hour (re-fires the same notice).
 * Both dismiss the current notification so the shade reflects the choice at once.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)

        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderActionEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_DONE -> deps.agenda().setDone(entryId, true)
                    ACTION_SNOOZE -> snooze(context, intent, entryId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Re-fires the same reminder in an hour through the existing worker. */
    private fun snooze(context: Context, intent: Intent, entryId: String) {
        val data = workDataOf(
            ReminderWorker.KEY_ENTRY_ID to entryId,
            ReminderWorker.KEY_TITLE to intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            ReminderWorker.KEY_DATE to intent.getStringExtra(EXTRA_DATE).orEmpty(),
            ReminderWorker.KEY_TIME to intent.getStringExtra(EXTRA_TIME).orEmpty(),
        )
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(data)
            .setInitialDelay(SNOOZE_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderActionEntryPoint {
        fun agenda(): AgendaRepository
    }

    companion object {
        const val ACTION_DONE = "com.simone.jarvismobile.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.simone.jarvismobile.action.REMINDER_SNOOZE"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val SNOOZE_MINUTES = 60L
    }
}
