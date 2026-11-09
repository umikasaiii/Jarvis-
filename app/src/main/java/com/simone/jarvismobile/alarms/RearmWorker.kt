package com.simone.jarvismobile.alarms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simone.jarvismobile.core.reliability.RearmTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * BETA 7 — persistent safety net for [AlarmRearmer]. A BroadcastReceiver's `goAsync()` window is short and its process
 * can be killed before the coroutine finishes; WorkManager persists the request, so if the in-receiver attempt is cut
 * short the same (idempotent) pass runs again. It is NOT a second scheduler: it calls the same single re-arm path.
 * Deferrable by nature, so it is a backstop, not a replacement for the immediate attempt.
 */
class RearmWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, RearmEntryPoint::class.java)
        val report = deps.rearmer().rearm(RearmTrigger.WORKER_SAFETY_NET)
        // A failed step is retried a bounded number of times; the system's own backoff applies.
        return if (report.allOk || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RearmEntryPoint {
        fun rearmer(): AlarmRearmer
    }

    companion object {
        private const val WORK_NAME = "jarvis_rearm_safety_net"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, reason: RearmTrigger) {
            val request = OneTimeWorkRequestBuilder<RearmWorker>()
                .setInputData(workDataOf("reason" to reason.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
