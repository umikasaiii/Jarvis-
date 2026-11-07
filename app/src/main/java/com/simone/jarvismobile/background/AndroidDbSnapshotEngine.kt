package com.simone.jarvismobile.background

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Real-SQLite [DbSnapshotEngine] (framework SQLite; minSdk 31 ships SQLite ≥ 3.32, so VACUUM INTO exists). */
object AndroidDbSnapshotEngine : DbSnapshotEngine {

    override fun snapshotInto(source: File, target: File) {
        require(!target.exists()) { "target exists" }
        try {
            // VACUUM INTO reads through the WAL and writes a fresh, self-contained file.
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'")
            }
        } catch (t: Throwable) {
            target.delete()
            // Fallback: fold the WAL completely, REQUIRE it to be complete, then copy the file.
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    check(c.moveToFirst() && c.getInt(0) == 0) { "WAL checkpoint busy/incomplete" }
                }
            }
            val wal = File(source.path + "-wal")
            check(!wal.exists() || wal.length() == 0L) { "WAL not empty after checkpoint" }
            source.copyTo(target, overwrite = false)
        }
    }

    override fun facts(db: File): DbFacts =
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val integrity = d.rawQuery("PRAGMA quick_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            val tables = mutableListOf<Pair<String, String>>()
            d.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0) to c.getString(1)
            }
            val virtual = tables.filter { it.second.contains("VIRTUAL TABLE", ignoreCase = true) }.map { it.first }
            val counts = tables.map { it.first }
                .filter { n -> !n.startsWith("sqlite_") && n != "android_metadata" && virtual.none { v -> n.startsWith("${v}_") } }
                .sorted()
                .associateWith { n -> d.rawQuery("SELECT COUNT(*) FROM `$n`", null).use { c -> c.moveToFirst(); c.getLong(0) } }
            DbFacts(d.version, integrity, counts)
        }
}
