package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HintTableTest {
    private fun n(id: String) = UiNode(identity = id, role = Role.BTN, bounds = Rect(0, 0, 10, 10), label = id, clickable = true)

    @Test
    fun sameControlKeepsItsNumberAcrossSteps() {
        val t = HintTable()
        val a = t.assign(listOf(n("x"), n("y"), n("z")))
        assertEquals(mapOf("x" to 1, "y" to 2, "z" to 3), a)
        val b = t.assign(listOf(n("z"), n("x"), n("y")))
        assertEquals(mapOf("z" to 3, "x" to 1, "y" to 2), b)
    }

    @Test
    fun aFreedNumberIsNotReusedOnTheVeryNextStep() {
        val t = HintTable()
        t.assign(listOf(n("x"), n("y")))
        val step2 = t.assign(listOf(n("x"), n("new")))
        // y's number 2 is quarantined: the new control must not become "2".
        assertNotEquals(2, step2["new"])
        assertEquals(3, step2["new"])
        val step3 = t.assign(listOf(n("x"), n("new"), n("later")))
        assertEquals(4, step3["later"])
        val step4 = t.assign(listOf(n("x"), n("new"), n("later"), n("final")))
        // Two steps after y disappeared, 2 is free again.
        assertEquals(2, step4["final"])
    }

    @Test
    fun resetForgetsEverything() {
        val t = HintTable()
        t.assign(listOf(n("x"), n("y")))
        t.reset()
        assertEquals(mapOf("y" to 1), t.assign(listOf(n("y"))))
    }

    @Test
    fun lookupsBothWays() {
        val t = HintTable()
        t.assign(listOf(n("x"), n("y")))
        assertEquals(2, t.hintOf("y"))
        assertEquals("y", t.identityOf(2))
        assertEquals(null, t.identityOf(9))
    }
}
