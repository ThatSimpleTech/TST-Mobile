package com.thatsimpletech.assist.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The first text frame on either socket: `{"type":"hello","version":N,"token":"..."}`,
 * TST Desk's handshake exactly. The phone sends one to tstd in Home mode and expects one
 * on its own device socket. Not a data class on purpose: the token must not ride along in
 * a generated toString.
 */
class Hello(val version: Int, val token: String) {
    override fun toString(): String = "Hello(version=$version, token=<token>)"

    companion object {
        const val TYPE = "hello"
        const val PROTOCOL_VERSION = 1

        fun frame(token: AuthToken, version: Int = PROTOCOL_VERSION): String =
            buildJsonObject {
                put("type", TYPE)
                put("version", version)
                put("token", token.reveal())
            }.toString()

        /** Null when the text is not a hello object with a numeric version and a string token. */
        fun parse(text: String): Hello? {
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != TYPE) return null
            val version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: return null
            val token = (obj["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return Hello(version, token)
        }
    }
}

sealed interface HandshakeResult {
    data object Accept : HandshakeResult
    /** Always close code 1008 (policy violation). [reason] is safe to send and to log. */
    data class Reject(val reason: String, val code: Int = HandshakeGate.CLOSE_POLICY_VIOLATION) : HandshakeResult
}

/**
 * Judges the first frame. Late, malformed, wrong version, wrong token: all rejected with
 * 1008 and a reason that names the rule, never the token (neither the expected one nor the
 * one offered).
 */
object HandshakeGate {
    const val CLOSE_POLICY_VIOLATION = 1008
    const val DEFAULT_TIMEOUT_MS = 10_000L

    fun check(
        frameText: String?,
        expectedToken: AuthToken,
        expectedVersion: Int = Hello.PROTOCOL_VERSION,
        elapsedMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HandshakeResult {
        if (elapsedMs > timeoutMs) return HandshakeResult.Reject("handshake_timeout: no hello within ${timeoutMs / 1000}s")
        if (frameText == null) return HandshakeResult.Reject("bad_request: handshake must be a text frame")
        val hello = Hello.parse(frameText) ?: return HandshakeResult.Reject("bad_request: first message must be a hello with version and token")
        if (hello.version != expectedVersion) {
            return HandshakeResult.Reject("version_unsupported: protocol version ${hello.version} is not supported, this side speaks $expectedVersion")
        }
        if (!AuthToken.equals(hello.token, expectedToken.reveal())) {
            return HandshakeResult.Reject("auth_failed: invalid auth token")
        }
        return HandshakeResult.Accept
    }
}
