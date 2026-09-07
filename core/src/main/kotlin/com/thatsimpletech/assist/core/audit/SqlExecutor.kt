package com.thatsimpletech.assist.core.audit

/**
 * The thin seam over SQLite. The app implements it with android.database.sqlite; the JVM
 * tests implement it with sqlite-jdbc. The audit store's SQL and its INSERT-only triggers
 * are the same text on both, which is the point.
 */
interface SqlExecutor {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>
    fun <T> transaction(block: () -> T): T
}
