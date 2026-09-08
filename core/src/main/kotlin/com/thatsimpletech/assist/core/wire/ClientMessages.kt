package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One client -> daemon frame, already shaped for the wire. Field names are the snake_case
 * names of the pydantic models in protocol.py; the daemon replies `error{unknown_message}`
 * and closes on anything it does not recognise (protocol.py:2302), so nothing here is loose.
 */
class ClientMessage internal constructor(val body: JsonObject) {
    val kind: String get() = (body.getValue("type") as JsonPrimitive).content

    /** Compact JSON text, the exact frame that goes on the socket. */
    fun encode(): String = Protocol.json.encodeToString(JsonObject.serializer(), body)

    /** Never the wire text: a hello carries the token, and generated strings end up in logs. */
    override fun toString(): String = "ClientMessage(type=${(body["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"})"
}

/** The messages the phone sends. Only what Home mode needs; the union has 73 members. */
object ClientMessages {
    /** protocol.py:111 `Hello`. Must be the first frame, within [Protocol.HANDSHAKE_TIMEOUT_SECONDS]. */
    fun hello(token: String, version: Int = Protocol.VERSION): ClientMessage {
        require(token.isNotEmpty()) { "hello.token must not be empty (protocol.py Hello: min_length=1)" }
        return msg("hello") {
            put("token", token)
            put("version", version)
        }
    }

    /** protocol.py:327 `Attach`. Replays from [fromSeq] then streams live. Send before any user_message (TD-1713). */
    fun attach(sessionId: String, fromSeq: Int = 1): ClientMessage {
        require(fromSeq >= 1) { "attach.from_seq must be >= 1" }
        return msg("attach") {
            put("session_id", sessionId)
            put("from_seq", fromSeq)
        }
    }

    /** protocol.py:335 `Detach`. */
    fun detach(sessionId: String): ClientMessage = msg("detach") { put("session_id", sessionId) }

    /** protocol.py:525 `ListSessions`. Answered with `session_list`. */
    fun listSessions(): ClientMessage = msg("list_sessions") {}

    /** protocol.py:141 `UserMessage` without attachments. */
    fun userMessage(sessionId: String, content: String): ClientMessage = msg("user_message") {
        put("session_id", sessionId)
        put("content", content)
    }

    /** protocol.py:178 `Approve`. */
    fun approve(request: TstdEvent.ApprovalRequest): ClientMessage = approve(request.sessionId, request.toolCallId)

    fun approve(sessionId: String, toolCallId: String): ClientMessage = msg("approve") {
        put("session_id", sessionId)
        put("tool_call_id", toolCallId)
    }

    /** protocol.py:186 `Deny`. A blank [reason] is sent as null, as approval.ts does. */
    fun deny(request: TstdEvent.ApprovalRequest, reason: String? = null): ClientMessage =
        deny(request.sessionId, request.toolCallId, reason)

    fun deny(sessionId: String, toolCallId: String, reason: String? = null): ClientMessage = msg("deny") {
        put("session_id", sessionId)
        put("tool_call_id", toolCallId)
        put("reason", if (reason.isNullOrBlank()) JsonNull else JsonPrimitive(reason))
    }

    /**
     * approval.ts `isAlwaysAllowable`: class C is the wall and the daemon only attaches a
     * proposed rule when it can generate one. Both are checked so the button is absent, not
     * merely refused later with `class_c_not_always_allowable`.
     */
    fun isAlwaysAllowable(request: TstdEvent.ApprovalRequest): Boolean =
        request.decisionClass != TstdEvent.DecisionClass.C && request.proposedAlwaysAllow != null

    /** protocol.py:195 `AlwaysAllow`, or null when [isAlwaysAllowable] says no. */
    fun alwaysAllow(request: TstdEvent.ApprovalRequest): ClientMessage? {
        if (!isAlwaysAllowable(request)) return null
        return msg("always_allow") {
            put("session_id", request.sessionId)
            put("tool_call_id", request.toolCallId)
        }
    }

    /** protocol.py:871 `SetCuKill`. Process-wide; acked with `cu_kill_state`. */
    fun setCuKill(killed: Boolean): ClientMessage = msg("set_cu_kill") { put("killed", killed) }

    private inline fun msg(type: String, fields: JsonObjectBuilder.() -> Unit): ClientMessage =
        ClientMessage(buildJsonObject {
            put("type", type)
            fields()
        })
}
