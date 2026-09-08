package com.thatsimpletech.assist.core.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelProfileTest {
    @Test
    fun emptyProfileIsZerosNotNaN() {
        val p = ModelProfile.empty()
        assertEquals("", p.baseUrl)
        assertEquals("", p.slug)
        assertEquals(0.0, p.parseRate)
        assertEquals(0.0, p.repairRate)
        assertEquals(0.0, p.loopRate)
        assertEquals(0.0, p.letterDelta)
        assertEquals(0L, p.latencyMsP50)
        assertEquals(0.0, p.validatorAgreement)
        assertEquals(0.0, p.suitePassRate)
        assertEquals(0, p.n)
        for (v in listOf(p.parseRate, p.repairRate, p.loopRate, p.letterDelta, p.validatorAgreement, p.suitePassRate)) {
            assertTrue(v.isFinite(), "$v")
            assertFalse(v.isNaN(), "$v")
        }
        val zeroDen = ModelSuite.reportOf("u", "s", emptyList(), emptyList()).profile
        assertEquals(0.0, zeroDen.parseRate)
        assertFalse(zeroDen.parseRate.isNaN())
        assertEquals(0, zeroDen.n)
        assertEquals(0L, zeroDen.latencyMsP50)
    }

    @Test
    fun jsonRoundTrip() {
        val original = ModelProfile(
            baseUrl = "https://openrouter.ai/api/v1",
            slug = "moonshotai/kimi-k3",
            parseRate = 0.9,
            repairRate = 0.1,
            loopRate = 0.05,
            letterDelta = -0.02,
            latencyMsP50 = 420L,
            validatorAgreement = 0.8,
            suitePassRate = 0.75,
            n = 12,
        )
        val file = File.createTempFile("models", ".json").apply { deleteOnExit() }
        ModelProfiles.save(file, listOf(original))
        val loaded = ModelProfiles.load(file)
        assertEquals(listOf(original), loaded)

        val nan = original.copy(parseRate = Double.NaN, letterDelta = Double.POSITIVE_INFINITY)
        ModelProfiles.upsert(file, nan)
        val after = ModelProfiles.load(file).single()
        assertEquals(0.0, after.parseRate)
        assertEquals(0.0, after.letterDelta)
        assertFalse(after.parseRate.isNaN())
        assertTrue(after.parseRate.isFinite())
    }

    @Test
    fun missingFileIsEmptyList() {
        val missing = File(File.createTempFile("models", ".json").apply { delete() }.path)
        assertEquals(emptyList(), ModelProfiles.load(missing))
    }

    @Test
    fun hintProfileWrapsCodec() {
        assertEquals(HintProfile.NUMERIC, HintProfile.parse(""))
        assertEquals(HintProfile.NUMERIC, HintProfile.parse("numeric"))
        assertEquals(HintProfile.LETTERS, HintProfile.parse("letters"))
        assertEquals("numeric", HintProfile.NUMERIC.word)
        assertEquals("letters", HintProfile.LETTERS.codec.word)
        assertEquals(HintProfile.LETTERS, HintProfile.of(HintProfile.LETTERS.codec))
    }
}
