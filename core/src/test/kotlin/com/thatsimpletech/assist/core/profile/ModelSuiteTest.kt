package com.thatsimpletech.assist.core.profile

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSuiteTest {
    @Test
    fun offlineFixturesParseOnScriptedBrain() {
        val report = ModelSuite.runOffline()
        val failed = report.cases.filter { !it.passed }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.id}: got ${it.outcome} expected ${it.expected}" })
        assertTrue(report.profile.n >= 8, "suite should have 8–12 cases, got ${report.profile.n}")
        assertEquals(1.0, report.profile.suitePassRate)
        assertTrue(report.profile.parseRate > 0.0)
        assertTrue(report.profile.repairRate > 0.0, "parse-repair fixture should count a first-repair")
        assertTrue(report.profile.loopRate > 0.0, "loop-repeat fixture should hit LoopDetector")
        assertTrue(report.profile.validatorAgreement > 0.0)
        assertEquals(0L, report.profile.latencyMsP50, "offline does not clock planner.next")
        assertEquals(ModelSuite.OFFLINE_BASE_URL, report.profile.baseUrl)
        assertEquals(ModelSuite.OFFLINE_SLUG, report.profile.slug)
        for (v in listOf(report.profile.parseRate, report.profile.repairRate, report.profile.loopRate, report.profile.letterDelta, report.profile.validatorAgreement, report.profile.suitePassRate)) {
            assertTrue(v.isFinite())
            assertFalse(v.isNaN())
        }
        assertTrue(report.cases.any { it.id == "hostile-label" && it.passed })
        assertTrue(report.cases.any { it.id == "call-dial" && it.passed })
        assertTrue(report.cases.any { it.id == "validator-fail" && it.passed })
        assertTrue("offline suite" in report.summary)
        assertTrue("% pass" in report.summary)
    }

    @Test
    fun letterAndNumericBothRun() {
        val report = ModelSuite.runOffline()
        val codecs = report.cases.map { it.codec }.toSet()
        assertTrue("numeric" in codecs, codecs.toString())
        assertTrue("letters" in codecs, codecs.toString())
        val numeric = report.cases.filter { it.codec == "numeric" }
        val letters = report.cases.filter { it.codec == "letters" }
        assertTrue(numeric.any { it.id == "type-tap" && it.passed })
        assertTrue(letters.any { it.id == "type-tap-letters" && it.passed })
        assertTrue(report.profile.letterDelta.isFinite())
        assertFalse(report.profile.letterDelta.isNaN())
        // Both clones succeed on the scripted brain, so the delta is zero, not a NaN gap.
        assertEquals(0.0, report.profile.letterDelta)
    }

    @Test
    fun liveSuiteIsOptIn() {
        assumeTrue(
            ModelSuite.liveOptIn(),
            "live suite is opt-in; pass -Dassist.liveSuite=1 (not CI)",
        )
        val live = ModelSuite.runLive()
        assertTrue(
            live.reports.isNotEmpty() || live.notes.any { ModelSuite.THIRD_BRAIN_NOT_CONFIGURED in it || "OPENROUTER" in it },
            "opt-in live suite must run a brain or explain the skip: ${live.notes}",
        )
        for (r in live.reports) {
            assertTrue(r.profile.parseRate.isFinite())
            assertFalse(r.profile.parseRate.isNaN())
            assertTrue(r.profile.n >= 8)
            assertTrue(r.profile.latencyMsP50 >= 0L)
        }
    }
}
