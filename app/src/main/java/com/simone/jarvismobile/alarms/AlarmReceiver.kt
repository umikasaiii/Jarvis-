package com.simone.jarvismobile.alarms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.AutomationRunner
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Where an exact alarm lands.
 *
 * A broadcast receiver is what an alarm can wake directly, so the work happens
 * here rather than by handing off to a deferrable job — handing off would put
 * back exactly the delay this change exists to remove.
 *
 * The notification is built from the alarm's own extras rather than by reading
 * the agenda again: at 03:00 on a sleeping phone the cheapest correct thing is
 * to post what was already decided when the alarm was set. Automations do read
 * their rule back, because a rule may have been edited in Obsidian since.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExactAlarms.ACTION_FIRE) return
        val kind = intent.getStringExtra(ExactAlarms.EXTRA_KIND) ?: return
        val id = intent.getStringExtra(ExactAlarms.EXTRA_ID) ?: return

        when (kind) {
            ExactAlarms.KIND_REMINDER -> {
                notify(
                    context = context,
                    id = id,
                    title = "Promemoria JARVIS",
                    text = intent.getStringExtra(ExactAlarms.EXTRA_TITLE).orEmpty(),
                    subText = intent.getStringExtra(ExactAlarms.EXTRA_SUBTEXT).orEmpty(),
                )
            }
            ExactAlarms.KIND_AUTOMATION -> {
                val deps = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AlarmEntryPoint::class.java,
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val repository = deps.automations()
                        repository.reload()
                        val rule = repository.find(id) ?: return@launch
                        if (!rule.enabled) return@launch
                        if (deps.runner().run(rule)) repository.markFired(id)
                        // reload() re-arms the schedule, which is what makes a
                        // daily rule recur: only the next occurrence is ever booked.
                        repository.reload()
                    } catch (e: Throwable) {
                        Log.w(TAG, "alarm_automation_failed ${e.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun notify(context: Context, id: String, title: String, text: String, subText: String) {
        if (text.isBlank()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Route through the shared JARVIS styling so the dragon avatar (large
        // icon), monochrome status-bar icon and accent are identical everywhere.
        val notification = JarvisNotifications.styled(
            context = context,
            channelId = JarvisNotifications.CHANNEL_REMINDERS,
            title = title,
            text = text,
            contentIntent = open,
            expandableText = text,
            withChatAction = true,
        )
            .setSubText(subText.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_BASE + id.hashCode().and(0x0fff), notification)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun automations(): AutomationRepository
        fun agenda(): AgendaRepository
        fun runner(): AutomationRunner
    }

    private companion object {
        const val TAG = "JarvisAlarms"
        const val NOTIFICATION_BASE = 6000
    }
}
