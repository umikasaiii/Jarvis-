package com.simone.jarvismobile.background

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable queue for local answers. Room owns state; WorkManager owns execution. */
@Singleton
class AssistantTaskQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: AssistantTaskDao,
) {
    private val workManager = WorkManager.getInstance(context)

    val recent: Flow<List<AssistantTask>> = dao.observeRecent()
    val activeCount: Flow<Int> = dao.observeActiveCount()

    suspend fun enqueueResponse(text: String): String {
        val id = UUID.randomUUID().toString()
        dao.insert(AssistantTask(id = id, input = text.trim()))
        enqueue(id, ExistingWorkPolicy.KEEP)
        return id
    }

    suspend fun retry(id: String) {
        val task = dao.get(id) ?: return
        dao.update(id, AssistantTaskStatus.QUEUED.name, progress = 0)
        enqueue(id, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
        dao.update(id, AssistantTaskStatus.CANCELLED.name, progress = 0)
    }

    /** Stops every unfinished response. The chat UI currently permits one at a time. */
    suspend fun cancelActive() {
        dao.activeIds().forEach { id ->
            workManager.cancelUniqueWork(workName(id))
            dao.update(id, AssistantTaskStatus.CANCELLED.name, progress = 0)
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
