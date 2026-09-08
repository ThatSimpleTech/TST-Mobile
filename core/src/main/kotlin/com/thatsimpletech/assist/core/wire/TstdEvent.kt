package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The phone's model of a daemon -> client frame (plan §3: "the event union in client.ts is
 * the contract; drop and count anything outside it"). The union itself is `DaemonEventUnion`
 * in TST-Desk ui/src/lib/protocol.ts (50 kinds), a hand mirror of `DaemonEventT` in
 * core/tstd/protocol.py; this sealed class is the third hand mirror.
 *
 * Kinds the phone acts on get a typed subtype. Every other kind in the union arrives as
 * [Known] with its payload, so a screen that wants it later can read it without a protocol
 * change here. Anything outside the union is [Unknown] and carries nothing.
 *
 * Two frames every client must handle sit outside the union on purpose: [HelloAck] and
 * [Ping] belong to the connection, not to any session's log, and carry no `seq`.
 */
sealed class TstdEvent {
    /** The wire `type`. */
    abstract val kind: String

    /** Per-session monotonic sequence (protocol.py `DaemonEvent.seq`), absent on out-of-band frames. */
    open val seq: Int? get() = null

    /** The session this event belongs to; null for connection-scoped events. */
    open val sessionId: String? get() = null

    /** Reply to `hello` (protocol.py:2370 `build_hello_ack`). Not in the union; no seq. */
    @Serializable
    data class HelloAck(val version: Int) : TstdEvent() {
        override val kind: String get() = HELLO_ACK
    }

    /** Liveness frame (protocol.py:1837). Proves the peer is alive, says nothing else. */
    data object Ping : TstdEvent() {
        override val kind: String get() = PING
    }

    /** protocol.py:959. [state] is one of idle, running, awaiting_approval, paused, complete, failed, cancelled, interrupted. */
    @Serializable
    data class SessionState(
        override val seq: Int,
        @SerialName("session_id") override val sessionId: String,
        val state: String,
        val reason: String? = null,
    ) : TstdEvent() {
        override val kind: String get() = SESSION_STATE
    }

    /** protocol.py:1534. One row of a [SessionList]. */
    @Serializable
    data class SessionSummary(
        @SerialName("session_id") val sessionId: String,
        @SerialName("workspace_path") val workspacePath: String,
        val state: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        @SerialName("event_count") val eventCount: Int = 0,
        val archived: Boolean = false,
        val starred: Boolean = false,
        val title: String? = null,
        val preset: String = "",
        val busy: Boolean = false,
    )

    /** protocol.py:1575. Connection-scoped, seq fixed at 1. */
    @Serializable
    data class SessionList(
        override val seq: Int = 1,
        val sessions: List<SessionSummary> = emptyList(),
    ) : TstdEvent() {
        override val kind: String get() = SESSION_LIST
    }

    /** protocol.py:1008. One streamed chunk of assistant text. */
    @Serializable
    data class AssistantDelta(
        override val seq: Int,
        @SerialName("session_id") override val sessionId: String,
        val delta: String,
    ) : TstdEvent() {
        override val kind: String get() = ASSISTANT_DELTA
    }

    /** protocol.py:1222. The end of a message: the turn's whole token and cost bill. */
    @Serializable
    data class TurnComplete(
        override val seq: Int,
        @SerialName("session_id") override val sessionId: String,
        val tokens: Int,
        val cost: Double,
        val tier: String,
        val duration: Double,
        val failed: Boolean = false,
        @SerialName("error_code") val errorCode: String? = null,
    ) : TstdEvent() {
        override val kind: String get() = TURN_COMPLETE
    }

    /** TST Desk's reversibility axis. C is the wall: policy can never run it automatically. */
    @Serializable
    enum class DecisionClass { A, B, C }

    /** protocol.py:1073. The rule "always allow in this workspace" would write. */
    @Serializable
    data class PolicyRule(val tool: String, val args: String, val effect: String)

    /**
     * protocol.py:1085. The approval card. [proposedAlwaysAllow] is null when the daemon
     * refuses to generate a rule, which it always does for class C.
     */
    @Serializable
    data class ApprovalRequest(
        override val seq: Int,
        @SerialName("session_id") override val sessionId: String,
        @SerialName("tool_call_id") val toolCallId: String,
        @SerialName("tool_name") val toolName: String,
        val arguments: JsonObject,
        @SerialName("decision_class") val decisionClass: DecisionClass,
        val summary: String,
        val reason: String,
        @SerialName("proposed_always_allow") val proposedAlwaysAllow: PolicyRule? = null,
    ) : TstdEvent() {
        override val kind: String get() = APPROVAL_REQUEST
    }

