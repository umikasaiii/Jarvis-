package com.simone.jarvismobile.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Cancel action shared by the foreground notification and the task centre. */
class CancelTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val entry = EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java)
        entry.coordinator().cancelTextGeneration()
        WorkManager.getInstance(context).cancelUniqueWork(AssistantTaskQueue.workName(taskId))
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                entry.taskDao().update(taskId, AssistantTaskStatus.CANCELLED.name, 0)
            }
            result.finish()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun coordinator(): com.simone.jarvismobile.audio.SessionCoordinator
        fun taskDao(): AssistantTaskDao
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
