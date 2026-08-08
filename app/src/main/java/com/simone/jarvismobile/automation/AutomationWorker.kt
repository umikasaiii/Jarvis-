package com.simone.jarvismobile.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Runs one time-based automation, then re-arms the schedule.
 *
 * Re-arming from inside the worker is what makes a rule recurring: the scheduler
 * only ever books the NEXT occurrence, so a rule that never fires cannot leave a
 * queue of missed ones behind, and one that fires books its successor from the
 * time it actually ran.
 *
 * Dependencies come through an entry point rather than @HiltWorker, matching the
 * existing workers in this project (no androidx.hilt.work dependency).
 */
class AutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val deps = EntryPointAccessors.fromApplication(
        appContext.applicationContext,
        AutomationEntryPoint::class.java,
    )

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val repository = deps.automations()

        // Read from the file rather than trusting a cached list: the rule may
        // have been edited in Obsidian since it was scheduled.
        repository.reload()
        val automation = repository.find(id) ?: return Result.success()
        if (!automation.enabled) return Result.success()

        val ran = runCatching { deps.runner().run(automation) }.getOrDefault(false)
        if (ran) repository.markFired(id)
        // markFired writes the file, which re-syncs the schedule and books the
        // next occurrence. Do it explicitly too, in case the write failed.
        repository.reload()
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutomationEntryPoint {
        fun automations(): AutomationRepository
        fun runner(): AutomationRunner
    }

    companion object {
        const val KEY_ID = "automation_id"
    }
}