    /** protocol.py:1150. Running totals; the last one of a turn agrees with [TurnComplete.cost]. */
    @Serializable
    data class CostUpdate(
        override val seq: Int,
        @SerialName("session_id") override val sessionId: String,
        @SerialName("turn_cost") val turnCost: Double,
        @SerialName("session_cost") val sessionCost: Double,
        @SerialName("total_cost") val totalCost: Double,
        @SerialName("classifier_cost") val classifierCost: Double = 0.0,
        @SerialName("cost_by_tier") val costByTier: Map<String, Double> = emptyMap(),
    ) : TstdEvent() {
        override val kind: String get() = COST_UPDATE
    }

    /**
     * protocol.py:1855. [seq] is nullable because `build_error` (protocol.py:2380) writes the
     * frame straight to the socket without one; handshake failures arrive that way.
     */
    @Serializable
    data class Error(
        override val seq: Int? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        val code: String,
        val message: String,
    ) : TstdEvent() {
        override val kind: String get() = ERROR
    }

    /** protocol.py:1882. The daemon's computer-use kill switch; connection-scoped, seq fixed at 1. */
    @Serializable
    data class CuKillState(
        override val seq: Int = 1,
        val killed: Boolean,
    ) : TstdEvent() {
        override val kind: String get() = CU_KILL_STATE
    }

    /** protocol.py:1773. Connection-scoped: the replay window starts at [earliestSeq]. */
    @Serializable
    data class LogTrimmed(
        override val seq: Int = 1,
        @SerialName("session_id") override val sessionId: String,
        @SerialName("requested_from_seq") val requestedFromSeq: Int,
        @SerialName("earliest_seq") val earliestSeq: Int,
    ) : TstdEvent() {
        override val kind: String get() = LOG_TRIMMED
    }

    /** A kind in the union the phone has no typed model for yet. The payload rides along. */
    data class Known(
        override val kind: String,
        override val seq: Int?,
        override val sessionId: String?,
        val json: JsonObject,
    ) : TstdEvent()

    /** A kind outside the union. Dropped and counted; the payload is not kept (plan §3). */
    data class Unknown(override val kind: String) : TstdEvent()

    companion object {
        const val HELLO_ACK = "hello_ack"
        const val PING = "ping"
        const val SESSION_STATE = "session_state"
        const val SESSION_LIST = "session_list"
        const val ASSISTANT_DELTA = "assistant_delta"
        const val TURN_COMPLETE = "turn_complete"
        const val APPROVAL_REQUEST = "approval_request"
        const val COST_UPDATE = "cost_update"
        const val ERROR = "error"
        const val CU_KILL_STATE = "cu_kill_state"
        const val LOG_TRIMMED = "log_trimmed"

        /**
         * `DaemonEventUnion`, protocol.ts:1347, in its order. 50 kinds. `ping` is deliberately
         * absent there (DECISIONS.md TD-4438) and `hello_ack` was never in it; both are in
         * [OUT_OF_BAND]. Keep this in sync with protocol.ts the way client.ts:26-77 does.
         */
        val UNION: Set<String> = linkedSetOf(
            "ready", "session_state", "conversation_reset", "user_turn", "assistant_delta",
            "assistant_reasoning", "tool_call", "tool_result", "shell_output", "approval_request",
            "decision_logged", "checkpoint_notice", "verify_result", "cost_update", "boundary_update",
            "turn_complete", "tier_state", "context_compacted", "steering_reloaded", "rule_activated",
            "tier_switched", "instruction_stack", "instruction_files", "command_list", "context_pins",
            "memory_files", "charter", "autonomy_start", "autonomy_summary", "memory_proposal",
            "session_list", "policy_rules", "setup_state", "api_key_validated", "diagnostics_report",
            "usage_report", "usage_exported", "log_trimmed", "artifact_ready", "artifact_list",
            "artifact", "error", "screen_frame", "cu_kill_state", "cu_session", "design_hit",
            "cu_permissions", "job_list", "job_draft", "transcript",
        )

        /** Frames about the connection, not any session (protocol.ts:636-651). */
        val OUT_OF_BAND: Set<String> = linkedSetOf(HELLO_ACK, PING)

        /** Everything the gate lets through. */
        val KNOWN: Set<String> = UNION + OUT_OF_BAND

        /**
         * client.ts:466-517: replies stamped seq=1 that are not in the session log. They must
         * bypass sequence bookkeeping or an attached client drops them as stale (TD-1204, TD-3403).
         */
        val CONNECTION_SCOPED_REPLIES: Set<String> = linkedSetOf("instruction_stack", "design_hit", "transcript", LOG_TRIMMED)
    }
}
