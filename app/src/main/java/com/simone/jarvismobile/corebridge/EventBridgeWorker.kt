package com.simone.jarvismobile.corebridge

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Periodic best-effort flush of [EventQueueStore]'s pending events toward
 * Core — mirrors `BackupWorker`'s entry-point pattern exactly. Unlike a
 * failed backup, a failed flush is never worth bothering the user about
 * (§ richiesta esplicita: "non deve mai... generare errori visibili in
 * UI") — it always succeeds from WorkManager's point of view; the events
 * themselves simply stay queued (and eventually expire per
 * [com.simone.jarvismobile.core.bridge.EventQueuePolicy]) until Core is
 * reachable again.
 */
class EventBridgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val deps = EntryPointAccessors.fromApplication(
        appContext.applicationContext,
        EventBridgeEntryPoint::class.java,
    )

    override suspend fun doWork(): Result {
        runCatching { deps.eventBridge().flushIfOnline() }
            .onFailure { Log.w(TAG, "event_bridge_flush_worker_failed ${it.javaClass.simpleName}") }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EventBridgeEntryPoint {
        fun eventBridge(): EventBridge
    }

    private companion object {
        const val TAG = "EventBridgeWorker"
    }
}
