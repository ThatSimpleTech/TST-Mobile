package com.thatsimpletech.assist.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointTest {
    @Test
    fun loopbackLiteralsAndLocalhostAreOnBox() {
        assertTrue(Endpoint.isOnBox("http://127.0.0.1:11434/v1"))
        assertTrue(Endpoint.isOnBox("http://127.5.5.5:8000/v1"))
        assertTrue(Endpoint.isOnBox("http://[::1]:8000/v1"))
        assertTrue(Endpoint.isOnBox("http://localhost:8000/v1"))
        assertTrue(Endpoint.isOnBox("http://LOCALHOST/v1"))
        assertEquals(EndpointKind.ON_BOX, Endpoint.kind("http://127.0.0.1:8000/v1"))
    }

    @Test
    fun namesThatMerelyLookLocalAreRemote() {
        assertFalse(Endpoint.isOnBox("http://localhost.evil.example/v1"))
        assertFalse(Endpoint.isOnBox("http://127.0.0.1.example/v1"))
        assertFalse(Endpoint.isOnBox("http://128.0.0.1/v1"))
        assertFalse(Endpoint.isOnBox("not a url"))
        assertFalse(Endpoint.isOnBox(""))
        assertEquals(EndpointKind.REMOTE, Endpoint.kind("https://openrouter.ai/api/v1"))
        assertEquals(EndpointKind.REMOTE, Endpoint.kind("http://home.tailnet.example:4000/v1"))
    }

    @Test
    fun tailscaleCgnatRangeIsTailnetAndItsNeighboursAreNot() {
        assertTrue(Endpoint.isTailnet("http://100.64.0.1:4000/v1"))
        assertTrue(Endpoint.isTailnet("http://100.100.7.7:4000/v1"))
        assertTrue(Endpoint.isTailnet("http://100.127.255.255:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://100.63.255.255:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://100.128.0.0:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://10.0.0.5:4000/v1"))
        assertEquals(EndpointKind.TAILNET, Endpoint.kind("http://100.64.0.1:4000/v1"))
    }

    @Test
    fun tailscaleUlaIsTailnetAndTheNextPrefixIsNot() {
        assertTrue(Endpoint.isTailnet("http://[fd7a:115c:a1e0::1]:4000/v1"))
        assertTrue(Endpoint.isTailnet("http://[fd7a:115c:a1e0:ab12:4843:cd96:6250:1]:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://[fd7a:115c:a1e1::1]:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://[fd00::1]:4000/v1"))
        assertFalse(Endpoint.isTailnet("http://[::1]:4000/v1"))
    }

    @Test
    fun tailnetIsOffBoxButNotRemote() {
        val url = "http://100.64.0.1:4000/v1"
        assertFalse(Endpoint.isOnBox(url))
        assertTrue(Endpoint.isTailnet(url))
        assertEquals(EndpointKind.TAILNET, Endpoint.kind(url))
    }

    @Test
    fun deviceSchemeIsOnDeviceAndHasNoHost() {
        assertTrue(Endpoint.isOnDevice("device://litert-lm"))
        assertEquals(EndpointKind.ON_DEVICE, Endpoint.kind("device://litert-lm"))
        assertFalse(Endpoint.isOnBox("device://litert-lm"))
        assertFalse(Endpoint.isNetwork("device://litert-lm"))
        assertFalse(Endpoint.isOnDevice("https://device.example/v1"))
    }

    @Test
    fun hostIsLowercasedAndUnbracketed() {
        assertEquals("openrouter.ai", Endpoint.host("https://OpenRouter.ai/api/v1"))
        assertEquals("::1", Endpoint.host("http://[::1]:8000/v1"))
        assertEquals("home.tailnet.example", Endpoint.host("http://home.tailnet.example:4000/v1"))
        assertEquals(null, Endpoint.host("garbage"))
    }
}
