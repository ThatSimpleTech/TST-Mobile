package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.PolicyPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardsTest {
    @Test
    fun loopDetectorFiresOnTheThirdIdenticalActionInARow() {
        val d = LoopDetector(3)
        assertFalse(d.record("tap 4"))
        assertFalse(d.record("tap 4"))
        assertTrue(d.record("tap 4"))
        assertEquals(3, d.streak)
    }

    @Test
    fun aDifferentActionBreaksTheStreak() {
        val d = LoopDetector(3)
        assertFalse(d.record("tap 4"))
        assertFalse(d.record("tap 4"))
        assertFalse(d.record("tap 5"))
        assertEquals(1, d.streak)
        assertFalse(d.record("tap 4"))
        assertFalse(d.record("tap 4"))
        assertTrue(d.record("tap 4"))
    }

    @Test
    fun stepBudgetAllowsExactlyLimitSteps() {
        val b = StepBudget(12)
        assertTrue(b.allows(1))
        assertTrue(b.allows(12))
        assertFalse(b.allows(13))
    }

    @Test
    fun defaultsComeFromThePolicyPack() {
        val pack = PolicyPack.loadDefault()
        assertEquals(36, StepBudget.of(pack).limit)
        assertEquals(3, LoopDetector.of(pack).limit)
    }

    @Test
    fun zeroLimitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { StepBudget(0) }
        assertFailsWith<IllegalArgumentException> { LoopDetector(0) }
    }
}
