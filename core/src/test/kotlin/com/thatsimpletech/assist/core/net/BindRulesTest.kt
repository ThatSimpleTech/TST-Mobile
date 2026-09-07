package com.thatsimpletech.assist.core.net

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BindRulesTest {

    @Test
    fun everyUnspecifiedHostIsRefused() {
        val hosts = listOf("0.0.0.0", "::", "", "*", "[::]", "::ffff:0.0.0.0", " 0.0.0.0 ", "0:0:0:0:0:0:0:0", "[::ffff:0.0.0.0]")
        for (h in hosts) {
            val c = BindRules.classify(h)
            assertIs<BindClass.Refused>(c, "expected '$h' refused")
            assertTrue("never 0.0.0.0" in c.reason, "reason for '$h' should name the rule: ${c.reason}")
            assertTrue(BindRules.isUnspecified(h), "isUnspecified('$h')")
        }
    }

    @Test
    fun loopbackSpellingsAreLoopback() {
        for (h in listOf("127.0.0.1", "127.5.6.7", "::1", "[::1]", "localhost", "LOCALHOST", "::ffff:127.0.0.1", "::1%lo")) {
            assertTrue(BindRules.isLoopback(h), "isLoopback('$h')")
            assertEquals(BindClass.Loopback, BindRules.classify(h), h)
        }
        assertFalse(BindRules.isLoopback("128.0.0.1"))
        assertFalse(BindRules.isLoopback("::2"))
    }

    @Test
    fun cgnatAndTailscaleUlaAreAccepted() {
        assertEquals(BindClass.Tailscale("100.64.0.1"), BindRules.classify("100.64.0.1"))
        assertEquals(BindClass.Tailscale("100.127.255.254"), BindRules.classify("100.127.255.254"))
        assertEquals(BindClass.Tailscale("fd7a:115c:a1e0:1::2"), BindRules.classify("fd7a:115c:a1e0:1::2"))
        assertEquals(BindClass.Tailscale("fd7a:115c:a1e0::5"), BindRules.classify("fd7a:115c:a1e0::5"))
        assertTrue(BindRules.isTailscale("100.100.100.100"))
    }

    @Test
    fun lanAndPublicAddressesAreRefused() {
        for (h in listOf("192.168.1.1", "10.0.0.1", "172.16.0.1", "100.63.255.255", "100.128.0.1", "8.8.8.8", "fd7a:115c:a1e1::1", "2001:db8::1")) {
            val c = BindRules.classify(h)
            assertIs<BindClass.Refused>(c, "expected '$h' refused")
            assertTrue("not a Tailscale address" in c.reason, c.reason)
            assertFalse(BindRules.isTailscale(h), "isTailscale('$h')")
        }
    }

    @Test
    fun ipv6ZoneIdIsStrippedBeforeClassifying() {
        assertEquals(BindClass.Tailscale("fd7a:115c:a1e0::5"), BindRules.classify("fd7a:115c:a1e0::5%tun0"))
        assertEquals(BindClass.Tailscale("fd7a:115c:a1e0::5"), BindRules.classify("[fd7a:115c:a1e0::5%tun0]"))
        assertIs<BindClass.Refused>(BindRules.classify("fe80::1%wlan0"))
    }

    @Test
    fun ipv4MappedAddressesAreJudgedAsIpv4() {
        assertEquals(BindClass.Tailscale("100.64.1.5"), BindRules.classify("::ffff:100.64.1.5"))
        assertEquals(BindClass.Tailscale("100.64.1.5"), BindRules.classify("::ffff:6440:105"))
        assertIs<BindClass.Refused>(BindRules.classify("::ffff:192.168.1.1"))
        assertEquals(BindClass.Loopback, BindRules.classify("::ffff:127.0.0.1"))
    }

    @Test
    fun interfaceNamesAreNotAddressesOnThePhone() {
        for (h in listOf("tailscale0", "tun0", "utun3", "eth0", "not an ip", "100.64.1", "100.64.1.5.6", "1:2:3:4:5:6:7:8:9")) {
            assertIs<BindClass.Refused>(BindRules.classify(h), "expected '$h' refused")
        }
    }

    // ---- binding ----

    private class FakeListener(override val host: String, override val port: Int) : Listener {
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeFactory(private val failOn: (String) -> Boolean = { false }) : ListenerFactory {
        val calls = ArrayList<Pair<String, Int>>()
        val opened = ArrayList<FakeListener>()
        override fun listen(host: String, port: Int): Listener {
            calls.add(host to port)
            if (failOn(host)) throw IOException("address in use: $host")
            return FakeListener(host, if (port == 0) 43210 else port).also { opened.add(it) }
        }
    }

    @Test
    fun bindWithNoExtraListensOnLoopbackOnly() {
        val f = FakeFactory()
        val bound = BindRules.bind(f, null)
        assertEquals(listOf("127.0.0.1"), bound.hosts)
        assertEquals(43210, bound.port)
        assertEquals(listOf("127.0.0.1" to 0), f.calls)
    }

    @Test
    fun tailscaleExtraSharesTheLoopbackPort() {
        val f = FakeFactory()
        val bound = BindRules.bind(f, "100.64.1.5")
        assertEquals(listOf("127.0.0.1", "100.64.1.5"), bound.hosts)
        assertEquals(listOf("127.0.0.1" to 0, "100.64.1.5" to 43210), f.calls)
    }

    @Test
    fun nonTailscaleExtraIsRefusedBeforeAnySocketOpens() {
        for (extra in listOf("0.0.0.0", "192.168.1.1", "127.0.0.1", "", "tun0")) {
            val f = FakeFactory()
            assertFailsWith<BindRefusedException>("extra '$extra'") { BindRules.bind(f, extra) }
            assertTrue(f.calls.isEmpty(), "no listen call for extra '$extra'")
        }
    }

    @Test
    fun bindFailsClosedWhenTheSecondListenerFails() {
        val f = FakeFactory(failOn = { it != "127.0.0.1" })
        assertFailsWith<IOException> { BindRules.bind(f, "100.64.1.5") }
        assertEquals(1, f.opened.size)
        assertTrue(f.opened[0].closed, "loopback listener must be closed when the Tailscale one fails")
    }

    @Test
    fun closingBoundListenersClosesBoth() {
        val f = FakeFactory()
        BindRules.bind(f, "fd7a:115c:a1e0::5").close()
        assertTrue(f.opened.all { it.closed })
        assertEquals(2, f.opened.size)
    }
}
