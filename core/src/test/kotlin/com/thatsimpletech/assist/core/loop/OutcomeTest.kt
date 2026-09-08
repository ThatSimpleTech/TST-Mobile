package com.thatsimpletech.assist.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeTest {
    /** Sealed-when exhaustiveness: adding a variant without an arm is a compile break. */
    private fun label(outcome: Outcome): String = when (outcome) {
        is Outcome.Done -> "done:${outcome.summary}"
        is Outcome.Ask -> "ask:${outcome.question}"
        is Outcome.Stopped -> "stopped:${outcome.reason}"
        is Outcome.Paused -> "paused:${outcome.reason}:${outcome.spentUsd}:${outcome.capUsd}"
    }

    @Test
    fun whenCoversPaused() {
        assertEquals("paused:cap:1.25:2.0", label(Outcome.Paused("cap", 1.25, 2.0)))
        assertEquals("done:ok", label(Outcome.Done("ok")))
        assertEquals("ask:huh", label(Outcome.Ask("huh")))
        assertEquals("stopped:no", label(Outcome.Stopped("no")))
    }
}
