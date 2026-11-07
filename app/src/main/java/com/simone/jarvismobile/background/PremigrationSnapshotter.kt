package com.simone.jarvismobile.background

import java.io.File

/** What a snapshot must prove about a database file before it may count as valid. */
data class DbFacts(val userVersion: Int, val integrityOk: Boolean, val rowCounts: Map<String, Long>)

/** SQLite access, abstracted so the orchestration below is unit-testable on the JVM. */
interface DbSnapshotEngine {
    /** Writes a consistent copy of [source] (WAL included) to [target]. Must throw on any failure. */
    fun snapshotInto(source: File, target: File)

    /** Opens [db] read-only and reports its user_version, integrity and per-table row counts. */
    fun facts(db: File): DbFacts
}

/** The pre-upgrade copy could not be made AND verified: the upgrade must not proceed. */
class SnapshotFailedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * STEP 2 — makes a *verified* copy of `jarvis.db` before Room runs real migrations.
 *
 * Policy: **fail closed.** If the copy cannot be written and verified, [ensureSnapshot] throws
 * [SnapshotFailedException]; `DatabaseOpenGuard` lets it propagate, Room never opens the file, the
 * original database is left exactly as found (nothing is written to it except an optional WAL
 * checkpoint), and the message says what to do. Rationale: an upgrade is rare, the data is
 * irreplaceable, and an app that explains "free some space" is better than one that migrates
 * without a safety net.
 *
 * Properties:
 *  1. **Consistent / WAL-complete** — the engine uses `VACUUM INTO` (reads through the WAL) or, as
 *     fallback, a full `wal_checkpoint(TRUNCATE)` that must report not-busy before a file copy.
 *  2. **Verified before being valid** — written as `*.part`, then `quick_check == ok`,
 *     `user_version == from` and per-table row counts equal to the source. Only then is it renamed
 *     to its final `jarvis-v<N>-<ts>.db` name. A `.part` is never a snapshot.
 *  3. **Never replaces a good copy with a bad one** — final names are unique (timestamp), an
 *     existing valid snapshot is never overwritten, and old snapshots are pruned only after the
 *     new one is verified and in place.
 *  4. **Disk full / permissions / interruption** — free space is checked first
 *     (`size * 1.2 + 8 MiB`); any IO error deletes the `.part` and fails closed; leftover `.part`
 *     files from a killed process are removed on the next attempt.
 *
 * This is NOT a second backup system: it is a transient safety copy for one schema upgrade; the
 * general encrypted backup (`BackupRepository`) is untouched and does not read these files.
 */
class PremigrationSnapshotter(
    private val dir: File,
    private val engine: DbSnapshotEngine,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val now: () -> Long = System::currentTimeMillis,
    private val keep: Int = DatabaseOpenGuard.KEEP,
) {
    fun ensureSnapshot(db: File, from: Int): File {
        try {
            dir.mkdirs()
            if (!dir.isDirectory) throw SnapshotFailedException("la cartella degli snapshot non è utilizzabile (${dir.name})")
            dir.listFiles().orEmpty().filter { it.name.endsWith(PART) }.forEach { it.delete() }

            val sourceFacts = engine.facts(db)
            if (!sourceFacts.integrityOk) {
                throw SnapshotFailedException("il database originale non supera il controllo di integrità: non è stato modificato né migrato")
            }
            if (sourceFacts.userVersion != from) {
                throw SnapshotFailedException("versione del database cambiata durante il controllo (${sourceFacts.userVersion} ≠ $from)")
            }

            existingValid(db, from, sourceFacts)?.let { return it }

            val need = (db.length() * 12 / 10) + 8L * 1024 * 1024
            val have = usableSpace(dir)
            if (have < need) {
                throw SnapshotFailedException(
                    "spazio insufficiente per la copia di sicurezza (servono ~${need / 1024 / 1024} MB, disponibili ${have / 1024 / 1024} MB): " +
                        "libera spazio e riavvia; il database non è stato toccato",
                )
            }

            val ts = now()
            val part = File(dir, DatabaseOpenGuard.snapshotName(from, ts) + PART)
            val finalFile = File(dir, DatabaseOpenGuard.snapshotName(from, ts))
            if (finalFile.exists()) throw SnapshotFailedException("lo snapshot ${finalFile.name} esiste già: non lo sovrascrivo")
            try {
                engine.snapshotInto(db, part)
                verify(part, from, sourceFacts)
                if (!part.renameTo(finalFile)) throw SnapshotFailedException("impossibile finalizzare lo snapshot")
            } catch (t: Throwable) {
                part.delete()
                throw t
            }
            DatabaseOpenGuard.toPrune(dir.list().orEmpty().filter { !it.endsWith(PART) }, keep)
                .forEach { File(dir, it).delete() }
            return finalFile
        } catch (e: SnapshotFailedException) {
            throw e
        } catch (t: Throwable) {
            throw SnapshotFailedException(
                "copia di sicurezza pre-aggiornamento non riuscita (${t::class.simpleName}): il database originale è intatto; " +
                    "libera spazio/permessi e riavvia l'app",
                t,
            )
        }
    }

    /** A snapshot of this version, newer than the db file, that still verifies against the source. */
    private fun existingValid(db: File, from: Int, sourceFacts: DbFacts): File? =
        dir.listFiles().orEmpty()
            .filter { f -> DatabaseOpenGuard.parse(f.name)?.let { it.first == from && it.second >= db.lastModified() } == true }
            .sortedByDescending { DatabaseOpenGuard.parse(it.name)!!.second }
            .firstOrNull { runCatching { verify(it, from, sourceFacts); true }.getOrDefault(false) }

    private fun verify(snapshot: File, from: Int, source: DbFacts) {
        val f = engine.facts(snapshot)
        if (!f.integrityOk) throw SnapshotFailedException("lo snapshot non supera il controllo di integrità")
        if (f.userVersion != from) throw SnapshotFailedException("lo snapshot ha versione ${f.userVersion}, attesa $from")
        if (f.rowCounts != source.rowCounts) {
            val diff = (source.rowCounts.keys + f.rowCounts.keys).filter { source.rowCounts[it] != f.rowCounts[it] }
            throw SnapshotFailedException("lo snapshot non corrisponde all'originale (tabelle diverse: ${diff.joinToString()})")
        }
    }

    companion object { const val PART = ".part" }
}
