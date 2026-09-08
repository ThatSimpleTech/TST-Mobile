package com.thatsimpletech.assist.core.audit

/**
 * The audit database schema (plan §1 "Append-only audit", §3 "same schema, local").
 *
 * The first two migration steps are TST Desk's, byte for byte, so a phone audit.db and a
 * desktop audit.db are the same shape and one query tool reads both. The third step is the
 * phone's extension: where the session ran, what planned it, the action rows the desktop has
 * no equivalent of, and the triggers that make append-only a property of the file. The
 * desktop keeps it a property of the codebase (a source scan for UPDATE/DELETE); the phone
 * cannot scan the app that opens the file, so the file refuses for itself.
 *
 * Timestamps are REAL seconds since the Unix epoch (UTC), as on the desktop.
 */
object AuditSchema {

    // Copied verbatim from TST-Desk core/tstd/audit.py, `_SCHEMA_V1`, lines 45-123.
    private const val V1 = """
CREATE TABLE sessions (
    session_id TEXT PRIMARY KEY,
    workspace_path TEXT NOT NULL,
    started_at REAL NOT NULL
);

CREATE TABLE turns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    turn_index INTEGER NOT NULL,
    tier TEXT NOT NULL,
    tokens INTEGER NOT NULL,
    cost REAL NOT NULL,
    duration REAL NOT NULL,
    ts REAL NOT NULL
);
CREATE INDEX idx_turns_session ON turns(session_id, turn_index);

CREATE TABLE tool_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    tool_call_id TEXT NOT NULL,
    name TEXT NOT NULL,
    -- Arguments as JSON, scrubbed through redact_secrets before insert.
    arguments TEXT NOT NULL,
    -- NULL means the static rule table found the call ambiguous (TD-701).
    decision_class TEXT CHECK(decision_class IN ('A', 'B', 'C')),
    -- 'refused' is a boundary refusal — a Class C event the model never ran.
    status TEXT NOT NULL CHECK(status IN ('success', 'error', 'refused')),
    -- sha256 hex of the result output; NULL when there is no result.
    result_hash TEXT,
    ts REAL NOT NULL
);
CREATE INDEX idx_tool_calls_session ON tool_calls(session_id, ts);

CREATE TABLE decisions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    decision_class TEXT NOT NULL CHECK(decision_class IN ('A', 'B', 'C')),
    what TEXT NOT NULL,
    why TEXT NOT NULL,
    commit_sha TEXT NULL,
    ts REAL NOT NULL
);
CREATE INDEX idx_decisions_session ON decisions(session_id, ts);

CREATE TABLE model_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    -- NULL for decision-classifier calls: they are not part of any turn.
    turn_id INTEGER REFERENCES turns(id),
    tier TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    cached_prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cost REAL NOT NULL,
    is_classifier INTEGER NOT NULL DEFAULT 0 CHECK(is_classifier IN (0, 1)),
    ts REAL NOT NULL
);
CREATE INDEX idx_model_calls_session ON model_calls(session_id);
-- Day rollups group on ts; this index keeps them off a full table scan.
CREATE INDEX idx_model_calls_ts ON model_calls(ts);

CREATE VIEW costs AS
SELECT
    session_id,
    turn_id,
    tier,
    date(datetime(ts, 'unixepoch', 'localtime')) AS day,
    SUM(prompt_tokens) AS prompt_tokens,
    SUM(cached_prompt_tokens) AS cached_prompt_tokens,
    SUM(completion_tokens) AS completion_tokens,
    SUM(CASE WHEN is_classifier = 0 THEN cost ELSE 0 END) AS cost,
    SUM(CASE WHEN is_classifier = 1 THEN cost ELSE 0 END) AS classifier_cost
FROM model_calls
GROUP BY session_id, turn_id, tier, day;
"""

    // Copied verbatim from TST-Desk core/tstd/audit.py, `_SCHEMA_V2`, lines 131-146.
    // Rebuilds `decisions` so commit_sha is nullable (desktop TD-1412). A fresh phone never
    // needs the rebuild, but the step stays so version numbers line up with the desktop.
    private const val V2 = """
CREATE TABLE decisions_v2 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    decision_class TEXT NOT NULL CHECK(decision_class IN ('A', 'B', 'C')),
    what TEXT NOT NULL,
    why TEXT NOT NULL,
    commit_sha TEXT NULL,
    ts REAL NOT NULL
);
INSERT INTO decisions_v2 (id, session_id, decision_class, what, why, commit_sha, ts)
    SELECT id, session_id, decision_class, what, why, commit_sha, ts FROM decisions;
DROP TABLE decisions;
ALTER TABLE decisions_v2 RENAME TO decisions;
CREATE INDEX idx_decisions_session ON decisions(session_id, ts);
"""

    /** Every table the triggers guard. schema_migrations is the one table that is not audit. */
    val AUDITED_TABLES: List<String> = listOf(
        "sessions", "turns", "tool_calls", "decisions", "model_calls", "actions", "approvals",
    )

    /** The one message the file gives back for any UPDATE or DELETE. */
    const val APPEND_ONLY_MESSAGE = "audit is append-only"

    /**
     * The BEFORE UPDATE and BEFORE DELETE triggers, `IF NOT EXISTS` so they can be re-asserted
     * on every open: SQLite drops a table's triggers with the table, so a future desktop step
     * that rebuilds a table the v2 way would silently leave the rebuilt one unguarded.
     */
    val APPEND_ONLY_TRIGGERS: String = AUDITED_TABLES.joinToString("\n") { t ->
        """
CREATE TRIGGER IF NOT EXISTS ${t}_no_update BEFORE UPDATE ON $t
BEGIN SELECT RAISE(ABORT, '$APPEND_ONLY_MESSAGE'); END;
CREATE TRIGGER IF NOT EXISTS ${t}_no_delete BEFORE DELETE ON $t
BEGIN SELECT RAISE(ABORT, '$APPEND_ONLY_MESSAGE'); END;
"""
    }

