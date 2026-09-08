package com.thatsimpletech.assist.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A home box never receives the OpenRouter key, and an OpenRouter key never goes anywhere but OpenRouter. */
class HomeCredentialTest {
    private val config = AssistConfig.loadDefault()

    @Test
    fun magicDnsNamesAreTailnet() {
        assertEquals(EndpointKind.TAILNET, Endpoint.kind("https://llm.ezer-server.ts.net/v1"))
        assertEquals(EndpointKind.TAILNET, Endpoint.kind("http://ezer-server.ts.net:4000/v1"))
        assertEquals(EndpointKind.REMOTE, Endpoint.kind("https://ts.net/v1"))
        assertEquals(EndpointKind.REMOTE, Endpoint.kind("https://evil.example/x.ts.net"))
        assertEquals(EndpointKind.REMOTE, Endpoint.kind("https://notts.net/v1"))
    }

    @Test
    fun tailnetTiersSendNoKeyUnlessBound() {
        val home = TierConfig(slug = "ezer-chat", baseUrl = "https://llm.ezer-server.ts.net/v1", inputPrice = 0.0, outputPrice = 0.0, cacheReadPrice = 0.0, contextWindow = 32768, maxOutputTokens = 4096)
        assertNull(home.credentialId)
        assertEquals("https://llm.ezer-server.ts.net/v1", config.resolveBaseUrl(home))
        val bound = home.copy(credential = "ezer")
        assertEquals("ezer", bound.credentialId)
        val literal = home.copy(baseUrl = "http://100.64.0.9:4000/v1")
        assertNull(literal.credentialId)
    }

    @Test
    fun shippedHomePresetIsKeyless() {
        val home = config.presets.getValue("home")
        for (t in home.tiers.values) assertNull(t.credentialId, "home tier ${t.baseUrl} must not carry the implicit key")
    }

    @Test
    fun anOpenRouterKeyOnlyEverGoesToOpenRouter() {
        val remote = TierConfig(slug = "x", baseUrl = "https://some-third-party.example/v1", inputPrice = 1.0, outputPrice = 1.0, cacheReadPrice = 0.0, contextWindow = 1, maxOutputTokens = 1)
        assertEquals("openrouter", remote.credentialId)
        assertEquals("https://openrouter.ai/api/v1", config.resolveBaseUrl(remote))
        assertTrue("openrouter.ai" in config.copy(preset = "tst-default").allowedHosts)
    }

    @Test
    fun aKeyOverPlainHttpToAThirdPartyIsRefusedByValidation() {
        val bad = AssistConfig(
            preset = "p",
            presets = mapOf("p" to run {
                val t = TierConfig(slug = "m", baseUrl = "http://api.example.com/v1", inputPrice = 0.0, outputPrice = 0.0, cacheReadPrice = 0.0, contextWindow = 1, maxOutputTokens = 1, credential = "k")
                Preset(t, t, t)
            }),
            credentials = mapOf("k" to CredentialConfig(name = "K")),
        )
        val problems = bad.validate()
        assertTrue(problems.any { "plain http" in it }, problems.toString())
    }
}
