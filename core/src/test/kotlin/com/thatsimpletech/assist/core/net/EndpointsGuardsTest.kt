package com.thatsimpletech.assist.core.net

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The three doors a plain allowlist leaves open: redirects, cleartext to anyone, and a lying resolver. */
class EndpointsGuardsTest {
    @Test
    fun aRedirectToAnotherHostIsRefusedAndNeverFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://evil.example/steal"))
            server.start()
            val endpoints = Endpoints(setOf(server.hostName))
            val client = endpoints.httpClient()
            val response = client.newCall(Request.Builder().url(server.url("/v1/chat/completions")).build()).execute()
            // The redirect is returned to the caller, not followed; nothing left for evil.example.
            assertEquals(302, response.code)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(server.hostName), endpoints.destinations())
            assertTrue(endpoints.refused().isEmpty())
        }
    }

    @Test
    fun cleartextIsOnlyForLoopbackAndTheTailnet() {
        assertTrue(CleartextPolicy.allowed("127.0.0.1"))
        assertTrue(CleartextPolicy.allowed("localhost"))
        assertTrue(CleartextPolicy.allowed("100.64.0.1"))
        assertTrue(CleartextPolicy.allowed("llm.ezer-server.ts.net"))
        assertTrue(!CleartextPolicy.allowed("example.com"))
        assertTrue(!CleartextPolicy.allowed("192.168.1.10"))
        assertTrue(!CleartextPolicy.allowed("ts.net"))
        val endpoints = Endpoints(setOf("example.com"))
        val e = assertFailsWith<CleartextRefusedException> {
            endpoints.httpClient().newCall(Request.Builder().url("http://example.com/v1").build()).execute()
        }
        assertEquals("example.com", e.host)
        assertEquals(listOf("example.com"), endpoints.refused())
        assertTrue(endpoints.destinations().isEmpty())
        // The same host over https is a different matter (the connection itself fails here, offline).
        assertFailsWith<Exception> { endpoints.httpClient().newCall(Request.Builder().url("https://example.com/v1").build()).execute() }
        assertEquals(listOf("example.com"), endpoints.destinations())
    }

    @Test
    fun magicDnsNamesArePinnedToTailnetAddresses() {
        val tailnet = InetAddress.getByName("100.101.102.103")
        val rogue = InetAddress.getByName("203.0.113.9")
        val dns = TailnetDns { name -> if (name.endsWith(".ts.net")) listOf(rogue, tailnet) else listOf(rogue) }
        assertEquals(listOf(tailnet), dns.lookup("llm.ezer-server.ts.net"))
        assertEquals(listOf(rogue), dns.lookup("openrouter.ai"))
        val onlyRogue = TailnetDns { listOf(rogue) }
        val e = assertFailsWith<UnknownHostException> { onlyRogue.lookup("llm.ezer-server.ts.net") }
        assertTrue("Tailscale" in e.message!!)
    }
}
