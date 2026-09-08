package com.thatsimpletech.assist.core.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalAskTest {
    private val goal = "send Jerry a message about who you are and how you're messaging him"

    @Test
    fun restatesTheGoal() {
        assertTrue(
            GoalAsk.restates(
                "Should I send Jerry a message explaining who I am and how I'm messaging him?",
                goal,
            ),
        )
    }

    @Test
    fun missingFactIsNotARestatement() {
        assertFalse(GoalAsk.restates("Which Jerry — work or personal?", goal))
        assertFalse(GoalAsk.restates("Can you unlock the phone?", goal))
        assertFalse(GoalAsk.restates("You are in Gmail now, continue?", "reply to Maria confirming 7pm"))
    }
}
