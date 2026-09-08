package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate every inbound frame passes (client.ts `handleMessage` + `dispatch`, TD-4816).
 * It never throws: a frame that is not JSON, not an object, or has no string `type` is
 * malformed; a `type` outside [TstdEvent.KNOWN] is unknown. Both are dropped and counted so
 * drift between the daemon and this mirror is observable instead of silent.
 *
 * One deliberate difference from the desktop: it casts payloads unchecked, this decodes the
 * typed kinds strictly and counts a bad payload as malformed. The phone acts on those fields
 * (an approval card, the kill switch) and must not act on garbage.
 */
class EventGate {
    private val unknown = AtomicInteger()
    private val malformed = AtomicInteger()

    /** Frames dropped because their `type` is outside the union. */
    val unknownCount: Int get() = unknown.get()

    @Volatile var lastUnknownKind: String? = null
        private set

    /** Frames dropped because they were not a well-formed protocol frame. */
    val malformedCount: Int get() = malformed.get()

    @Volatile var lastMalformedReason: String? = null
        private set

    /**
     * @return the event; [TstdEvent.Unknown] for a kind outside the union (already counted);
     *   null for a malformed frame (already counted). Never throws.
     */
    fun parse(text: String): TstdEvent? {
        val element = try {
            Protocol.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return drop("not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            return drop("not JSON: ${e.message}")
        }
        val obj = element as? JsonObject ?: return drop("not a JSON object")
        val typeField = obj["type"] as? JsonPrimitive
        if (typeField == null || !typeField.isString) return drop("no string `type`")
        val kind = typeField.content
        if (kind !in TstdEvent.KNOWN) {
            unknown.incrementAndGet()
            lastUnknownKind = kind
            return TstdEvent.Unknown(kind)
        }
        return try {
            decode(kind, obj)
        } catch (e: SerializationException) {
            drop("$kind: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            drop("$kind: ${e.message}")
        }
    }

    private fun decode(kind: String, obj: JsonObject): TstdEvent {
        val json = Protocol.json
        return when (kind) {
            TstdEvent.HELLO_ACK -> json.decodeFromJsonElement(TstdEvent.HelloAck.serializer(), obj)
            TstdEvent.PING -> TstdEvent.Ping
            TstdEvent.SESSION_STATE -> json.decodeFromJsonElement(TstdEvent.SessionState.serializer(), obj)
            TstdEvent.SESSION_LIST -> json.decodeFromJsonElement(TstdEvent.SessionList.serializer(), obj)
            TstdEvent.ASSISTANT_DELTA -> json.decodeFromJsonElement(TstdEvent.AssistantDelta.serializer(), obj)
            TstdEvent.TURN_COMPLETE -> json.decodeFromJsonElement(TstdEvent.TurnComplete.serializer(), obj)
            TstdEvent.APPROVAL_REQUEST -> json.decodeFromJsonElement(TstdEvent.ApprovalRequest.serializer(), obj)
            TstdEvent.COST_UPDATE -> json.decodeFromJsonElement(TstdEvent.CostUpdate.serializer(), obj)
            TstdEvent.ERROR -> json.decodeFromJsonElement(TstdEvent.Error.serializer(), obj)
            TstdEvent.CU_KILL_STATE -> json.decodeFromJsonElement(TstdEvent.CuKillState.serializer(), obj)
            TstdEvent.LOG_TRIMMED -> json.decodeFromJsonElement(TstdEvent.LogTrimmed.serializer(), obj)
            else -> TstdEvent.Known(
                kind = kind,
                seq = (obj["seq"] as? JsonPrimitive)?.intOrNull,
                sessionId = (obj["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                json = obj,
            )
        }
    }

    private fun drop(reason: String): TstdEvent? {
        malformed.incrementAndGet()
        lastMalformedReason = reason
        return null
    }
}
