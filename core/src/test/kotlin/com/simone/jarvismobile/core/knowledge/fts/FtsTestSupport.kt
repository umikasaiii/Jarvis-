package com.simone.jarvismobile.core.knowledge.fts

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** JDBC implementation of [FtsDb] for JVM tests: the SAME [KnowledgeIndex] code runs on real SQLite (FTS4 + fts4aux). */
class JdbcFtsDb(val file: File, private val failOnExecNumber: Int = -1) : FtsDb, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also { it.autoCommit = true }
    private var depth = 0
    var execs = 0

    private fun bind(ps: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is Long -> ps.setLong(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        execs++
        if (failOnExecNumber in 1..execs && execs == failOnExecNumber) throw IllegalStateException("simulated crash at exec #$execs")
        conn.prepareStatement(sql).use { ps -> bind(ps, args); ps.execute() }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                val row = object : FtsRow {
                    override fun string(i: Int): String = rs.getString(i + 1) ?: ""
                    override fun long(i: Int): Long = rs.getLong(i + 1)
                }
                while (rs.next()) out += map(row)
                out
            }
        }

    override fun <T> transaction(body: () -> T): T {
        if (depth > 0) return body()
        depth++
        conn.autoCommit = false
        try {
            val r = body()
            conn.commit()
            return r
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
            depth--
        }
    }

    override fun close() { conn.close() }
}
