package com.simone.jarvismobile.background

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.simone.jarvismobile.R
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/** Executes one local response independently of any Activity or ViewModel. */
class AssistantTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dependencies = EntryPointAccessors.fromApplication(
        appContext,
        WorkerEntryPoint::class.java,
    )

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val dao = dependencies.taskDao()
        val task = dao.get(id) ?: return Result.failure()
        if (task.parsedStatus == AssistantTaskStatus.CANCELLED) return Result.success()

        setForeground(processingForeground(id, 5, "Preparazione"))
        return runCatching {
            update(dao, id, AssistantTaskStatus.LOADING_MODEL, 10)
            dependencies.coordinator().ensureModelReady()

            update(dao, id, AssistantTaskStatus.RETRIEVING_MEMORY, 25)
            dependencies.coordinator().ensureMemoryReady()

            update(dao, id, AssistantTaskStatus.UNDERSTANDING, 40)
            setForeground(processingForeground(id, 40, "Comprensione della richiesta"))

            update(dao, id, AssistantTaskStatus.GENERATING, 60)
            setForeground(processingForeground(id, 60, "Generazione della risposta"))
            val answer = dependencies.coordinator().processQueuedText(id, task.input)
                ?: error("empty_answer")

            dao.update(id, AssistantTaskStatus.COMPLETED.name, 100, output = answer)
            showReadyNotification(id, answer)
            Result.success()
        }.getOrElse { error ->
            if (isStopped) {
                dao.update(id, AssistantTaskStatus.CANCELLED.name, 0)
                Result.success()
            } else {
                val code = error.message?.take(80) ?: error.javaClass.simpleName
                dao.update(id, AssistantTaskStatus.FAILED.name, 0, errorCode = code)
                if (runAttemptCount < 1) Result.retry() else Result.failure()
            }
        }
    }

    private suspend fun update(
        dao: AssistantTaskDao,
        id: String,
        status: AssistantTaskStatus,
        progress: Int,
    ) = dao.update(id, status.name, progress)

    private fun processingForeground(id: String, progress: Int, detail: String): ForegroundInfo {
        val cancel = PendingIntent.getBroadcast(
            applicationContext,
            id.hashCode(),
            Intent(applicationContext, CancelTaskReceiver::class.java)
                .putExtra(CancelTaskReceiver.EXTRA_TASK_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, JarvisNotifications.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_tile_jarvis)
            .setContentTitle("JARVIS sta lavorando")
            .setContentText(detail)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Annulla", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROCESSING_NOTIFICATION_BASE + id.hashCode().and(0x0fff),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(PROCESSING_NOTIFICATION_BASE + id.hashCode().and(0x0fff), notification)
        }
    }

    private suspend fun showReadyNotification(taskId: String, answer: String) {
        if (!dependencies.settings().responseNotifications.first()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val showPreview = dependencies.settings().showResponsePreview.first()
        val openChat = PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode(),
            Intent(applicationContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(
            applicationContext,
            JarvisNotifications.CHANNEL_RESPONSES,
        )
            .setSmallIcon(R.drawable.ic_tile_jarvis)
            .setContentTitle("Risposta JARVIS pronta")
            .setContentText(if (showPreview) answer.take(180) else "Tocca per aprire la conversazione")
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (showPreview) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
        if (showPreview) builder.setStyle(NotificationCompat.BigTextStyle().bigText(answer.take(700)))
        val notification: Notification = builder.build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(RESPONSE_NOTIFICATION_BASE + taskId.hashCode().and(0x0fff), notification)
        } catch (_: SecurityException) {
            // The permission can be revoked between the check and notify().
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun coordinator(): SessionCoordinator
        fun taskDao(): AssistantTaskDao
        fun settings(): SettingsRepository
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val PROCESSING_NOTIFICATION_BASE = 2_000
        private const val RESPONSE_NOTIFICATION_BASE = 3_000
    }
}
