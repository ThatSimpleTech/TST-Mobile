package com.thatsimpletech.assist.audit

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.thatsimpletech.assist.core.audit.SqlExecutor

/** The app's [SqlExecutor]: the same SQL text and the same append-only triggers as the JVM tests run. */
class AndroidSqlExecutor(private val db: SQLiteDatabase) : SqlExecutor {

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(names.size)
                for (i in names.indices) row[names[i]] = valueAt(c, i)
                out += row
            }
        }
        return out
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun valueAt(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
        else -> c.getString(i)
    }
}
