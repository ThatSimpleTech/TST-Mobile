package com.thatsimpletech.assist.core.audit

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditSchemaTest {
    private val clock = { 1_726_000_000.5 }

    private fun fresh(): JdbcSqlExecutor = JdbcSqlExecutor.memory().also { AuditSchema.migrate(it, clock = clock) }

    private fun columns(db: SqlExecutor, table: String): List<String> =
        db.query("PRAGMA table_info($table)").map { it.getValue("name") as String }

    private fun names(db: SqlExecutor, type: String): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%'", listOf(type))
            .map { it.getValue("name") as String }.toSet()

    private fun count(db: SqlExecutor, table: String): Long =
        (db.query("SELECT COUNT(*) AS n FROM $table").first().getValue("n") as Number).toLong()

    // ── Migrations ──────────────────────────────────────────────────────────

    @Test
    fun migrationsApplyOneThroughThreeOnAFreshDatabase() {
        fresh().use { db ->
            assertEquals(3, AuditSchema.version(db))
            val versions = db.query("SELECT version FROM schema_migrations ORDER BY version").map { (it.getValue("version") as Number).toInt() }
            assertEquals(listOf(1, 2, 3), versions)
        }
    }

    @Test
    fun rerunningMigrateIsIdempotentAndKeepsData() {
        fresh().use { db ->
            db.exec("INSERT INTO sessions (session_id, workspace_path, started_at) VALUES ('s1', 'goal', 1.0)")
            val stamps = db.query("SELECT applied_at FROM schema_migrations ORDER BY version")
            AuditSchema.migrate(db, clock = { 9_999_999_999.0 })
            assertEquals(3L, count(db, "schema_migrations"))
            assertEquals(stamps, db.query("SELECT applied_at FROM schema_migrations ORDER BY version"))
            assertEquals(1L, count(db, "sessions"))
        }
    }

    @Test
    fun migrationStampsAreEpochSeconds() {
        fresh().use { db ->
            val applied = db.query("SELECT applied_at FROM schema_migrations WHERE version = 3").first().getValue("applied_at")
            assertEquals(1_726_000_000.5, applied)
        }
    }

    @Test
    fun migrationAppliesOnlyMissingSteps() {
        val step1 = "CREATE TABLE t1 (id INTEGER PRIMARY KEY);"
        val step2 = "CREATE TABLE t2 (id INTEGER PRIMARY KEY);"
        JdbcSqlExecutor.memory().use { db ->
            assertEquals(1, AuditSchema.migrate(db, listOf(step1), clock))
            assertTrue("t2" !in names(db, "table"))
            assertEquals(2, AuditSchema.migrate(db, listOf(step1, step2), clock))
            assertTrue("t2" in names(db, "table"))
            assertEquals(2, AuditSchema.version(db))
        }
    }

    @Test
    fun failedMigrationRollsBackTheWholeStep() {
        val good = "CREATE TABLE t1 (id INTEGER PRIMARY KEY);"
        val bad = "INSERT INTO table_that_does_not_exist VALUES (1);"
        JdbcSqlExecutor.memory().use { db ->
            assertFailsWith<Exception> { AuditSchema.migrate(db, listOf("$good\n$bad"), clock) }
            assertTrue("t1" !in names(db, "table"))
            assertEquals(0L, count(db, "schema_migrations"))
        }
    }

    @Test
    fun legacyDesktopV1DatabaseIsRebuiltByV2AndGuardedByV3() {
        // A desktop file that ran the original v1: every v1 object, but decisions.commit_sha
        // TEXT NOT NULL, and the version stamp says 1.
        JdbcSqlExecutor.memory().use { db ->
            val v1WithoutDecisions = SqlScript.split(AuditSchema.MIGRATIONS[0]).filter { "decisions" !in it }
            assertEquals(9, v1WithoutDecisions.size)
            for (sql in v1WithoutDecisions) db.exec(sql)
            for (sql in listOf(
                "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at REAL NOT NULL)",
                "INSERT INTO schema_migrations (version, applied_at) VALUES (1, 0)",
                "INSERT INTO sessions VALUES ('s1', '/tmp/ws', 1.0)",
                "CREATE TABLE decisions (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(session_id)," +
                    " decision_class TEXT NOT NULL CHECK(decision_class IN ('A', 'B', 'C')), what TEXT NOT NULL, why TEXT NOT NULL," +
                    " commit_sha TEXT NOT NULL, ts REAL NOT NULL)",
                "INSERT INTO decisions (session_id, decision_class, what, why, commit_sha, ts) VALUES ('s1', 'A', 'edited app.py', 'writable_paths', 'abc123', 2.0)",
                "CREATE INDEX idx_decisions_session ON decisions(session_id, ts)",
            )) db.exec(sql)

            assertEquals(3, AuditSchema.migrate(db, clock = clock))
            val commitCol = db.query("PRAGMA table_info(decisions)").first { it["name"] == "commit_sha" }
            assertEquals(0, (commitCol.getValue("notnull") as Number).toInt(), "commit_sha must be nullable after v2")
            val kept = db.query("SELECT decision_class, commit_sha FROM decisions").single()
            assertEquals("A", kept["decision_class"])
            assertEquals("abc123", kept["commit_sha"])
            db.exec("INSERT INTO decisions (session_id, decision_class, what, why, commit_sha, ts) VALUES ('s1', 'B', 'read', 'judgment', NULL, 3.0)")
            assertEquals(2L, count(db, "decisions"))
            // The rebuilt table carries the phone's triggers, and the desktop rows keep their device_id default.
            val e = assertFailsWith<Exception> { db.exec("DELETE FROM decisions WHERE decision_class = 'A'") }
            assertTrue(AuditSchema.APPEND_ONLY_MESSAGE in e.message.orEmpty(), e.message)
            assertEquals("", db.query("SELECT device_id FROM sessions").single()["device_id"])
        }
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test
    fun desktopTablesExistWithTheDesktopColumns() {
        fresh().use { db ->
            assertEquals(
                listOf("session_id", "workspace_path", "started_at", "device_id", "planner_mode"),
                columns(db, "sessions"),
            )
            assertEquals(
                listOf("id", "session_id", "turn_index", "tier", "tokens", "cost", "duration", "ts"),
                columns(db, "turns"),
            )
            assertEquals(
                listOf("id", "session_id", "tool_call_id", "name", "arguments", "decision_class", "status", "result_hash", "ts"),
                columns(db, "tool_calls"),
            )
            assertEquals(
                listOf("id", "session_id", "decision_class", "what", "why", "commit_sha", "ts"),
                columns(db, "decisions"),
            )
            assertEquals(
                listOf("id", "session_id", "turn_id", "tier", "model", "prompt_tokens", "cached_prompt_tokens", "completion_tokens", "cost", "is_classifier", "ts"),
                columns(db, "model_calls"),
            )
            assertEquals(setOf("costs"), names(db, "view"))
            assertTrue(
                setOf("idx_turns_session", "idx_tool_calls_session", "idx_decisions_session", "idx_model_calls_session", "idx_model_calls_ts")
                    .all { it in names(db, "index") },
            )
        }
    }

    @Test
    fun phoneTablesExistWithTheirColumns() {
        fresh().use { db ->
            assertEquals(
                listOf("id", "session_id", "step", "verb", "action", "app", "target_label", "target_id", "tier", "rule", "gate", "approval", "status", "detail", "fingerprint", "ts"),
                columns(db, "actions"),
            )
            assertEquals(
                listOf("id", "session_id", "action_id", "kind", "outcome", "requested_at", "answered_at"),
                columns(db, "approvals"),
            )
            assertTrue(setOf("idx_actions_session", "idx_approvals_session").all { it in names(db, "index") })
            assertEquals(
                setOf("schema_migrations", "sessions", "turns", "tool_calls", "decisions", "model_calls", "actions", "approvals"),
                names(db, "table") - "sqlite_sequence",
            )
        }
    }

    @Test
    fun actionsTierAndStatusAreCheckedByTheFile() {
        fresh().use { db ->
            db.exec("INSERT INTO sessions (session_id, workspace_path, started_at) VALUES ('s1', 'g', 1.0)")
            val insert = "INSERT INTO actions (session_id, step, verb, action, app, tier, rule, gate, status, ts) VALUES ('s1', 1, 'tap', 'tap 1', 'a', ?, 'r', 'proceed', ?, 1.0)"
            assertFailsWith<Exception> { db.exec(insert, listOf("tier2", "success")) }
            assertFailsWith<Exception> { db.exec(insert, listOf("every", "done")) }
            db.exec(insert, listOf("every", "killed"))
            assertEquals(1L, count(db, "actions"))
        }
    }

    // ── Append-only ─────────────────────────────────────────────────────────

    @Test
    fun updateAndDeleteAreRefusedOnEveryAuditedTableWhileInsertStillWorks() {
        fresh().use { db ->
            db.exec("INSERT INTO sessions (session_id, workspace_path, started_at, device_id, planner_mode) VALUES ('s1', 'g', 1.0, 'd', 'home')")
            db.exec("INSERT INTO turns (session_id, turn_index, tier, tokens, cost, duration, ts) VALUES ('s1', 1, 'brain', 1, 0.0, 0.1, 1.0)")
            db.exec("INSERT INTO tool_calls (session_id, tool_call_id, name, arguments, status, ts) VALUES ('s1', 't', 'n', '{}', 'success', 1.0)")
            db.exec("INSERT INTO decisions (session_id, decision_class, what, why, ts) VALUES ('s1', 'A', 'w', 'y', 1.0)")
            db.exec("INSERT INTO model_calls (session_id, tier, model, prompt_tokens, cached_prompt_tokens, completion_tokens, cost, ts) VALUES ('s1', 'brain', 'm', 1, 0, 1, 0.0, 1.0)")
            db.exec("INSERT INTO actions (session_id, step, verb, action, app, tier, rule, gate, status, ts) VALUES ('s1', 1, 'tap', 'tap 1', 'a', 'silent', 'r', 'proceed', 'success', 1.0)")
            db.exec("INSERT INTO approvals (session_id, kind, outcome, requested_at) VALUES ('s1', 'card', 'approved', 1.0)")

            for (table in AuditSchema.AUDITED_TABLES) {
                assertEquals(1L, count(db, table), table)
                val update = assertFailsWith<Exception>(table) { db.exec("UPDATE $table SET session_id = 's2'") }
                assertTrue("audit is append-only" in update.message.orEmpty(), "$table update: ${update.message}")
                val delete = assertFailsWith<Exception>(table) { db.exec("DELETE FROM $table") }
                assertTrue("audit is append-only" in delete.message.orEmpty(), "$table delete: ${delete.message}")
                assertEquals(1L, count(db, table), "$table row survived")
            }
            // The one table that is not audit keeps its ordinary rights.
            db.exec("UPDATE schema_migrations SET applied_at = 2.0 WHERE version = 3")
            assertEquals(2.0, db.query("SELECT applied_at FROM schema_migrations WHERE version = 3").single()["applied_at"])
        }
    }

    @Test
    fun everyAuditedTableHasBothTriggersAndSchemaMigrationsHasNone() {
        fresh().use { db ->
            val triggers = db.query("SELECT name, tbl_name FROM sqlite_master WHERE type = 'trigger'")
                .groupBy({ it.getValue("tbl_name") as String }, { it.getValue("name") as String })
            for (table in AuditSchema.AUDITED_TABLES) {
                assertEquals(setOf("${table}_no_update", "${table}_no_delete"), triggers[table]?.toSet(), table)
            }
            assertNull(triggers["schema_migrations"])
        }
    }

    @Test
    fun aDroppedTriggerComesBackOnTheNextMigrate() {
        fresh().use { db ->
            db.exec("INSERT INTO sessions (session_id, workspace_path, started_at) VALUES ('s1', 'g', 1.0)")
            db.exec("DROP TRIGGER sessions_no_delete")
            AuditSchema.migrate(db, clock = clock)
            AuditSchema.ensureAppendOnly(db)
            val e = assertFailsWith<Exception> { db.exec("DELETE FROM sessions") }
            assertTrue("audit is append-only" in e.message.orEmpty())
        }
    }

    // ── Connection settings ─────────────────────────────────────────────────

    @Test
    fun fileDatabaseRunsInWalWithForeignKeysOn() {
        val dir = Files.createTempDirectory("audit")
        JdbcSqlExecutor.file(dir.resolve("audit.db").toString()).use { db ->
            AuditSchema.migrate(db, clock = clock)
            assertEquals("wal", (db.query("PRAGMA journal_mode").single().values.single() as String).lowercase())
            assertEquals(1, (db.query("PRAGMA foreign_keys").single().values.single() as Number).toInt())
            assertFailsWith<Exception> {
                db.exec("INSERT INTO turns (session_id, turn_index, tier, tokens, cost, duration, ts) VALUES ('nope', 0, 'brain', 1, 0.0, 0.1, 1.0)")
            }
        }
    }

    // ── Script splitting ────────────────────────────────────────────────────

    @Test
    fun splitKeepsTriggerBodiesAndCaseExpressionsWhole() {
        val script = """
            -- a comment; with a semicolon
            CREATE TABLE t (x TEXT);
            CREATE TRIGGER t_no_update BEFORE UPDATE ON t
            BEGIN SELECT RAISE(ABORT, 'no; never'); END;
            CREATE VIEW v AS SELECT SUM(CASE WHEN x = 'a;b' THEN 1 ELSE 0 END) AS n FROM t;
            INSERT INTO t VALUES ('semi;colon')
        """.trimIndent()
        val statements = SqlScript.split(script)
        assertEquals(4, statements.size)
        assertEquals("CREATE TABLE t (x TEXT)", statements[0])
        assertTrue(statements[1].startsWith("CREATE TRIGGER") && statements[1].endsWith("END"))
        assertTrue(statements[2].startsWith("CREATE VIEW") && "ELSE 0 END" in statements[2])
        assertEquals("INSERT INTO t VALUES ('semi;colon')", statements[3])
        assertTrue(statements.none { "comment" in it })
    }

    @Test
    fun splitOfTheRealMigrationsCountsTheDesktopStatements() {
        // v1: 5 tables, 5 indexes, 1 view. v2: create, insert, drop, rename, index. v3: 2 alters,
        // 2 tables, 2 indexes, then two triggers for each of the seven audited tables.
        val counts = AuditSchema.MIGRATIONS.map { SqlScript.split(it).size }
        assertEquals(listOf(11, 5, 6 + 14), counts)
    }
}
