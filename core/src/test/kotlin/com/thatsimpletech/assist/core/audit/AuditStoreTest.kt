package com.thatsimpletech.assist.core.audit

import com.thatsimpletech.assist.core.policy.Gate
import com.thatsimpletech.assist.core.policy.Tier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditStoreTest {
    // Built at runtime so a key-shaped literal never lands in the repo.
    private val canary = "sk-" + "p".repeat(24)
    private val redact: (String) -> String = { it.replace(canary, "[REDACTED]") }

    private var now = 1_726_000_000.5
    private val db = JdbcSqlExecutor.memory()
    private val store = AuditStore(db, clock = { now }, redact = redact).also { it.migrate() }

    private fun raw(sql: String, args: List<Any?> = emptyList()) = db.query(sql, args)

    private fun action(
        sessionId: String = "s1",
        step: Int = 1,
        verb: String = "tap",
        text: String = "tap 3",
        app: String = "com.whatsapp",
        label: String? = "Send",
        tier: Tier = Tier.EVERY_TIME,
        gate: Gate = Gate.NEED_CARD,
        approval: Approval? = Approval.APPROVED,
        status: ActionStatus = ActionStatus.SUCCESS,
        detail: String? = null,
        ts: Double = now,
    ): Long = store.recordAction(sessionId, step, verb, text, app, label, "btn_send", tier, "sensitive-control", gate, approval, status, detail, "abcd", ts)

    // ── Timestamps ──────────────────────────────────────────────────────────

    @Test
    fun timestampsAreEpochSecondsFromTheClock() {
        store.startSession("s1", "pixel7", "home", "Text Sam")
        now = 1_726_000_001.25
        val id = action()
        assertEquals(1_726_000_000.5, raw("SELECT started_at FROM sessions").single()["started_at"])
        assertEquals(1_726_000_001.25, raw("SELECT ts FROM actions WHERE id = ?", listOf(id)).single()["ts"])
        assertEquals(1_726_000_001.25, store.actionsFor("s1").single().ts)
    }

    @Test
    fun isoUtcMatchesPythonIsoformat() {
        assertEquals("2001-09-09T01:46:40+00:00", AuditStore.isoUtc(1_000_000_000.0))
        assertEquals("2001-09-09T01:46:40.500000+00:00", AuditStore.isoUtc(1_000_000_000.5))
        assertEquals("1970-01-01T00:00:00+00:00", AuditStore.isoUtc(0.0))
    }

    // ── Redaction (plan §1: one shared redactor for logs and audit rows) ───

    @Test
    fun actionTextAndTargetLabelPassThroughTheRedactor() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        action(text = "type 2 \"$canary\"", label = "Key $canary field")
        val row = raw("SELECT action, target_label FROM actions").single()
        assertEquals("type 2 \"[REDACTED]\"", row["action"])
        assertEquals("Key [REDACTED] field", row["target_label"])
        val stored = store.actionsFor("s1").single()
        assertFalse(canary in stored.action + stored.targetLabel)
    }

    @Test
    fun goalTextAndDetailAreRedactedToo() {
        store.startSession("s1", "pixel7", "cloud", "paste $canary into the form")
        action(status = ActionStatus.ERROR, detail = "field rejected $canary")
        assertEquals("paste [REDACTED] into the form", store.session("s1")?.workspacePath)
        assertEquals("field rejected [REDACTED]", raw("SELECT detail FROM actions").single()["detail"])
    }

    @Test
    fun toolCallArgumentsAreScrubbedAndStayValidJson() {
        store.startSession("s1", "pixel7", "home", "goal")
        val args = buildJsonObject {
            put("command", JsonPrimitive("curl -H 'Authorization: Bearer $canary' https://x"))
            put("nested", buildJsonObject { put("key", JsonPrimitive(canary)) })
            put("list", JsonArray(listOf(JsonPrimitive(canary), JsonPrimitive("plain value"), JsonPrimitive(7))))
        }
        store.recordToolCall("s1", "tc1", "shell", args, "C", ToolCallStatus.REFUSED)
        val stored = raw("SELECT arguments FROM tool_calls").single()["arguments"] as String
        assertFalse(canary in stored)
        val parsed = Json.parseToJsonElement(stored).jsonObject
        assertEquals("curl -H 'Authorization: Bearer [REDACTED]' https://x", parsed.getValue("command").jsonPrimitive.content)
        assertEquals("[REDACTED]", parsed.getValue("nested").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("[REDACTED]", parsed.getValue("list").jsonArray[0].jsonPrimitive.content)
        assertEquals("plain value", parsed.getValue("list").jsonArray[1].jsonPrimitive.content)
        assertEquals(7, parsed.getValue("list").jsonArray[2].jsonPrimitive.content.toInt())
    }

    // ── Desktop-shaped rows ─────────────────────────────────────────────────

    @Test
    fun toolCallResultIsKeptOnlyAsItsSha256() {
        store.startSession("s1", "pixel7", "home", "goal")
        val output = "file contents here"
        store.recordToolCall("s1", "tc1", "fs_read", JsonObject(emptyMap()), "A", ToolCallStatus.SUCCESS, output)
        store.recordToolCall("s1", "tc2", "fs_write", JsonObject(emptyMap()), null, ToolCallStatus.ERROR, null)
        val expected = MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
        val rows = raw("SELECT tool_call_id, result_hash, decision_class, status FROM tool_calls ORDER BY id")
        assertEquals(expected, rows[0]["result_hash"])
        assertEquals("success", rows[0]["status"])
        assertNull(rows[1]["result_hash"])
        assertNull(rows[1]["decision_class"])
        assertEquals("error", rows[1]["status"])
    }

    @Test
    fun modelCallLinksToItsTurnAndClassifierCallsStandAlone() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        val turn = store.recordTurn("s1", 1, "brain", 1500, 0.0042, 2.5, ts = 2000.0)
        store.recordModelCall("s1", turn, "brain", "m", 900, 500, 100, 0.01, ts = 4000.0)
        store.recordModelCall("s1", null, "worker", "m-small", 200, 0, 5, 0.0001, isClassifier = true, ts = 4001.0)
        val rows = raw("SELECT turn_id, tier, is_classifier FROM model_calls ORDER BY ts")
        assertEquals(turn, (rows[0]["turn_id"] as Number).toLong())
        assertEquals(0, (rows[0]["is_classifier"] as Number).toInt())
        assertNull(rows[1]["turn_id"])
        assertEquals(1, (rows[1]["is_classifier"] as Number).toInt())
        val t = raw("SELECT turn_index, tokens, cost, duration, ts FROM turns").single()
        assertEquals(listOf(1L, 1500L, 0.0042, 2.5, 2000.0), listOf((t["turn_index"] as Number).toLong(), (t["tokens"] as Number).toLong(), t["cost"], t["duration"], t["ts"]))
    }

    @Test
    fun unreportedCachedTokensStoreAsZero() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        store.recordModelCall("s1", null, "brain", "m", 100, null, 10, 0.001)
        store.recordModelCall("s1", null, "brain", "m", 100, 40, 10, 0.001)
        assertEquals(listOf(0, 40), raw("SELECT cached_prompt_tokens FROM model_calls ORDER BY id").map { (it["cached_prompt_tokens"] as Number).toInt() })
    }

    @Test
    fun daySpendSumsNonClassifierCallsForLocalToday() {
        assertEquals(0.0, store.daySpend(now), 1e-9)
        store.startSession("s1", "pixel7", "cloud", "goal")
        val today = now
        val yesterday = now - 86_400.0
        store.recordModelCall("s1", null, "brain", "m", 100, 0, 10, 0.40, ts = today)
        store.recordModelCall("s1", null, "brain", "m", 100, 0, 10, 0.10, ts = today)
        store.recordModelCall("s1", null, "worker", "m-small", 100, 0, 10, 0.99, isClassifier = true, ts = today)
        store.recordModelCall("s1", null, "brain", "m", 100, 0, 10, 5.00, ts = yesterday)
        assertEquals(0.50, store.daySpend(today), 1e-9)
        assertEquals(5.00, store.daySpend(yesterday), 1e-9)
    }

    @Test
    fun costsViewStillAggregatesOnThePhone() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        store.recordModelCall("s1", null, "brain", "m", 1000, 200, 100, 0.05)
        store.recordModelCall("s1", null, "brain", "m", 500, 500, 50, 0.02)
        store.recordModelCall("s1", null, "brain", "m-small", 100, 0, 10, 0.001, isClassifier = true)
        val row = raw("SELECT prompt_tokens, cached_prompt_tokens, completion_tokens, cost, classifier_cost FROM costs").single()
        assertEquals(1600, (row["prompt_tokens"] as Number).toInt())
        assertEquals(700, (row["cached_prompt_tokens"] as Number).toInt())
        assertEquals(160, (row["completion_tokens"] as Number).toInt())
        assertEquals(0.07, row["cost"] as Double, 1e-9)
        assertEquals(0.001, row["classifier_cost"] as Double, 1e-9)
    }

    // ── Phone rows ──────────────────────────────────────────────────────────

    @Test
    fun tierAndGateAreStoredAsThePolicyWords() {
        store.startSession("s1", "pixel7", "home", "goal")
        action(step = 1, tier = Tier.SILENT, gate = Gate.PROCEED, approval = null)
        action(step = 2, tier = Tier.ONCE_PER_TASK, gate = Gate.NEED_TASK_GRANT, approval = Approval.GRANTED)
        action(step = 3, tier = Tier.EVERY_TIME, gate = Gate.TURN_LIMIT, approval = null, status = ActionStatus.SKIPPED)
        action(step = 4, tier = Tier.REFUSED, gate = Gate.REFUSE, approval = null, status = ActionStatus.REFUSED)
        val rows = store.actionsFor("s1")
        assertEquals(listOf("silent", "once", "every", "refused"), rows.map { it.tier })
        assertEquals(listOf("proceed", "need_task_grant", "turn_limit", "refuse"), rows.map { it.gate })
        assertEquals(listOf(null, "granted", null, null), rows.map { it.approval })
        assertEquals(listOf("success", "success", "skipped", "refused"), rows.map { it.status })
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.step })
    }

    @Test
    fun approvalsRecordTheTaskGrantAndTheCardAgainstItsAction() {
        store.startSession("s1", "pixel7", "home", "goal")
        now = 100.0
        val grant = store.recordApproval("s1", null, ApprovalKind.TASK_GRANT, ApprovalOutcome.APPROVED, requestedAt = 90.0)
        val act = action()
        now = 130.0
        val card = store.recordApproval("s1", act, ApprovalKind.CARD, ApprovalOutcome.TIMEOUT, requestedAt = 85.0, answeredAt = null)
        val rows = raw("SELECT id, action_id, kind, outcome, requested_at, answered_at FROM approvals ORDER BY id")
        assertEquals(grant, (rows[0]["id"] as Number).toLong())
        assertNull(rows[0]["action_id"])
        assertEquals("task_grant", rows[0]["kind"])
        assertEquals("approved", rows[0]["outcome"])
        assertEquals(90.0, rows[0]["requested_at"])
        assertEquals(100.0, rows[0]["answered_at"])
        assertEquals(card, (rows[1]["id"] as Number).toLong())
        assertEquals(act, (rows[1]["action_id"] as Number).toLong())
        assertEquals("card", rows[1]["kind"])
        assertEquals("timeout", rows[1]["outcome"])
        assertNull(rows[1]["answered_at"])
    }

    @Test
    fun twoDevicesKeepTheirDeviceIdApart() {
        store.startSession("s-a", "pixel7", "home", "goal a")
        store.startSession("s-b", "fold11", "cloud", "goal b")
        action(sessionId = "s-a", step = 1, text = "tap 1")
        action(sessionId = "s-b", step = 1, text = "tap 9")
        action(sessionId = "s-a", step = 2, text = "tap 2")

        assertEquals("pixel7", store.session("s-a")?.deviceId)
        assertEquals("home", store.session("s-a")?.plannerMode)
        assertEquals("fold11", store.session("s-b")?.deviceId)
        assertEquals("cloud", store.session("s-b")?.plannerMode)
        assertEquals(listOf("tap 1", "tap 2"), store.actionsFor("s-a").map { it.action })
        assertEquals(listOf("tap 9"), store.actionsFor("s-b").map { it.action })
        val byDevice = raw("SELECT s.device_id AS d, a.action AS act FROM actions a JOIN sessions s ON s.session_id = a.session_id ORDER BY a.id")
            .groupBy({ it["d"] as String }, { it["act"] as String })
        assertEquals(mapOf("pixel7" to listOf("tap 1", "tap 2"), "fold11" to listOf("tap 9")), byDevice)
    }

    // ── Exports ─────────────────────────────────────────────────────────────

    private val desktopColumns = listOf(
        "session_id", "turn_id", "tier", "model", "prompt_tokens", "cached_prompt_tokens", "completion_tokens", "cost", "is_classifier", "ts",
    )

    @Test
    fun modelCallExportsHaveTheDesktopColumnsInOrder() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        val turn = store.recordTurn("s1", 1, "brain", 175, 0.00035, 1.0, ts = 1_000_000_000.0)
        store.recordModelCall("s1", turn, "brain", "m", 100, 50, 25, 0.00035, ts = 1_000_000_000.5)
        store.recordModelCall("s1", null, "worker", "m-small", 20, null, 5, 0.0004, isClassifier = true, ts = 1_000_000_002.0)

        val lines = store.exportModelCallsJsonl().trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        val first = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals(desktopColumns, first.keys.toList())
        assertEquals(turn, first.getValue("turn_id").jsonPrimitive.content.toLong())
        assertEquals("2001-09-09T01:46:40.500000+00:00", first.getValue("ts").jsonPrimitive.content)
        assertEquals(0.00035, first.getValue("cost").jsonPrimitive.content.toDouble(), 1e-12)
        val second = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("null", second.getValue("turn_id").toString())
        assertEquals(1, second.getValue("is_classifier").jsonPrimitive.content.toInt())
        assertEquals(0, second.getValue("cached_prompt_tokens").jsonPrimitive.content.toInt())

        val csv = store.exportModelCallsCsv().split("\r\n")
        assertEquals(desktopColumns.joinToString(","), csv[0])
        assertEquals("s1,$turn,brain,m,100,50,25,0.00035,0,2001-09-09T01:46:40.500000+00:00", csv[1])
        assertEquals("s1,,worker,m-small,20,0,5,0.0004,1,2001-09-09T01:46:42+00:00", csv[2])
        assertEquals("", csv[3])
        // The JSON text carries the same float form, not Java's.
        assertTrue("\"cost\":0.00035," in lines[0], lines[0])
    }

    @Test
    fun realColumnsExportInPythonFloatText() {
        assertEquals("0.00035", PyFloat.repr(0.00035))
        assertEquals("1e-05", PyFloat.repr(0.00001))
        assertEquals("2.5", PyFloat.repr(2.5))
        assertEquals("100.0", PyFloat.repr(100.0))
        assertEquals("0.0", PyFloat.repr(0.0))
        assertEquals("1e+16", PyFloat.repr(1e16))
        assertEquals("-0.0042", PyFloat.repr(-0.0042))
        assertEquals("1234567.5", PyFloat.repr(1234567.5))
        assertEquals("0.30000000000000004", PyFloat.repr(0.1 + 0.2))
    }

    @Test
    fun exportsFilterBySessionAndOrderByTimestamp() {
        store.startSession("s1", "pixel7", "cloud", "goal")
        store.startSession("s2", "pixel7", "cloud", "goal")
        store.recordModelCall("s1", null, "brain", "m", 1, 0, 1, 0.0, ts = 30.0)
        store.recordModelCall("s2", null, "brain", "m", 2, 0, 1, 0.0, ts = 20.0)
        store.recordModelCall("s1", null, "brain", "m", 3, 0, 1, 0.0, ts = 10.0)
        val all = store.exportModelCallsJsonl().trimEnd('\n').split('\n').map { Json.parseToJsonElement(it).jsonObject.getValue("prompt_tokens").jsonPrimitive.content.toInt() }
        assertEquals(listOf(3, 2, 1), all)
        val s1 = store.exportModelCallsJsonl("s1").trimEnd('\n').split('\n').map { Json.parseToJsonElement(it).jsonObject.getValue("prompt_tokens").jsonPrimitive.content.toInt() }
        assertEquals(listOf(3, 1), s1)
    }

    @Test
    fun emptyExportIsHeaderOnlyForCsvAndNothingForJsonl() {
        assertEquals(desktopColumns.joinToString(",") + "\r\n", store.exportModelCallsCsv())
        assertEquals("", store.exportModelCallsJsonl())
        assertEquals("", store.exportActionsJsonl())
    }

    @Test
    fun actionExportsHaveTheActionColumnsInOrderAndQuoteCsvLikePython() {
        store.startSession("s1", "pixel7", "home", "goal")
        val id = action(text = "tap 3", label = "Say \"hi\", now", ts = 1_000_000_000.0)
        val expectedColumns = listOf(
            "id", "session_id", "step", "verb", "action", "app", "target_label", "target_id",
            "tier", "rule", "gate", "approval", "status", "detail", "fingerprint", "ts",
        )
        val line = Json.parseToJsonElement(store.exportActionsJsonl("s1").trimEnd('\n')).jsonObject
        assertEquals(expectedColumns, line.keys.toList())
        assertEquals("Say \"hi\", now", line.getValue("target_label").jsonPrimitive.content)
        assertEquals("null", line.getValue("detail").toString())

        val csv = store.exportActionsCsv("s1").split("\r\n")
        assertEquals(expectedColumns.joinToString(","), csv[0])
        assertEquals(
            "$id,s1,1,tap,tap 3,com.whatsapp,\"Say \"\"hi\"\", now\",btn_send,every,sensitive-control,need_card,approved,success,,abcd,2001-09-09T01:46:40+00:00",
            csv[1],
        )
    }

    // ── Append-only surface ─────────────────────────────────────────────────

    @Test
    fun storeExposesNoUpdateOrDeleteMethods() {
        val public = AuditStore::class.java.methods
            .filter { it.declaringClass == AuditStore::class.java && '$' !in it.name }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "migrate", "getSchemaVersion", "startSession", "recordTurn", "recordToolCall", "recordAction",
                "recordApproval", "recordModelCall", "session", "actionsFor", "daySpend",
                "exportModelCallsJsonl", "exportModelCallsCsv", "exportActionsJsonl", "exportActionsCsv",
            ),
            public,
        )
        assertTrue(public.none { it.startsWith("update") || it.startsWith("delete") || it.startsWith("remove") })
    }
}
