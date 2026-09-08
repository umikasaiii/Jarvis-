package com.simone.jarvismobile.backup

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * A restored `assistant_tasks` row (Phase 6d's persistent response queue)
 * can carry a non-terminal status — QUEUED/LOADING_MODEL/UNDERSTANDING/
 * RETRIEVING_MEMORY/GENERATING — simply because that was its state at
 * backup time. The WorkManager job that would have carried it forward is
 * NOT part of this backup (WorkManager owns its own separate database) and
 * restore never re-enqueues anything, so left alone such a row would sit
 * forever as a phantom "in progress" task no worker will ever finish or
 * fail (§ JARVIS Implementation Master Plan PASSAGGIO 10 §C/§J: a restore
 * must never resurrect a pending/in-flight side effect merely because it
 * was pending at backup time).
 *
 * [TERMINAL_STATUSES] mirrors `AssistantTaskStatus`'s three terminal values
 * by name rather than importing that enum, so this stays a plain string
 * constant [RestoreSanitizeCallback] can use from Room's own `onOpen()` —
 * the one point Room guarantees fires only AFTER every registered migration
 * has already brought `assistant_tasks` to its current shape (Room's
 * documented `Callback` lifecycle: migrations run, THEN `onOpen`), so a
 * restored older schema is always sanitized against its current, migrated
 * shape — never a parallel migration path racing Room's own.
 */
internal object AssistantTaskRestoreSanitizer {
    val TERMINAL_STATUSES = listOf("COMPLETED", "FAILED", "CANCELLED")

    /** Cancels every non-terminal row in place; never deletes history. */
    val SANITIZE_SQL: String = buildString {
        append("UPDATE assistant_tasks SET status = 'CANCELLED', progress = 0 WHERE status NOT IN (")
        append(TERMINAL_STATUSES.joinToString(", ") { "'$it'" })
        append(')')
    }
}

/**
 * Runs [AssistantTaskRestoreSanitizer.SANITIZE_SQL] exactly once, only when
 * [BackupRepository] left the marker behind after a restore that included
 * the database — an app start with no restore ever performed never touches
 * this table. Registered on [JarvisDatabase][com.simone.jarvismobile.background.JarvisDatabase]'s
 * builder in `DatabaseModule`.
 */
internal class RestoreSanitizeCallback(private val context: Context) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        val marker = markerFile(context)
        if (!marker.exists()) return
        runCatching { db.execSQL(AssistantTaskRestoreSanitizer.SANITIZE_SQL) }
        runCatching { marker.delete() }
    }

    companion object {
        private const val MARKER_NAME = ".sanitize_assistant_tasks"

        /** Also called by [BackupRepository] to leave the marker after a db cutover. */
        fun markerFile(context: Context): File {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            return File(dir, MARKER_NAME)
        }
    }
}
