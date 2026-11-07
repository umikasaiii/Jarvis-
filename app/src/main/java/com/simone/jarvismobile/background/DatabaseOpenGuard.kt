package com.simone.jarvismobile.background

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * STEP 2 — non-destructive safety around opening `jarvis.db`.
 *
 *  1. **Classify** the on-disk `PRAGMA user_version` ([classify], pure/JVM-tested).
 *     A version this build cannot reach ([Plan.Unsupported]: a downgrade, or one
 *     below [JarvisMigrations.OLDEST_SUPPORTED_VERSION]) throws
 *     [UnsupportedDatabaseVersionException] *before* Room is built. Nothing is
 *     deleted, renamed or rewritten: the file stays exactly as found.
 *  2. **Verified snapshot before an upgrade** ([PremigrationSnapshotter]): a WAL-complete,
 *     integrity-checked, row-count-verified copy under `databases/premigration/` (last [KEEP]).
 *     **Fail closed:** if it cannot be made and verified (disk full, permissions, corruption)
 *     the upgrade does not run and [SnapshotFailedException] explains why; the database is
 *     left exactly as found.
 *
 * Only a version/size/name is ever logged — never table content.
 */
object DatabaseOpenGuard {

    sealed interface Plan {
        /** No file yet, or an empty one (`user_version` 0): Room creates the current schema. */
        data object Fresh : Plan
        data object UpToDate : Plan
        data class Upgrade(val from: Int) : Plan
        data class Unsupported(val onDisk: Int, val reason: String) : Plan
    }

    class UnsupportedDatabaseVersionException(message: String) : IllegalStateException(message)

    const val KEEP = 2
    const val SNAPSHOT_DIR = "premigration"
    private const val TAG = "JarvisDbGuard"

    fun classify(
        onDiskVersion: Int,
        current: Int = JarvisMigrations.CURRENT_VERSION,
        oldest: Int = JarvisMigrations.OLDEST_SUPPORTED_VERSION,
    ): Plan = when {
        onDiskVersion == 0 -> Plan.Fresh
        onDiskVersion == current -> Plan.UpToDate
        onDiskVersion > current -> Plan.Unsupported(
            onDiskVersion,
            "il database è della versione $onDiskVersion, più recente di quella di questa app ($current): " +
                "un'app più vecchia non può aprirlo senza perdere dati",
        )
        onDiskVersion < oldest -> Plan.Unsupported(
            onDiskVersion,
            "il database è della versione $onDiskVersion, precedente alla più vecchia migrabile ($oldest)",
        )
        else -> Plan.Upgrade(onDiskVersion)
    }

    /** `jarvis-v<from>-<epochMs>.db` — sorts by (from, time) lexically only per-version; use [parse]. */
    fun snapshotName(from: Int, nowMs: Long): String = "jarvis-v$from-$nowMs.db"

    /** The snapshot file names to delete so that only the newest [keep] remain. */
    fun toPrune(names: List<String>, keep: Int = KEEP): List<String> {
        val stamped = names.mapNotNull { n -> parse(n)?.let { n to it.second } }
        return stamped.sortedByDescending { it.second }.drop(keep).map { it.first }
    }

    internal fun parse(name: String): Pair<Int, Long>? {
        val m = Regex("""jarvis-v(\d+)-(\d+)\.db""").matchEntire(name) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toLong()
    }

    /**
     * Call once, before `Room.databaseBuilder(...).build()`. Returns the plan.
     * @throws UnsupportedDatabaseVersionException when the file cannot be opened without data loss.
     */
    fun preflight(context: Context, dbName: String = "jarvis.db"): Plan {
        val file = context.getDatabasePath(dbName)
        if (!file.exists() || file.length() == 0L) return Plan.Fresh
        // Unreadable header (corrupt / not a database): do not guess and do not touch the file.
        // With no destructive fallback Room itself refuses to open it and reports the real error.
        val version = readUserVersion(file) ?: return Plan.Fresh.also {
            Log.w(TAG, "jarvis.db user_version unreadable; leaving file untouched")
        }
        val plan = classify(version)
        when (plan) {
            is Plan.Unsupported -> throw UnsupportedDatabaseVersionException(
                "jarvis.db non è apribile in sicurezza: ${plan.reason}. Il file non è stato modificato.",
            )
            // Fail closed: if the verified pre-upgrade copy cannot be made, SnapshotFailedException
            // propagates, Room never opens the file and the original is left as found.
            is Plan.Upgrade -> PremigrationSnapshotter(
                File(file.parentFile, SNAPSHOT_DIR), AndroidDbSnapshotEngine,
            ).ensureSnapshot(file, plan.from)
            else -> Unit
        }
        return plan
    }

    private fun readUserVersion(file: File): Int? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    }.getOrNull()
}
