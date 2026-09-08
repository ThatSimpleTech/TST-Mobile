package com.thatsimpletech.assist.core.audit

import com.thatsimpletech.assist.core.policy.Gate
import com.thatsimpletech.assist.core.policy.Tier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The words the `actions.status` CHECK accepts. killed and skipped are phone-only outcomes (plan §4 kill switch). */
enum class ActionStatus(val word: String) {
    SUCCESS("success"), ERROR("error"), REFUSED("refused"), KILLED("killed"), SKIPPED("skipped"),
}

/** How an action's gate was answered. `granted` means the task grant already covered it. */
enum class Approval(val word: String) {
    APPROVED("approved"), DENIED("denied"), TIMEOUT("timeout"), GRANTED("granted"),
}

enum class ApprovalKind(val word: String) { TASK_GRANT("task_grant"), CARD("card") }

enum class ApprovalOutcome(val word: String) { APPROVED("approved"), DENIED("denied"), TIMEOUT("timeout") }

/** The desktop's `ToolCallStatus`: refused is a boundary refusal the model never ran. */
enum class ToolCallStatus(val word: String) { SUCCESS("success"), ERROR("error"), REFUSED("refused") }

data class SessionRow(
    val sessionId: String,
    val workspacePath: String,
    val startedAt: Double,
    val deviceId: String,
    val plannerMode: String,
)

data class ActionRow(
    val id: Long,
    val sessionId: String,
    val step: Long,
    val verb: String,
    val action: String,
    val app: String,
    val targetLabel: String?,
    val targetId: String?,
    val tier: String,
    val rule: String,
    val gate: String,
    val approval: String?,
    val status: String,
    val detail: String?,
    val fingerprint: String?,
    val ts: Double,
)

/**
 * INSERT-only handle over the audit database, the phone's port of TST Desk's `AuditStore`.
 * There is no update or delete method and the file's triggers refuse both anyway.
 *
 * [clock] returns epoch seconds; [redact] is the one shared redactor (plan §1 "key never
 * touches disk"): every free-text column goes through it before insert, the same rule the
 * desktop applies to tool arguments and decision text. Not thread-safe by itself; the caller
 * serializes, as the desktop writer does.
 */
