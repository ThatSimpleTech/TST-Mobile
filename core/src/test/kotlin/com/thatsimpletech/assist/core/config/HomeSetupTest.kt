package com.thatsimpletech.assist.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pointing the app at a home box: the config that comes out must validate and round-trip. */
class HomeSetupTest {
    @Test
    fun pointingHomeAtALiteLlmBoxWithAKey() {
        val cfg = AssistConfig.loadDefault().withHome("https://llm.ezer-server.ts.net/v1", "ezer-chat", "ezer")
        assertEquals(emptyList(), cfg.validate())
        assertEquals("home", cfg.preset)
        val brain = cfg.tier(TierName.BRAIN)
        assertEquals("ezer-chat", brain.slug)
        assertEquals("ezer", brain.credentialId)
        assertEquals("https://llm.ezer-server.ts.net/v1", cfg.resolveBaseUrl(brain))
        assertEquals(setOf("llm.ezer-server.ts.net"), cfg.allowedHosts)
        assertTrue(brain.isFree)
        // Round trip through YAML: what the app writes is what it reads back.
        val again = AssistConfig.parse(cfg.toYaml())
        assertEquals(cfg, again)
    }

    @Test
    fun pointingHomeAtAKeylessBox() {
        val cfg = AssistConfig.loadDefault().withHome("http://100.64.0.9:8000/v1", "qwen", null)
        assertEquals(emptyList(), cfg.validate())
        assertNull(cfg.tier(TierName.BRAIN).credentialId)
        assertEquals(setOf("100.64.0.9"), cfg.allowedHosts)
    }

    @Test
    fun aThirdPartyHostOverPlainHttpWithAKeyIsRefused() {
        val cfg = AssistConfig.loadDefault().withHome("http://api.example.com/v1", "m", "k")
        assertTrue(cfg.validate().any { "plain http" in it })
    }
}
