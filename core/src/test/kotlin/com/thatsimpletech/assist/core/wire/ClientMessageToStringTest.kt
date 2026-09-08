package com.thatsimpletech.assist.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientMessageToStringTest {
    @Test
    fun helloToStringNeverShowsTheToken() {
        val token = "ab".repeat(32)
        val m = ClientMessages.hello(token)
        assertTrue(token !in m.toString(), m.toString())
        assertEquals("ClientMessage(type=hello)", m.toString())
        assertTrue(token in m.encode())
    }
}
