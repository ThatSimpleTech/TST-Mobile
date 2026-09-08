package com.thatsimpletech.assist.core.net

import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.config.TierName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderPolicyTest {

    @Test
    fun openRouterIsAllowedInFamilyMode() {
        assertTrue(ProviderPolicy.hostAllowed("https://openrouter.ai/api/v1", familyMode = true))
        val shipped = AssistConfig.loadDefault()
        assertTrue(ProviderPolicy.hostAllowed(shipped.tier(TierName.BRAIN).baseUrl, familyMode = true))
        assertEquals("moonshotai/kimi-k3", shipped.tier(TierName.BRAIN).slug)
        assertEquals("openrouter.ai", Endpoint.host(shipped.tier(TierName.BRAIN).baseUrl))
        assertFalse("openrouter.ai" in ProviderPolicy.blockedHosts)
        assertFalse("moonshotai/kimi-k3" in ProviderPolicy.blockedHosts)
        assertFalse("kimi-k3" in ProviderPolicy.blockedHosts)
        assertNull(ProviderPolicy.familyBlockReason("https://openrouter.ai/api/v1", familyMode = true))
    }

    @Test
    fun moonshotCnIsBlockedInFamilyMode() {
        assertFalse(ProviderPolicy.hostAllowed("https://api.moonshot.cn/v1", familyMode = true))
        assertFalse(ProviderPolicy.hostAllowed("https://API.Moonshot.CN/v1", familyMode = true))
        assertFalse(ProviderPolicy.hostAllowed("https://api.moonshot.ai/v1", familyMode = true))
        assertEquals(
            ProviderPolicy.FAMILY_BLOCKED,
            ProviderPolicy.familyBlockReason("https://api.moonshot.cn/v1", familyMode = true),
        )
        assertEquals("that host is blocked in family mode", ProviderPolicy.FAMILY_BLOCKED)
    }

    @Test
    fun deepseekComBlockedInFamily() {
        assertFalse(ProviderPolicy.hostAllowed("https://api.deepseek.com/v1", familyMode = true))
        assertFalse(ProviderPolicy.hostAllowed("https://api.deepseek.com:443/v1", familyMode = true))
        for (host in ProviderPolicy.blockedHosts) {
            assertFalse(ProviderPolicy.hostAllowed("https://$host/v1", familyMode = true), host)
        }
    }

    @Test
    fun byomCustomAllowsBlockedHostWhenFamilyOff() {
        assertTrue(ProviderPolicy.hostAllowed("https://api.deepseek.com/v1", familyMode = false))
        assertTrue(ProviderPolicy.hostAllowed("https://api.moonshot.cn/v1", familyMode = false))
        assertTrue(ProviderPolicy.hostAllowed("https://dashscope.aliyuncs.com/v1", familyMode = false))
        assertNull(ProviderPolicy.familyBlockReason("https://api.deepseek.com/v1", familyMode = false))
        for (host in ProviderPolicy.blockedHosts) {
            assertTrue(ProviderPolicy.hostAllowed("https://$host/v1", familyMode = false), host)
        }
    }

    @Test
    fun deviceAndLoopbackAlwaysAllowed() {
        assertTrue(ProviderPolicy.hostAllowed("device://litert-lm", familyMode = true))
        assertTrue(ProviderPolicy.hostAllowed("http://127.0.0.1:11434/v1", familyMode = true))
        assertTrue(ProviderPolicy.hostAllowed("http://127.5.5.5:8000/v1", familyMode = true))
        assertTrue(ProviderPolicy.hostAllowed("http://localhost:11434/v1", familyMode = true))
        assertTrue(ProviderPolicy.hostAllowed("http://LOCALHOST/v1", familyMode = true))
        assertTrue(ProviderPolicy.hostAllowed("http://[::1]:8000/v1", familyMode = true))
        assertNull(ProviderPolicy.familyBlockReason("device://litert-lm", familyMode = true))
        assertNull(ProviderPolicy.familyBlockReason("http://127.0.0.1:11434/v1", familyMode = true))
    }

    @Test
    fun classificationDoesNotResolveDns() {
        // Names are never resolved: [Endpoint.host] is a URI parse, and the blocklist
        // is an exact lowercase host match. A made-up name must not leave this process.
        assertTrue(ProviderPolicy.hostAllowed("https://this-name-must-not-be-resolved.invalid/v1", familyMode = true))
        assertFalse(ProviderPolicy.hostAllowed("https://api.deepseek.com/v1", familyMode = true))
        assertEquals("api.deepseek.com", Endpoint.host("https://api.deepseek.com/v1"))
        assertEquals("this-name-must-not-be-resolved.invalid", Endpoint.host("https://this-name-must-not-be-resolved.invalid/v1"))
        // A neighbour of a blocked host is not blocked; matching is not a DNS lookup or suffix walk.
        assertTrue(ProviderPolicy.hostAllowed("https://api.deepseek.com.evil.example/v1", familyMode = true))
    }

    @Test
    fun defaultPickerDoesNotOfferBlockedHostsOrDevice() {
        val modes = ProviderPolicy.defaultPickerModes()
        assertEquals(listOf("OpenRouter", "Local / LAN", "Custom server"), modes)
        val joined = modes.joinToString()
        for (host in ProviderPolicy.blockedHosts) {
            assertFalse(joined.contains(host, ignoreCase = true), host)
        }
        assertFalse("Device" in joined)
        assertFalse("LiteRT" in joined)
    }
}