    // The phone extension. Sessions learn which device and which planner mode (plan §3);
    // actions are the grammar-level record the desktop's tool_calls stands in for; approvals
    // are the cards and the one task grant (plan §4). Then the triggers.
    private val V3: String = """
ALTER TABLE sessions ADD COLUMN device_id TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN planner_mode TEXT NOT NULL DEFAULT '';

CREATE TABLE actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    step INTEGER NOT NULL,
    verb TEXT NOT NULL,
    -- The rendered grammar line, through the redactor before insert.
    action TEXT NOT NULL,
    app TEXT NOT NULL,
    target_label TEXT,
    target_id TEXT,
    tier TEXT NOT NULL CHECK (tier IN ('silent', 'once', 'every', 'refused')),
    rule TEXT NOT NULL,
    gate TEXT NOT NULL,
    -- NULL when no card was involved (silent, or a refusal).
    approval TEXT CHECK (approval IN ('approved', 'denied', 'timeout', 'granted')),
    status TEXT NOT NULL CHECK (status IN ('success', 'error', 'refused', 'killed', 'skipped')),
    detail TEXT,
    -- The screen fingerprint the action was planned against (plan §7).
    fingerprint TEXT,
    ts REAL NOT NULL
);
CREATE INDEX idx_actions_session ON actions(session_id, step);

CREATE TABLE approvals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    -- NULL for a task grant: it covers the plan, not one action.
    action_id INTEGER,
    kind TEXT CHECK (kind IN ('task_grant', 'card')),
    outcome TEXT CHECK (outcome IN ('approved', 'denied', 'timeout')),
    requested_at REAL NOT NULL,
    answered_at REAL
);
CREATE INDEX idx_approvals_session ON approvals(session_id, requested_at);
""" + APPEND_ONLY_TRIGGERS

    /** Ordered steps; index + 1 is the version a step produces. Every schema change appends one. */
    val MIGRATIONS: List<String> = listOf(V1, V2, V3)

    /** The highest applied version, 0 for a database that has never been migrated. */
    fun version(executor: SqlExecutor): Int {
        executor.exec(
            "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at REAL NOT NULL)",
        )
        val row = executor.query("SELECT MAX(version) AS v FROM schema_migrations").first()
        return (row["v"] as Number?)?.toInt() ?: 0
    }

    /**
     * Mirrors the desktop's `AuditStore._migrate`: apply the unapplied steps in order, each in
     * one transaction with its version stamp, so the database is never left between versions.
     * Idempotent; a database already at the current version gets no writes.
     */
    fun migrate(executor: SqlExecutor, migrations: List<String> = MIGRATIONS, clock: () -> Double): Int {
        // journal_mode cannot change inside a transaction and returns a row; foreign_keys is
        // per connection. Both are best effort: an executor that refuses pragmas still migrates.
        runCatching { executor.query("PRAGMA journal_mode=WAL") }
        runCatching { executor.exec("PRAGMA foreign_keys=ON") }
        // REPLACE conflict resolution deletes rows without firing DELETE triggers unless this is on.
        runCatching { executor.exec("PRAGMA recursive_triggers=ON") }
        val current = version(executor)
        var applied = current
        for ((index, sql) in migrations.withIndex()) {
            val version = index + 1
            if (version <= current) continue
            executor.transaction {
                for (statement in SqlScript.split(sql)) executor.exec(statement)
                executor.exec("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)", listOf(version, clock()))
            }
            applied = version
        }
        return applied
    }

    /** Re-assert the triggers on a migrated database; see [APPEND_ONLY_TRIGGERS] for why. */
    fun ensureAppendOnly(executor: SqlExecutor) {
        runCatching { executor.exec("PRAGMA recursive_triggers=ON") }
        executor.transaction {
            for (statement in SqlScript.split(APPEND_ONLY_TRIGGERS)) executor.exec(statement)
        }
    }
}

/**
 * Splits a migration script into single statements. Neither Android's execSQL nor a bound
 * JDBC statement takes a script, and the desktop steps are scripts. A `;` inside a trigger
 * body (BEGIN ... END) or a CASE ... END must not split, and `--` comments are dropped.
 * Only for our own migration text: it knows nothing of BEGIN TRANSACTION.
 */
internal object SqlScript {
    fun split(script: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            if (c == '-' && i + 1 < n && script[i + 1] == '-') {
                while (i < n && script[i] != '\n') i++
                continue
            }
            if (c == '\'' || c == '"') {
                val quote = c
                current.append(c)
                i++
                while (i < n) {
                    val d = script[i]
                    current.append(d)
                    i++
                    if (d == quote) {
                        if (i < n && script[i] == quote) {
                            current.append(quote)
                            i++
                        } else {
                            break
                        }
                    }
                }
                continue
            }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < n && (script[i].isLetterOrDigit() || script[i] == '_')) i++
                val word = script.substring(start, i)
                when (word.uppercase()) {
                    "BEGIN", "CASE" -> depth++
                    "END" -> depth--
                }
                current.append(word)
                continue
            }
            if (c == ';' && depth == 0) {
                val statement = current.toString().trim()
                if (statement.isNotEmpty()) out += statement
                current.clear()
                i++
                continue
            }
            current.append(c)
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }
}