class AuditStore(
    private val executor: SqlExecutor,
    private val clock: () -> Double,
    private val redact: (String) -> String,
) {
    /** Bring the file to the current schema and make sure the triggers are on it. */
    fun migrate(): Int {
        val version = AuditSchema.migrate(executor, clock = clock)
        AuditSchema.ensureAppendOnly(executor)
        return version
    }

    val schemaVersion: Int get() = AuditSchema.version(executor)

    // ── Appends ───────────────────────────────────────────────────────────────
    // One row per completed fact, recorded after it resolves, never patched.

    /** Record a session's start. The goal text takes the desktop's workspace_path slot. */
    fun startSession(sessionId: String, deviceId: String, plannerMode: String, workspacePath: String, startedAt: Double = clock()) {
        executor.exec(
            "INSERT INTO sessions (session_id, workspace_path, started_at, device_id, planner_mode) VALUES (?, ?, ?, ?, ?)",
            listOf(sessionId, redact(workspacePath), startedAt, deviceId, plannerMode),
        )
    }

    /** Record a completed turn; the id links model_calls to it. Desktop `append_turn`. */
    fun recordTurn(sessionId: String, turnIndex: Int, tier: String, tokens: Int, cost: Double, duration: Double, ts: Double = clock()): Long =
        insert(
            "INSERT INTO turns (session_id, turn_index, tier, tokens, cost, duration, ts) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf(sessionId, turnIndex, tier, tokens, cost, duration, ts),
        )

    /**
     * Desktop `append_tool_call`. Every string inside [arguments] is redacted in place so the
     * stored JSON stays valid JSON; the result is kept only as a hash.
     */
    fun recordToolCall(
        sessionId: String,
        toolCallId: String,
        name: String,
        arguments: JsonObject,
        decisionClass: String?,
        status: ToolCallStatus,
        resultOutput: String? = null,
        ts: Double = clock(),
    ): Long = insert(
        "INSERT INTO tool_calls (session_id, tool_call_id, name, arguments, decision_class, status, result_hash, ts)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(sessionId, toolCallId, name, json.encodeToString(JsonElement.serializer(), scrub(arguments)), decisionClass, status.word, resultOutput?.let(::sha256), ts),
    )

    /**
     * One row per action the loop judged, whether it ran or not. [action] is the rendered
     * grammar line and [targetLabel] is screen text: both are data the model or the screen
     * chose, so both go through the redactor, as does [detail].
     */
    fun recordAction(
        sessionId: String,
        step: Int,
        verb: String,
        action: String,
        app: String,
        targetLabel: String?,
        targetId: String?,
        tier: Tier,
        rule: String,
        gate: Gate,
        approval: Approval?,
        status: ActionStatus,
        detail: String? = null,
        fingerprint: String? = null,
        ts: Double = clock(),
    ): Long = insert(
        "INSERT INTO actions (session_id, step, verb, action, app, target_label, target_id, tier, rule, gate, approval, status, detail, fingerprint, ts)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            sessionId, step, verb, redact(action), app, targetLabel?.let(redact), targetId, tier.word(), rule,
            gate.name.lowercase(), approval?.word, status.word, detail?.let(redact), fingerprint, ts,
        ),
    )

    /** A card or the task grant and how it ended. [actionId] is null for the task grant. */
    fun recordApproval(
        sessionId: String,
        actionId: Long?,
        kind: ApprovalKind,
        outcome: ApprovalOutcome,
        requestedAt: Double,
        answeredAt: Double? = clock(),
    ): Long = insert(
        "INSERT INTO approvals (session_id, action_id, kind, outcome, requested_at, answered_at) VALUES (?, ?, ?, ?, ?, ?)",
        listOf(sessionId, actionId, kind.word, outcome.word, requestedAt, answeredAt),
    )

    /**
     * Desktop `append_model_call`. A provider that reported no cached figure stores 0, the
     * desktop's `billable_cached_tokens` rule: the column is a sum of reuse someone claimed.
     */
    fun recordModelCall(
        sessionId: String,
        turnId: Long?,
        tier: String,
        model: String,
        promptTokens: Int,
        cachedPromptTokens: Int?,
        completionTokens: Int,
        cost: Double,
        isClassifier: Boolean = false,
        ts: Double = clock(),
    ): Long = insert(
        "INSERT INTO model_calls (session_id, turn_id, tier, model, prompt_tokens, cached_prompt_tokens, completion_tokens, cost, is_classifier, ts)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(sessionId, turnId, tier, model, promptTokens, cachedPromptTokens ?: 0, completionTokens, cost, if (isClassifier) 1 else 0, ts),
    )

    // ── Queries ───────────────────────────────────────────────────────────────

    fun session(sessionId: String): SessionRow? =
        executor.query("SELECT session_id, workspace_path, started_at, device_id, planner_mode FROM sessions WHERE session_id = ?", listOf(sessionId))
            .firstOrNull()
            ?.let { r ->
                SessionRow(r.string("session_id"), r.string("workspace_path"), r.double("started_at"), r.string("device_id"), r.string("planner_mode"))
            }

    /**
     * Non-classifier model-call spend whose local calendar day matches [nowEpochSeconds]
     * (TM-025). Classifier rows stay off the day meter, same as [com.thatsimpletech.assist.core.meter.CostTracker.sessionCost].
     */
    fun daySpend(nowEpochSeconds: Double): Double {
        val row = executor.query(
            "SELECT COALESCE(SUM(cost), 0) AS spend FROM model_calls" +
                " WHERE is_classifier = 0" +
                " AND date(datetime(ts, 'unixepoch', 'localtime')) = date(datetime(?, 'unixepoch', 'localtime'))",
            listOf(nowEpochSeconds),
        ).first()
        return (row.getValue("spend") as Number).toDouble()
    }

    fun actionsFor(sessionId: String): List<ActionRow> =
        executor.query("SELECT ${ACTION_EXPORT_COLUMNS.joinToString(", ")} FROM actions WHERE session_id = ? ORDER BY step, id", listOf(sessionId))
            .map { r ->
                ActionRow(
                    r.long("id"), r.string("session_id"), r.long("step"), r.string("verb"), r.string("action"), r.string("app"),
                    r.stringOrNull("target_label"), r.stringOrNull("target_id"), r.string("tier"), r.string("rule"), r.string("gate"),
                    r.stringOrNull("approval"), r.string("status"), r.stringOrNull("detail"), r.stringOrNull("fingerprint"), r.double("ts"),
                )
            }

    // ── Exports ───────────────────────────────────────────────────────────────
    // Individual rows, the source of truth, as text for the share sheet. Columns and order
    // are the desktop's so one spreadsheet template reads both files.

    fun exportModelCallsJsonl(sessionId: String? = null): String =
        jsonl(exportRows("model_calls", MODEL_CALL_EXPORT_COLUMNS, sessionId))

    fun exportModelCallsCsv(sessionId: String? = null): String =
        csv(MODEL_CALL_EXPORT_COLUMNS, exportRows("model_calls", MODEL_CALL_EXPORT_COLUMNS, sessionId))

    fun exportActionsJsonl(sessionId: String? = null): String =
        jsonl(exportRows("actions", ACTION_EXPORT_COLUMNS, sessionId))

    fun exportActionsCsv(sessionId: String? = null): String =
        csv(ACTION_EXPORT_COLUMNS, exportRows("actions", ACTION_EXPORT_COLUMNS, sessionId))

    // ── Internals ─────────────────────────────────────────────────────────────

    /** last_insert_rowid() is per connection, so the read rides the insert's transaction. */
    private fun insert(sql: String, args: List<Any?>): Long = executor.transaction {
        executor.exec(sql, args)
        executor.query("SELECT last_insert_rowid() AS id").first().long("id")
    }

    private fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { scrub(it.value) })
        is JsonArray -> JsonArray(element.map(::scrub))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        JsonNull -> element
    }

    /** One export row: column name to a JSON-ready value, `ts` already ISO-8601 UTC, REALs in Python's float text. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun exportRows(table: String, columns: List<String>, sessionId: String?): List<Map<String, JsonPrimitive>> {
        val cols = columns.joinToString(", ")
        val rows = if (sessionId == null) {
            executor.query("SELECT $cols FROM $table ORDER BY ts, id")
        } else {
            executor.query("SELECT $cols FROM $table WHERE session_id = ? ORDER BY ts, id", listOf(sessionId))
        }
        return rows.map { r ->
            columns.associateWith { c ->
                when (val v = r[c]) {
                    null -> JsonNull
                    is String -> JsonPrimitive(v)
                    is Double, is Float -> if (c == "ts") JsonPrimitive(isoUtc(v.toDouble())) else JsonUnquotedLiteral(PyFloat.repr(v.toDouble()))
                    is Number -> JsonPrimitive(v.toLong())
                    else -> JsonPrimitive(v.toString())
                }
            }
        }
    }

    private fun jsonl(rows: List<Map<String, JsonPrimitive>>): String = buildString {
        for (row in rows) append(json.encodeToString(JsonElement.serializer(), JsonObject(row))).append('\n')
    }

    /** Python csv defaults, which is what the desktop writes: comma, minimal quoting, CRLF, NULL as empty. */
    private fun csv(columns: List<String>, rows: List<Map<String, JsonPrimitive>>): String = buildString {
        append(columns.joinToString(",", transform = ::csvField)).append("\r\n")
        for (row in rows) {
            append(columns.joinToString(",") { c -> csvField(row.getValue(c).let { if (it is JsonNull) "" else it.content }) })
            append("\r\n")
        }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun Tier.word() = when (this) {
        Tier.SILENT -> "silent"
        Tier.ONCE_PER_TASK -> "once"
        Tier.EVERY_TIME -> "every"
        Tier.REFUSED -> "refused"
    }

    private fun Map<String, Any?>.long(k: String) = (getValue(k) as Number).toLong()
    private fun Map<String, Any?>.double(k: String) = (getValue(k) as Number).toDouble()
    private fun Map<String, Any?>.string(k: String) = getValue(k) as String
    private fun Map<String, Any?>.stringOrNull(k: String) = this[k] as String?

    companion object {
        /** Desktop `audit_queries._EXPORT_COLUMNS`, same names, same order. */
        val MODEL_CALL_EXPORT_COLUMNS: List<String> = listOf(
            "session_id", "turn_id", "tier", "model", "prompt_tokens", "cached_prompt_tokens",
            "completion_tokens", "cost", "is_classifier", "ts",
        )

        /** The actions table in column order. */
        val ACTION_EXPORT_COLUMNS: List<String> = listOf(
            "id", "session_id", "step", "verb", "action", "app", "target_label", "target_id",
            "tier", "rule", "gate", "approval", "status", "detail", "fingerprint", "ts",
        )

        private val json = Json

        private val isoSeconds: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * Python's `datetime.fromtimestamp(ts, tz=UTC).isoformat()`: microseconds only when
         * there are any, and a literal `+00:00`, so the two exports diff clean.
         */
        fun isoUtc(ts: Double): String {
            val micros = Math.round(ts * 1_000_000.0)
            val seconds = Math.floorDiv(micros, 1_000_000L)
            val fraction = Math.floorMod(micros, 1_000_000L)
            val base = Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).format(isoSeconds)
            return if (fraction == 0L) "$base+00:00" else "$base.${"%06d".format(fraction)}+00:00"
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Python's `repr(float)`, which is what the desktop's csv and json modules write: shortest
 * round-trip digits, positional for exponents -4..15, else `1e-05` / `1e+16`. Java's own
 * text says `3.5E-4` where the desktop says `0.00035`, and the two exports should diff clean.
 */
internal object PyFloat {
    fun repr(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val bd = BigDecimal(d.toString()).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val exponent = digits.length - bd.scale() - 1
        val sign = if (bd.signum() < 0) "-" else ""
        return if (exponent in -4..15) {
            val plain = bd.abs().toPlainString()
            sign + if ('.' in plain) plain else "$plain.0"
        } else {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val expSign = if (exponent < 0) "-" else "+"
            sign + mantissa + "e" + expSign + kotlin.math.abs(exponent).toString().padStart(2, '0')
        }
    }
}
