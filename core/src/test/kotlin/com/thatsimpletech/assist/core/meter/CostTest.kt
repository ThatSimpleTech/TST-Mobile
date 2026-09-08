package com.thatsimpletech.assist.core.meter

import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.TierConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hand-computed dollar figures for the shipped tst-default prices: the port of tests/test_cost.py. */
class CostTest {
    // Read from the shipped file, so a price edit there is caught here rather than hidden by a copy.
    private val preset = AssistConfig.loadDefault().presets.getValue("tst-default")
    private val brain: TierConfig = preset.brain // 2.80 in, 14.00 out, 0.30 cache
    private val worker: TierConfig = preset.worker // 0.07 in, 0.17 out, 0.07 cache
    private val validator: TierConfig = preset.validator // 0.44 in, 0.87 out, 0.44 cache

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) <= 1e-9 + 1e-6 * abs(expected), "expected $expected, got $actual")

    @Test
    fun brainUncached() {
        // 1000 * 2.80/1e6 + 500 * 14.00/1e6 = 0.002800 + 0.007000 = 0.009800
        assertClose(0.009800, Cost.of(Usage(1000, 0, 500), brain).total)
    }

    @Test
    fun brainFullyCached() {
        // 0 * 2.80/1e6 + 1000 * 0.30/1e6 + 500 * 14.00/1e6 = 0 + 0.000300 + 0.007000 = 0.007300
        assertClose(0.007300, Cost.of(Usage(1000, 1000, 500), brain).total)
    }

    @Test
    fun brainMixedWithBreakdown() {
        // uncached 1500: 1500 * 2.80/1e6 = 0.004200; 500 * 0.30/1e6 = 0.000150; 800 * 14.00/1e6 = 0.011200; total 0.015550
        val c = Cost.of(Usage(2000, 500, 800), brain)
        assertEquals(1500, c.uncachedPromptTokens)
        assertEquals(500, c.billableCachedTokens)
        assertClose(0.004200, c.promptCost)
        assertClose(0.000150, c.cachedCost)
        assertClose(0.011200, c.completionCost)
        assertClose(0.015550, c.total)
    }

    @Test
    fun workerUncached() {
        // 5000 * 0.07/1e6 + 2000 * 0.17/1e6 = 0.000350 + 0.000340 = 0.000690
        assertClose(0.000690, Cost.of(Usage(5000, 0, 2000), worker).total)
    }

    @Test
    fun workerMixed() {
        // 5000 * 0.07/1e6 + 5000 * 0.07/1e6 + 4000 * 0.17/1e6 = 0.000350 + 0.000350 + 0.000680 = 0.001380
        assertClose(0.001380, Cost.of(Usage(10000, 5000, 4000), worker).total)
    }

    @Test
    fun validatorUncached() {
        // 3000 * 0.44/1e6 + 1000 * 0.87/1e6 = 0.001320 + 0.000870 = 0.002190
        assertClose(0.002190, Cost.of(Usage(3000, 0, 1000), validator).total)
    }

    @Test
    fun validatorMixed() {
        // 2000 * 0.44/1e6 + 2000 * 0.44/1e6 + 1500 * 0.87/1e6 = 0.000880 + 0.000880 + 0.001305 = 0.003065
        assertClose(0.003065, Cost.of(Usage(4000, 2000, 1500), validator).total)
    }

    @Test
    fun zeroTokensIsExactlyZero() {
        assertEquals(0.0, Cost.of(Usage(0, 0, 0), brain).total)
    }

    @Test
    fun moreCachedThanPromptClampsUncachedToZero() {
        // uncached 0; 200 * 0.30/1e6 = 0.000060; 50 * 14.00/1e6 = 0.000700; total 0.000760
        val c = Cost.of(Usage(100, 200, 50), brain)
        assertEquals(0, c.uncachedPromptTokens)
        assertClose(0.000760, c.total)
    }

    @Test
    fun aSilentProviderBillsTheWholePromptAtInputPrice() {
        // No cache figure: 1_000_000 * 2.80/1e6 = 2.80, none of it at the 0.30 cache rate.
        val silent = Usage(promptTokens = 1_000_000, cachedPromptTokens = null, completionTokens = 0)
        val c = Cost.of(silent, brain)
        assertEquals(0, c.billableCachedTokens)
        assertEquals(1_000_000, c.uncachedPromptTokens)
        assertClose(2.80, c.total)
        assertEquals(0.0, c.cachedCost)
    }

    @Test
    fun aReportedHitIsPricedAtTheCacheRate() {
        // 1_000_000 * 0.30/1e6 = 0.30
        val hit = Usage(promptTokens = 1_000_000, cachedPromptTokens = 1_000_000, completionTokens = 0)
        assertClose(0.30, Cost.of(hit, brain).total)
    }

    @Test
    fun aReportedZeroCostsTheSameAsSilenceButIsStillAReport() {
        val silent = Usage(1000, null, 0)
        val zero = Usage(1000, 0, 0)
        assertEquals(Cost.of(silent, brain).total, Cost.of(zero, brain).total)
        assertEquals(false, silent.cacheReported)
        assertEquals(true, zero.cacheReported)
    }

    @Test
    fun billableCachedTokensIsExplicitAboutBothInputs() {
        assertEquals(0, Cost.billableCachedTokens(null))
        assertEquals(0, Cost.billableCachedTokens(0))
        assertEquals(1200, Cost.billableCachedTokens(1200))
    }

    @Test
    fun usageParsesTheOllamaShapeAsUnknownAndTheReportingShapeAsZero() {
        // The usage object Ollama returned on 2026-08-17 (test_cache_honesty.py), verbatim: no cache key at all.
        val ollama = Json.parseToJsonElement("""{"prompt_tokens": 1955, "completion_tokens": 8, "total_tokens": 1963}""").jsonObject
        val u = Usage.fromApi(ollama)
        assertEquals(1955, u.promptTokens)
        assertEquals(8, u.completionTokens)
        assertEquals(null, u.cachedPromptTokens)

        val reporting = Json.parseToJsonElement(
            """{"prompt_tokens": 1955, "completion_tokens": 8, "total_tokens": 1963, "prompt_tokens_details": {"cached_tokens": 0}}""",
        ).jsonObject
        assertEquals(0, Usage.fromApi(reporting).cachedPromptTokens)

        val hit = Json.parseToJsonElement("""{"prompt_tokens": 1955, "completion_tokens": 8, "prompt_tokens_details": {"cached_tokens": 1200}}""").jsonObject
        assertEquals(1200, Usage.fromApi(hit).cachedPromptTokens)

        val malformed = Json.parseToJsonElement("""{"prompt_tokens": 1955, "completion_tokens": 8, "prompt_tokens_details": "unexpected"}""").jsonObject
        assertEquals(null, Usage.fromApi(malformed).cachedPromptTokens)
        assertEquals(null, Usage.fromApi(null).cachedPromptTokens)
    }
}
