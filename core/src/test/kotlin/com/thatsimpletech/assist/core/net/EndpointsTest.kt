package com.thatsimpletech.assist.core.net

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointsTest {

    private fun <T> withServer(block: (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun destinationRecordedEqualsTheMockHost() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val host = server.url("/").host
        val endpoints = Endpoints(setOf(host))
        val response = endpoints.httpClient().newCall(Request.Builder().url(server.url("/ping")).build()).execute()
        response.use { assertEquals("ok", it.body.string()) }
        assertEquals(listOf(host), endpoints.destinations())
        assertEquals(emptyList(), endpoints.refused())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun hostNotNamedIsRefusedBeforeAnySocketOpens() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("never"))
        val host = server.url("/").host
        val endpoints = Endpoints(setOf("api.example.com"))
        val e = assertFailsWith<HostNotNamedException> {
            endpoints.httpClient().newCall(Request.Builder().url(server.url("/ping")).build()).execute()
        }
        assertEquals(host, e.host)
        assertTrue(host in e.message!!, e.message)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList(), endpoints.destinations())
        assertEquals(listOf(host), endpoints.refused())
    }

    @Test
    fun allowedHostsMatchCaseInsensitively() {
        val endpoints = Endpoints(setOf("API.Example.com "))
        assertTrue(endpoints.isAllowed("api.example.com"))
        assertTrue(endpoints.isAllowed("Api.Example.Com"))
        assertTrue(!endpoints.isAllowed("example.com"))
    }

    @Test
    fun webSocketClientAppliesTheSameRule() {
        val endpoints = Endpoints(setOf("100.64.1.5"))
        val e = assertFailsWith<HostNotNamedException> { endpoints.webSocketClient("ws://192.168.1.20:8765/") }
        assertEquals("192.168.1.20", e.host)
        endpoints.webSocketClient("ws://100.64.1.5:8765/")
        endpoints.webSocketClient("wss://100.64.1.5:8765/")
        assertEquals(listOf("100.64.1.5", "100.64.1.5"), endpoints.destinations())
        assertEquals(listOf("192.168.1.20"), endpoints.refused())
    }
}
