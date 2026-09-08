package com.thatsimpletech.assist.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssistConfigTest {
    private fun tierYaml(baseUrl: String, slug: String? = "m", extra: String = "") = """
        |      ${if (slug != null) "slug: $slug" else "# no slug"}
        |      base_url: $baseUrl
        |      input_price: 1.0
        |      output_price: 2.0
        |      cache_read_price: 0.5
        |      context_window: 1000
        |      max_output_tokens: 100
        |$extra""".trimMargin()

    private fun oneTierConfig(baseUrl: String, slug: String? = "m", extra: String = "", top: String = "") = """
        |preset: p
        |$top
        |presets:
        |  p:
        |    brain:
        |${tierYaml(baseUrl, slug, extra)}
        |    worker:
        |${tierYaml("http://127.0.0.1:8000/v1", null)}
        |    validator:
        |${tierYaml("http://127.0.0.1:8000/v1", null)}
        |""".trimMargin()

    @Test
    fun shippedFileParsesAndValidates() {
        val cfg = AssistConfig.loadDefault()
        assertEquals(emptyList(), cfg.validate())
        assertEquals("tst-default", cfg.preset)
    }

    @Test
    fun shippedFileHasTheSixPresetsWithThreeTiersEach() {
        val cfg = AssistConfig.loadDefault()
        assertEquals(setOf("tst-default", "budget", "local", "vllm", "device", "home"), cfg.presets.keys)
        for ((name, p) in cfg.presets) {
            assertEquals(3, p.tiers.size, name)
            for ((t, tier) in p.tiers) assertTrue(tier.baseUrl.isNotBlank(), "$name.${t.word}")
        }
    }

    @Test
    fun shippedTstDefaultMatchesTheDesktopPrices() {
        // TST-Desk core/tstd/config.yaml, tst-default: brain 2.80/14.00/0.30, worker 0.07/0.17/0.07, validator 0.44/0.87/0.44.
        val p = AssistConfig.loadDefault().presets.getValue("tst-default")
        assertEquals("moonshotai/kimi-k3", p.brain.slug)
        assertEquals(listOf(2.80, 14.00, 0.30), listOf(p.brain.inputPrice, p.brain.outputPrice, p.brain.cacheReadPrice))
        assertEquals("deepseek/deepseek-v4-flash", p.worker.slug)
        assertEquals(listOf(0.07, 0.17, 0.07), listOf(p.worker.inputPrice, p.worker.outputPrice, p.worker.cacheReadPrice))
        assertEquals("deepseek/deepseek-v4-pro", p.validator.slug)
        assertEquals(listOf(0.44, 0.87, 0.44), listOf(p.validator.inputPrice, p.validator.outputPrice, p.validator.cacheReadPrice))
        assertEquals(1_000_000, p.brain.contextWindow)
    }

    @Test
    fun deviceAndHomeAreZeroPriced() {
        val cfg = AssistConfig.loadDefault()
        for (name in listOf("device", "home")) {
            for ((t, tier) in cfg.presets.getValue(name).tiers) {
                assertTrue(tier.isFree, "$name.${t.word} is not free")
                assertEquals(0.0, tier.inputPrice)
                assertEquals(0.0, tier.outputPrice)
                assertEquals(0.0, tier.cacheReadPrice)
            }
        }
    }

    @Test
    fun devicePresetIsOnDeviceWithASlugAndNoHosts() {
        val p = AssistConfig.loadDefault().presets.getValue("device")
        for (tier in p.tiers.values) {
            assertEquals(EndpointKind.ON_DEVICE, tier.kind)
            assertEquals("gemma-4-e4b", tier.slug)
            assertEquals(null, tier.credentialId)
        }
        assertEquals(emptySet(), p.hosts)
    }

    @Test
    fun allowedHostsAreTheActivePresetsEndpointsOnly() {
        val cfg = AssistConfig.loadDefault()
        assertEquals(setOf("openrouter.ai"), cfg.allowedHosts)
        assertEquals(setOf("home.tailnet.example"), cfg.copy(preset = "home").allowedHosts)
        assertEquals(setOf("127.0.0.1"), cfg.copy(preset = "local").allowedHosts)
        assertEquals(emptySet(), cfg.copy(preset = "device").allowedHosts)
    }

    @Test
    fun aTailscaleTierWithoutASlugFailsValidation() {
        val text = oneTierConfig("http://100.64.0.1:4000/v1", slug = null)
        val e = assertFailsWith<IllegalArgumentException> { AssistConfig.parse(text) }
        assertTrue(e.message!!.contains("presets.p.brain.slug is required"), e.message)
    }

    @Test
    fun aDeviceTierWithoutASlugFailsValidation() {
        val e = assertFailsWith<IllegalArgumentException> { AssistConfig.parse(oneTierConfig("device://litert-lm", slug = null)) }
        assertTrue(e.message!!.contains("slug is required"), e.message)
    }

    @Test
    fun aLoopbackTierMayOmitItsSlug() {
        val cfg = AssistConfig.parse(oneTierConfig("http://127.0.0.1:11434/v1", slug = null))
        assertEquals(null, cfg.tier(TierName.BRAIN).slug)
        assertEquals(null, cfg.tier(TierName.BRAIN).credentialId)
    }

    @Test
    fun typosFailUnderStrictMode() {
        val text = oneTierConfig("https://openrouter.ai/api/v1", extra = "      input_prise: 3.0")
        assertFailsWith<Exception> { AssistConfig.parse(text) }
        assertFailsWith<Exception> { AssistConfig.parse(oneTierConfig("https://openrouter.ai/api/v1", top = "spend_cap: 5")) }
    }

    @Test
    fun negativePricesAndZeroWindowsFail() {
        val bad = """
            |preset: p
            |presets:
            |  p:
            |    brain:
            |      slug: m
            |      base_url: https://openrouter.ai/api/v1
            |      input_price: -1.0
            |      output_price: 2.0
            |      cache_read_price: 0.5
            |      context_window: 0
            |      max_output_tokens: 0
            |    worker:
            |${tierYaml("http://127.0.0.1:8000/v1", null)}
            |    validator:
            |${tierYaml("http://127.0.0.1:8000/v1", null)}
            |""".trimMargin()
        val e = assertFailsWith<IllegalArgumentException> { AssistConfig.parse(bad) }
        val msg = e.message!!
        assertTrue(msg.contains("input_price must be >= 0"), msg)
        assertTrue(msg.contains("context_window must be > 0"), msg)
        assertTrue(msg.contains("max_output_tokens must be > 0"), msg)
    }

    @Test
    fun activePresetMustExistAndCapMustBePositive() {
        val missing = oneTierConfig("http://127.0.0.1:8000/v1").replace("preset: p", "preset: nope")
        assertTrue(assertFailsWith<IllegalArgumentException> { AssistConfig.parse(missing) }.message!!.contains("preset 'nope'"))
        val zeroCap = oneTierConfig("http://127.0.0.1:8000/v1", top = "spend_cap_usd: 0")
        assertTrue(assertFailsWith<IllegalArgumentException> { AssistConfig.parse(zeroCap) }.message!!.contains("spend_cap_usd"))
        assertEquals(2.5, AssistConfig.parse(oneTierConfig("http://127.0.0.1:8000/v1", top = "spend_cap_usd: 2.5")).spendCapUsd)
        assertEquals(null, AssistConfig.loadDefault().spendCapUsd)
    }

    @Test
    fun anUndeclaredCredentialFailsButOpenrouterIsImplicit() {
        val bound = oneTierConfig("https://openrouter.ai/api/v1", extra = "      credential: mystery")
        assertTrue(assertFailsWith<IllegalArgumentException> { AssistConfig.parse(bound) }.message!!.contains("credential 'mystery'"))
        val implicit = AssistConfig.parse(oneTierConfig("https://openrouter.ai/api/v1"))
        assertEquals("openrouter", implicit.tier(TierName.BRAIN).credentialId)
    }

    @Test
    fun userEndpointRewiresEveryTierAndTheAllowlist() {
        val cfg = AssistConfig.loadDefault().withUserEndpoint(
            baseUrl = "http://192.168.1.10:11434/v1",
            slug = "llama3.1",
            credential = null,
            template = "local",
        )
        assertEquals("user", cfg.preset)
        assertEquals("llama3.1", cfg.tier(TierName.BRAIN).slug)
        assertEquals("llama3.1", cfg.tier(TierName.WORKER).slug)
        assertEquals("http://192.168.1.10:11434/v1", cfg.tier(TierName.BRAIN).baseUrl)
        assertEquals(setOf("192.168.1.10"), cfg.allowedHosts)
        assertEquals(EndpointKind.REMOTE, cfg.tier(TierName.BRAIN).kind)
        assertEquals(0.0, cfg.tier(TierName.BRAIN).inputPrice)
    }

    @Test
    fun userOverlayStillSingleHost() {
        val local = AssistConfig.loadDefault().withUserEndpoint(
            baseUrl = "http://192.168.1.10:11434/v1",
            slug = "llama3.1",
            credential = null,
            template = "local",
        ).copy(spendCapUsd = 2.5)
        assertEquals(setOf("192.168.1.10"), local.allowedHosts)
        assertEquals(2.5, local.spendCapUsd)

        // A BYOM overlay of a family-blocked host is still one host; the block is ProviderPolicy, not this allowlist.
        val blocked = AssistConfig.loadDefault().withUserEndpoint(
            baseUrl = "https://api.deepseek.com/v1",
            slug = "deepseek-chat",
            credential = "openrouter",
            template = "home",
        )
        assertEquals(setOf("api.deepseek.com"), blocked.allowedHosts)
        assertEquals("openrouter.ai", AssistConfig.loadDefault().allowedHosts.single())
        assertEquals("moonshotai/kimi-k3", AssistConfig.loadDefault().tier(TierName.BRAIN).slug)
    }

    @Test
    fun userCloudEndpointKeepsOpenrouterHostAndPrices() {
        val cfg = AssistConfig.loadDefault().withUserEndpoint(
            baseUrl = "https://openrouter.ai/api/v1",
            slug = "openai/gpt-4o-mini",
            credential = "openrouter",
            template = "tst-default",
        )
        assertEquals("openai/gpt-4o-mini", cfg.tier(TierName.BRAIN).slug)
        assertEquals(setOf("openrouter.ai"), cfg.allowedHosts)
        assertEquals("openrouter", cfg.tier(TierName.BRAIN).credentialId)
        assertEquals(2.80, cfg.tier(TierName.BRAIN).inputPrice)
    }
}
