package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientMessagesTest {
    private fun fields(m: ClientMessage) = Protocol.json.parseToJsonElement(m.encode()).jsonObject

    private val classC = assertIs<TstdEvent.ApprovalRequest>(EventGate().parse(Fixtures.text("approval_request")))
    private val classB = assertIs<TstdEvent.ApprovalRequest>(EventGate().parse(Fixtures.text("approval_request_always_allow")))

    @Test
    fun helloCarriesTheProtocolVersionFromProtocolPy() {
        // core/tstd/protocol.py:26 `PROTOCOL_VERSION = 1`; Hello (protocol.py:111) is {type, token, version}.
        assertEquals(1, Protocol.VERSION)
        val hello = fields(ClientMessages.hello("test-token-abc"))
        assertEquals(setOf("type", "token", "version"), hello.keys)
        assertEquals(JsonPrimitive("hello"), hello["type"])
        assertEquals(JsonPrimitive("test-token-abc"), hello["token"])
        assertEquals(JsonPrimitive(1), hello["version"])
        // And it is exactly the daemon's own sample of a hello.
        assertEquals(Fixtures.all.getValue("hello"), hello)
    }

    @Test
    fun helloRefusesAnEmptyToken() {
        // protocol.py:114 `token: str = Field(min_length=1)`; sending it would only earn a bad_request.
        assertFailsWith<IllegalArgumentException> { ClientMessages.hello("") }
    }

    @Test
    fun approveSerializesToWhatProtocolPyApproveExpects() {
        // protocol.py:178 Approve: type="approve", session_id, tool_call_id. Nothing else.
        val m = fields(ClientMessages.approve(classC))
        assertEquals(setOf("type", "session_id", "tool_call_id"), m.keys)
        assertEquals(JsonPrimitive("approve"), m["type"])
        assertEquals(JsonPrimitive("sess-1"), m["session_id"])
        assertEquals(JsonPrimitive("tc-1"), m["tool_call_id"])
        assertEquals(Fixtures.all.getValue("approve"), m)
    }

    @Test
    fun denySerializesToWhatProtocolPyDenyExpects() {
        // protocol.py:186 Deny: type="deny", session_id, tool_call_id, reason: str | None.
        val withReason = fields(ClientMessages.deny(classC, "not safe"))
        assertEquals(setOf("type", "session_id", "tool_call_id", "reason"), withReason.keys)
        assertEquals(JsonPrimitive("deny"), withReason["type"])
        assertEquals(JsonPrimitive("not safe"), withReason["reason"])
        assertEquals(Fixtures.all.getValue("deny"), withReason)

        val noReason = fields(ClientMessages.deny(classC))
        assertEquals(JsonNull, noReason["reason"])
        assertEquals(Fixtures.all.getValue("deny_no_reason"), noReason)
        // approval.ts denyMessage: a whitespace-only note is no note.
        assertEquals(JsonNull, fields(ClientMessages.deny(classC, "   "))["reason"])
    }

    @Test
    fun alwaysAllowOnClassCIsRefused() {
        assertFalse(ClientMessages.isAlwaysAllowable(classC))
        assertNull(ClientMessages.alwaysAllow(classC))
        // Class C with a proposed rule is still refused: the class is the wall, not the rule (approval.ts isAlwaysAllowable).
        val cWithRule = classC.copy(proposedAlwaysAllow = TstdEvent.PolicyRule("fs_write", "test.txt", "auto"))
        assertNull(ClientMessages.alwaysAllow(cWithRule))
        // Class B without a proposed rule is refused too: the daemon offered nothing to write.
        assertNull(ClientMessages.alwaysAllow(classB.copy(proposedAlwaysAllow = null)))
    }

    @Test
    fun alwaysAllowOnClassBWithARuleSerializesToProtocolPyAlwaysAllow() {
        // protocol.py:195 AlwaysAllow: type="always_allow", session_id, tool_call_id.
        val m = fields(assertNotNull(ClientMessages.alwaysAllow(classB)))
        assertEquals(setOf("type", "session_id", "tool_call_id"), m.keys)
        assertEquals(JsonPrimitive("always_allow"), m["type"])
        assertEquals(JsonPrimitive("sess-1"), m["session_id"])
        assertEquals(JsonPrimitive("tc-2"), m["tool_call_id"])
    }

    @Test
    fun attachDetachAndKillMatchProtocolPy() {
        // protocol.py:327 Attach {session_id, from_seq >= 1}; :335 Detach {session_id}; :871 SetCuKill {killed}.
        val attach = fields(ClientMessages.attach("sess-1", 5))
        assertEquals(setOf("type", "session_id", "from_seq"), attach.keys)
        assertEquals(JsonPrimitive(5), attach["from_seq"])
        assertEquals(Fixtures.all.getValue("attach").keys, attach.keys)
        assertFailsWith<IllegalArgumentException> { ClientMessages.attach("sess-1", 0) }

        assertEquals(Fixtures.all.getValue("detach").keys, fields(ClientMessages.detach("sess-1")).keys)

        val kill = fields(ClientMessages.setCuKill(true))
        assertEquals(setOf("type", "killed"), kill.keys)
        assertEquals(Fixtures.all.getValue("set_cu_kill"), kill)

        val user = fields(ClientMessages.userMessage("sess-1", "hi"))
        assertEquals(setOf("type", "session_id", "content"), user.keys)
        assertTrue(Fixtures.all.getValue("user_message").keys.containsAll(user.keys))
    }

    @Test
    fun encodeIsCompactJsonWithNoWhitespace() {
        val text = ClientMessages.approve("s", "t").encode()
        assertEquals("""{"type":"approve","session_id":"s","tool_call_id":"t"}""", text)
        assertEquals("approve", ClientMessages.approve("s", "t").kind)
    }
}
