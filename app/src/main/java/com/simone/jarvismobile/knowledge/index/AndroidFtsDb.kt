package com.simone.jarvismobile.knowledge.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.simone.jarvismobile.core.knowledge.fts.FtsDb
import com.simone.jarvismobile.core.knowledge.fts.FtsRow

/**
 * `knowledge_fts.db` — the persistent knowledge index, in its OWN database file: it is a rebuildable cache, so it never shares
 * (or can corrupt, or be restored over) `jarvis.db`, and no migration of user data is ever involved. The schema is created and
 * versioned by [com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex] itself (FTS4 + fts4aux, both in Android's SQLite).
 */
class AndroidFtsDb(context: Context) : SQLiteOpenHelper(context.applicationContext, FILE, null, 1), FtsDb {
    override fun onCreate(db: SQLiteDatabase) = Unit
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun exec(sql: String, vararg args: Any?) {
        // execSQL binds by TYPE (Long/Int/String), which FTS docid inserts need
        writableDatabase.execSQL(sql, arrayOf(*args))
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T> {
        // rawQuery binds only Strings; numbers are inlined (they are ids/limits built by our own code, never user text)
        val text = ArrayList<String>()
        val sb = StringBuilder()
        var next = 0
        for (c in sql) {
            if (c == '?') {
                val a = args[next++]
                if (a is Number) sb.append(a.toLong()) else { sb.append('?'); text += a?.toString() ?: "" }
            } else sb.append(c)
        }
        readableDatabase.rawQuery(sb.toString(), text.toTypedArray()).use { c ->
            val out = ArrayList<T>()
            val row = object : FtsRow {
                override fun string(i: Int): String = if (c.isNull(i)) "" else c.getString(i)
                override fun long(i: Int): Long = if (c.isNull(i)) 0L else c.getLong(i)
            }
            while (c.moveToNext()) out += map(row)
            return out
        }
    }

    override fun <T> transaction(body: () -> T): T {
        val db = writableDatabase
        if (db.inTransaction()) return body()
        db.beginTransaction()
        try {
            val r = body()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    companion object { const val FILE = "knowledge_fts.db" }
}
