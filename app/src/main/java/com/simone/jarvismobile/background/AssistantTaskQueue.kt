package com.simone.jarvismobile.background

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.simone.jarvismobile.audio.SessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import com.simone.jarvismobile.core.chat.EnqueueGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable queue for local answers. Room owns state; WorkManager owns execution. */
@Singleton
class AssistantTaskQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: AssistantTaskDao,
    private val coordinator: SessionCoordinator,
) {
    private val workManager = WorkManager.getInstance(context)

    val recent: Flow<List<AssistantTask>> = dao.observeRecent()
    val activeCount: Flow<Int> = dao.observeActiveCount()

    // BETA 2/8: check-then-insert must be atomic, or a double tap enqueues the same message twice.
    private val enqueueMutex = Mutex()

    suspend fun enqueueResponse(text: String): String = enqueueMutex.withLock {
        when (val decision = EnqueueGuard.decide(dao.activeTasks().map { it.id to it.input }, text)) {
            is EnqueueGuard.Decision.Duplicate -> return@withLock decision.existingTaskId
            EnqueueGuard.Decision.Empty -> return@withLock ""
            EnqueueGuard.Decision.Accept -> Unit
        }
        val id = UUID.randomUUID().toString()
        dao.insert(AssistantTask(id = id, input = text.trim()))
        enqueue(id, ExistingWorkPolicy.KEEP)
        id
    }

    suspend fun retry(id: String) {
        val task = dao.get(id) ?: return
        dao.update(id, AssistantTaskStatus.QUEUED.name, progress = 0)
        enqueue(id, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancel(id: String) {
        // HOTFIX 4: stop THIS task's turn only; newer messages keep running.
        coordinator.cancelTurn(id)
        workManager.cancelUniqueWork(workName(id))
        dao.update(id, AssistantTaskStatus.CANCELLED.name, progress = 0, errorCode = "stopped")
    }

    /**
     * Stops every unfinished response CREATED UP TO [cutoffMs] (the moment the user tapped Stop / «Nuova
     * conversazione»). A message sent right after the tap is newer than the cut-off and is left alone — the
     * engine/turn stop itself already happened synchronously in the caller.
     */
    suspend fun cancelActive(cutoffMs: Long = Long.MAX_VALUE) {
        dao.activeIdsUpTo(cutoffMs).forEach { id ->
            coordinator.cancelTurn(id)
            workManager.cancelUniqueWork(workName(id))
            dao.update(id, AssistantTaskStatus.CANCELLED.name, progress = 0, errorCode = "stopped")
        }
    }

    private fun enqueue(id: String, policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<AssistantTaskWorker>()
            .setInputData(workDataOf(AssistantTaskWorker.KEY_TASK_ID to id))
            .addTag(TAG_RESPONSES)
            .addTag("task:$id")
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, work)
    }

    companion object {
        const val TAG_RESPONSES = "jarvis_responses"
        fun workName(id: String) = "jarvis_response_$id"
    }
}
