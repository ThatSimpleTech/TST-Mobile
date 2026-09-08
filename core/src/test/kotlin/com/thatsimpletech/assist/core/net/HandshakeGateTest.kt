package com.thatsimpletech.assist.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandshakeGateTest {
    private val expected = AuthToken.mint()
    private val offered = AuthToken.mint()

    private fun frame(token: String, version: Any = 1) = """{"type":"hello","version":$version,"token":"$token"}"""

    private fun assertRejectedWithoutToken(r: HandshakeResult, rule: String): HandshakeResult.Reject {
        val reject = assertIs<HandshakeResult.Reject>(r)
        assertEquals(1008, reject.code)
        assertTrue(reject.reason.startsWith(rule), reject.reason)
        assertFalse(expected.reveal() in reject.reason, "expected token leaked")
        assertFalse(offered.reveal() in reject.reason, "offered token leaked")
        return reject
    }

    @Test
    fun validHelloIsAccepted() {
        val r = HandshakeGate.check(frame(expected.reveal()), expected, 1, elapsedMs = 250)
        assertEquals(HandshakeResult.Accept, r)
    }

    @Test
    fun extraFieldsInTheHelloAreIgnored() {
        val r = HandshakeGate.check("""{"type":"hello","version":1,"token":"${expected.reveal()}","client":"phone"}""", expected, 1, elapsedMs = 0)
        assertEquals(HandshakeResult.Accept, r)
    }

    @Test
    fun wrongTokenIsRejectedWith1008AndNoTokenInReason() {
        assertRejectedWithoutToken(HandshakeGate.check(frame(offered.reveal()), expected, 1, elapsedMs = 0), "auth_failed")
    }

    @Test
    fun wrongVersionIsRejectedBeforeTheTokenIsChecked() {
        val r = assertRejectedWithoutToken(HandshakeGate.check(frame(expected.reveal(), 2), expected, 1, elapsedMs = 0), "version_unsupported")
        assertTrue("2" in r.reason && "1" in r.reason, r.reason)
    }

    @Test
    fun lateFrameIsRejectedEvenWhenItIsValid() {
        assertRejectedWithoutToken(HandshakeGate.check(frame(expected.reveal()), expected, 1, elapsedMs = 10_001), "handshake_timeout")
        assertEquals(HandshakeResult.Accept, HandshakeGate.check(frame(expected.reveal()), expected, 1, elapsedMs = 10_000))
        assertRejectedWithoutToken(HandshakeGate.check(frame(expected.reveal()), expected, 1, elapsedMs = 501, timeoutMs = 500), "handshake_timeout")
    }

    @Test
    fun malformedFramesAreRejectedAsBadRequest() {
        val bad = listOf(
            "not json",
            "[1,2]",
            """{"type":"ping"}""",
            """{"type":"hello","version":1}""",
            """{"type":"hello","token":"${expected.reveal()}"}""",
            """{"type":"hello","version":"one","token":"${expected.reveal()}"}""",
            """{"type":"hello","version":1,"token":12}""",
            "",
        )
        for (f in bad) assertRejectedWithoutToken(HandshakeGate.check(f, expected, 1, elapsedMs = 0), "bad_request")
        assertRejectedWithoutToken(HandshakeGate.check(null, expected, 1, elapsedMs = 0), "bad_request")
    }

    @Test
    fun helloFrameRoundTrips() {
        val text = Hello.frame(expected, 1)
        assertEquals("""{"type":"hello","version":1,"token":"${expected.reveal()}"}""", text)
        val h = assertNotNull(Hello.parse(text))
        assertEquals(1, h.version)
        assertEquals(expected.reveal(), h.token)
        assertNull(Hello.parse("""{"type":"hello_ack","version":1}"""))
    }

    @Test
    fun helloToStringHidesTheToken() {
        val h = Hello(1, expected.reveal())
        assertEquals("Hello(version=1, token=<token>)", h.toString())
    }
}
