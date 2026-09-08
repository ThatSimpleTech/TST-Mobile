package com.thatsimpletech.assist.core.audit

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * The JVM test implementation of [SqlExecutor] over sqlite-jdbc. The app's implementation
 * over android.database.sqlite runs the same SQL text; this one exists so every schema
 * promise is provable without a device.
 */
class JdbcSqlExecutor(private val conn: Connection) : SqlExecutor, AutoCloseable {
    private var depth = 0

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val meta = rs.metaData
                val labels = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>()
                    for ((i, label) in labels.withIndex()) row[label] = rs.getObject(i + 1)
                    out += row
                }
                out
            }
        }

    /** Outermost call owns the commit/rollback; nested calls just run inside it. */
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) {
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }
        conn.autoCommit = false
        depth = 1
        try {
            val result = block()
            conn.commit()
            return result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            depth = 0
            conn.autoCommit = true
        }
    }

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        for ((i, a) in args.withIndex()) st.setObject(i + 1, a)
    }

    companion object {
        fun memory() = JdbcSqlExecutor(DriverManager.getConnection("jdbc:sqlite::memory:"))
        fun file(path: String) = JdbcSqlExecutor(DriverManager.getConnection("jdbc:sqlite:$path"))
    }
}
