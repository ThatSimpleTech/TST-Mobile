package com.thatsimpletech.assist.core.meter

import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.TierName
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostTrackerTest {
    private val cfg = AssistConfig.loadDefault()
    private val preset = cfg.presets.getValue("tst-default")
    private val brain = preset.brain // 2.80 / 14.00 / 0.30
    private val worker = preset.worker // 0.07 / 0.17 / 0.07

    /** A clock the test moves by hand; the tracker's day boundary is in UTC so the test is the same on every box. */
    private class FakeClock(var now: Long) {
        fun tracker() = CostTracker(clock = { now }, zone = ZoneOffset.UTC)
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) <= 1e-9 + 1e-6 * abs(expected), "expected $expected, got $actual")

    @Test
    fun beginTurnResetsTheTurnButNotTheSession() {
        val t = CostTracker()
        t.beginTurn()
        // 1000 * 2.80/1e6 + 500 * 14.00/1e6 = 0.009800
        assertClose(0.009800, t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain))
        assertClose(0.009800, t.turnCost())
        t.beginTurn()
        assertEquals(0.0, t.turnCost())
        assertClose(0.009800, t.sessionCost())
    }

    @Test
    fun turnCostSumsEveryCallOfTheTurn() {
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain) // 0.009800
        t.record(TierName.BRAIN, "m", Usage(500, 0, 200), brain) // 500 * 2.80/1e6 + 200 * 14.00/1e6 = 0.001400 + 0.002800 = 0.004200
        assertClose(0.014000, t.turnCost())
        assertEquals(2200, t.turnTokens())
    }

    @Test
    fun sessionCostAndCostByTierSpanTurns() {
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain) // 0.009800
        t.beginTurn()
        t.record(TierName.WORKER, "w", Usage(5000, 0, 2000), worker) // 0.000350 + 0.000340 = 0.000690
        assertClose(0.010490, t.sessionCost())
        assertEquals(8500, t.sessionTokens())
        val byTier = t.costByTier()
        assertEquals(setOf("brain", "worker"), byTier.keys)
        assertClose(0.009800, byTier.getValue("brain"))
        assertClose(0.000690, byTier.getValue("worker"))
    }

    @Test
    fun classifierCallsAreLedgeredApart() {
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain) // 0.009800
        val c = t.record(TierName.WORKER, "w", Usage(5000, 0, 2000), worker, isClassifier = true) // 0.000690
        assertClose(0.000690, c)
        assertClose(0.000690, t.classifierCost())
        assertClose(0.009800, t.turnCost())
        assertClose(0.009800, t.sessionCost())
        assertEquals(setOf("brain"), t.costByTier().keys)
        assertEquals(1, t.calls.size)
    }

    @Test
    fun snapshotCarriesEveryFigure() {
        val clock = FakeClock(1_800_000_000_000L)
        val t = clock.tracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain)
        t.record(TierName.WORKER, "w", Usage(5000, 0, 2000), worker, isClassifier = true)
        val s = t.snapshot()
        assertClose(0.009800, s.turnCost)
        assertClose(0.009800, s.sessionCost)
        assertClose(0.009800, s.dayCost)
        assertClose(0.000690, s.classifierCost)
        assertEquals(setOf("brain"), s.costByTier.keys)
    }

    @Test
    fun devicePresetIsExactlyZeroOnEveryAccessor() {
        val device = cfg.presets.getValue("device")
        val t = CostTracker()
        t.beginTurn()
        // Real token counts, silent cache, and still $0.00: free is tracked, not untracked.
        assertEquals(0.0, t.record(TierName.BRAIN, "gemma-4-e4b", Usage(1955, null, 8), device.brain))
        assertEquals(0.0, t.record(TierName.WORKER, "gemma-4-e4b", Usage(40_000, 0, 4000), device.worker))
        assertEquals(0.0, t.record(TierName.VALIDATOR, "gemma-4-e4b", Usage(3000, 3000, 100), device.validator, isClassifier = true))
        assertEquals(0.0, t.turnCost())
        assertEquals(0.0, t.sessionCost())
        assertEquals(0.0, t.dayCost())
        assertEquals(0.0, t.classifierCost())
        assertEquals(mapOf("brain" to 0.0, "worker" to 0.0), t.costByTier())
        val s = t.snapshot()
        assertEquals(CostUpdate(0.0, 0.0, 0.0, 0.0, mapOf("brain" to 0.0, "worker" to 0.0)), s)
        assertEquals(45_963, t.sessionTokens())
        assertFalse(t.capExceeded(0.01))
    }

    @Test
    fun capPausesAtOrAboveTheCapAndNullIsNoCap() {
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain) // session 0.009800
        assertFalse(t.capExceeded(0.01))
        assertTrue(t.capExceeded(0.009800), "exactly the cap pauses (>=)")
        t.record(TierName.BRAIN, "m", Usage(500, 0, 200), brain) // session 0.014000
        assertTrue(t.capExceeded(0.01))
        assertFalse(t.capExceeded(null))
        assertFalse(SpendCap.exceeded(999.0, null))
        assertTrue(SpendCap.exceeded(5.0, 5.0))
        assertFalse(SpendCap.exceeded(4.999999, 5.0))
    }

    @Test
    fun classifierSpendDoesNotCountTowardTheCap() {
        // loop.py checks session_cost(), and the classifier ledger is not in it.
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.WORKER, "w", Usage(5000, 0, 2000), worker, isClassifier = true) // 0.000690
        assertFalse(t.capExceeded(0.0005))
    }

    @Test
    fun dayCostRollsOverAtLocalMidnightWhileSessionCostDoesNot() {
        // 2026-09-07T23:30:00Z
        val clock = FakeClock(1_788_823_800_000L)
        val t = clock.tracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain) // 0.009800
        assertClose(0.009800, t.dayCost())
        assertEquals(1500, t.dayTokens())

        clock.now += 60L * 60L * 1000L // 2026-09-08T00:30:00Z
        assertEquals(0.0, t.dayCost())
        assertEquals(0, t.dayTokens())
        assertClose(0.009800, t.sessionCost())

        t.record(TierName.WORKER, "w", Usage(5000, 0, 2000), worker) // 0.000690, on the new day
        assertClose(0.000690, t.dayCost())
        assertClose(0.010490, t.sessionCost())
        assertClose(0.000690, t.snapshot().dayCost)
    }

    @Test
    fun theTrackerNeverInventsACacheFigure() {
        val t = CostTracker()
        assertFalse(t.cacheObserved)
        assertEquals(null, t.lastCachedPromptTokens)

        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1955, null, 8), brain)
        assertTrue(t.cacheObserved)
        assertEquals(null, t.lastCachedPromptTokens)
        assertFalse(t.turnCacheReported())
        assertEquals(0.0, t.turnCacheRatio())

        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1955, 0, 8), brain)
        assertEquals(0, t.lastCachedPromptTokens)
        assertTrue(t.turnCacheReported())
        assertEquals(0.0, t.turnCacheRatio())
    }

    @Test
    fun aSilentTurnAfterAHitDoesNotInheritTheRatioOrReported() {
        val t = CostTracker()
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 800, 10), brain)
        assertClose(0.8, t.turnCacheRatio())
        assertTrue(t.turnCacheReported())

        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1955, null, 8), brain)
        assertEquals(0.0, t.turnCacheRatio())
        assertFalse(t.turnCacheReported())
        assertEquals(null, t.lastCachedPromptTokens)
    }

    @Test
    fun anEmptyTurnReportsZeroNotBlank() {
        val t = CostTracker()
        t.beginTurn()
        assertEquals(0.0, t.turnCacheRatio())
        assertEquals(0.0, t.turnCost())
    }

    @Test
    fun listenersSeeEveryCallIncludingClassifierOnes() {
        val t = CostTracker()
        val seen = ArrayList<CallRecord>()
        t.addListener { seen += it }
        t.beginTurn()
        t.record(TierName.BRAIN, "m", Usage(1000, 0, 500), brain)
        t.record(TierName.WORKER, "w", Usage(10, 0, 1), worker, isClassifier = true)
        assertEquals(listOf(false, true), seen.map { it.classifier })
        assertEquals("m", seen[0].model)
        assertEquals(0, seen[0].cachedPromptTokens)
    }
}
