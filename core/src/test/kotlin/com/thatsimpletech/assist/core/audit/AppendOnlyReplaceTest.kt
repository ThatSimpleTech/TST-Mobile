package com.thatsimpletech.assist.core.audit

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** REPLACE deletes without firing DELETE triggers unless recursive_triggers is on. It is on. */
class AppendOnlyReplaceTest {
    @Test
    fun insertOrReplaceCannotRewriteASession() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            val exec = JdbcSqlExecutor(conn)
            val store = AuditStore(exec, { 1.0 }, { it })
            store.migrate()
            store.startSession("s1", "dev", "cloud-key", "original goal")
            val e = assertFailsWith<Exception> {
                exec.exec("INSERT OR REPLACE INTO sessions (session_id, workspace_path, started_at, device_id, planner_mode) VALUES (?, ?, ?, ?, ?)", listOf("s1", "rewritten", 2.0, "dev", "x"))
            }
            assertTrue("append-only" in (e.message ?: ""), e.message)
            assertEquals("original goal", store.session("s1")?.workspacePath)
        }
    }
}
