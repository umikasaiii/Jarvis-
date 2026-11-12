package com.simone.jarvismobile.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.core.chat.QueueLiveness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HOTFIX 3 — the durable response queue on a real Room/SQLite on an emulator.
 *
 * Proves: (1) an active task row survives closing and re-opening the database, i.e. what is left behind when
 * the process dies mid-generation (it is the row WorkManager's re-run picks up); (2) a row ORPHANED by such a
 * death stops holding back newer messages after [QueueLiveness.STALE_AFTER_MS], while a live older row still does.
 *
 * Does NOT prove: that WorkManager really re-launches the worker after a kill on the Honor 200/MagicOS, nor the
 * foreground-service behaviour when the Activity is backgrounded — those are device-only checks.
 */
@RunWith(AndroidJUnit4::class)
class AssistantQueueRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "hotfix3_queue_test.db"

    @Before fun clean() { context.deleteDatabase(dbName) }
    @After fun cleanup() { context.deleteDatabase(dbName) }

    private fun open() = Room.databaseBuilder(context, JarvisDatabase::class.java, dbName)
        .allowMainThreadQueries().build()

    @Test fun activeTaskSurvivesProcessRestartOfTheDatabase() = runBlocking {
        var db = open()
        db.assistantTaskDao().insert(AssistantTask(id = "t1", input = "domanda", status = AssistantTaskStatus.GENERATING.name))
        db.close() // process death: nothing is flushed by the app beyond what Room already committed
        db = open()
        val rows = db.assistantTaskDao().activeTasks()
        assertEquals(listOf("t1"), rows.map { it.id })
        assertNotNull(db.assistantTaskDao().get("t1"))
        db.close()
    }

    @Test fun orphanedOlderRowStopsBlockingNewerMessagesButLiveOneStillDoes() = runBlocking {
        val db = open()
        val dao = db.assistantTaskDao()
        val now = System.currentTimeMillis()
        // Orphan: left GENERATING 10 minutes ago by a process that died.
        dao.insert(AssistantTask("orphan", "a", AssistantTaskStatus.GENERATING.name, createdAt = now - 700_000, updatedAt = now - 600_000))
        // Live: touched a minute ago.
        dao.insert(AssistantTask("live", "b", AssistantTaskStatus.GENERATING.name, createdAt = now - 90_000, updatedAt = now - 60_000))
        val newer = now
        val stale = QueueLiveness.staleBefore(now)
        // Only the live older row counts.
        assertEquals(1, dao.olderActiveCount("new", newer, stale))
        dao.update("live", AssistantTaskStatus.COMPLETED.name, 100)
        assertEquals(0, dao.olderActiveCount("new", newer, stale))
        db.close()
    }

    // ---- HOTFIX 4 ------------------------------------------------------------------------------------

    @Test fun stopCutoffCancelsOnlyMessagesCreatedBeforeTheTap() = runBlocking {
        val db = open()
        val dao = db.assistantTaskDao()
        val now = System.currentTimeMillis()
        dao.insert(AssistantTask("before", "a", AssistantTaskStatus.GENERATING.name, createdAt = now - 5_000, updatedAt = now))
        dao.insert(AssistantTask("after", "b", AssistantTaskStatus.QUEUED.name, createdAt = now + 5_000, updatedAt = now))
        assertEquals(listOf("before"), dao.activeIdsUpTo(now))
        db.close()
    }

    @Test fun aStoppedTaskIsNeverFlippedBackToCompleted() = runBlocking {
        val db = open()
        val dao = db.assistantTaskDao()
        dao.insert(AssistantTask("t", "q", AssistantTaskStatus.GENERATING.name))
        dao.update("t", AssistantTaskStatus.CANCELLED.name, 0, errorCode = "stopped")
        assertEquals(0, dao.completeUnlessCancelled("t", "late answer"))
        assertEquals(AssistantTaskStatus.CANCELLED, dao.get("t")!!.parsedStatus)
        assertEquals(null, dao.get("t")!!.output)
        // a task that was NOT cancelled completes normally
        dao.insert(AssistantTask("ok", "q2", AssistantTaskStatus.GENERATING.name))
        assertEquals(1, dao.completeUnlessCancelled("ok", "answer"))
        assertEquals("answer", dao.get("ok")!!.output)
        db.close()
    }

    @Test fun aRetryOfAnOlderMessageIsDetectedWhenNewerOnesWereAnswered() = runBlocking {
        val db = open()
        val dao = db.assistantTaskDao()
        val now = System.currentTimeMillis()
        dao.insert(AssistantTask("old", "q1", AssistantTaskStatus.FAILED.name, createdAt = now - 3_000))
        dao.insert(AssistantTask("new", "q2", AssistantTaskStatus.COMPLETED.name, createdAt = now - 1_000))
        assertEquals(1, dao.newerCompletedCount("old", now - 3_000))
        assertEquals(0, dao.newerCompletedCount("new", now - 1_000))
        db.close()
    }
}
