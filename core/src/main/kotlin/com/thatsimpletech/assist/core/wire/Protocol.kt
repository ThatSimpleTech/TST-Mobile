package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.json.Json

/**
 * Constants of tstd's WebSocket protocol that the phone must agree on exactly. The
 * authoritative source is TST-Desk core/tstd/protocol.py; the numbers here are copied from
 * it and the tests cite the line. Plan §3: the phone is a client of that protocol in Home mode.
 */
object Protocol {
    /** protocol.py:26 `PROTOCOL_VERSION = 1`. `validate_version` refuses anything else. */
    const val VERSION = 1

    /** ws.py:167 `handshake_timeout=10.0`: the hello must be the first frame within this long. */
    const val HANDSHAKE_TIMEOUT_SECONDS = 10

    /** ws.py:59: the daemon pings handshaken clients every 15s; 30s of silence is a dead socket (client.ts:109). */
    const val ZOMBIE_SILENCE_MS = 30_000L

    /** Error codes the handshake can answer with before closing 1008 (protocol.py:50-88, ws.py:439-447). */
    const val ERR_VERSION_UNSUPPORTED = "version_unsupported"
    const val ERR_AUTH_FAILED = "auth_failed"
    const val ERR_HANDSHAKE_TIMEOUT = "handshake_timeout"
    const val ERR_UNKNOWN_MESSAGE = "unknown_message"

    /** daemon.py:1687-1725: what an `always_allow` on a class-C call is refused with. */
    const val ERR_CLASS_C_NOT_ALWAYS_ALLOWABLE = "class_c_not_always_allowable"

    /**
     * One Json for the whole wire. Unknown keys are ignored because the protocol grows by
     * additive fields without a version bump (architecture.md "additive-fields policy");
     * a client that fails on a new field would break on every daemon upgrade.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
}
