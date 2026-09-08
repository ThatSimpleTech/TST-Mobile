package com.thatsimpletech.assist.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceCopyTest {
    @Test
    fun spokenOutcomeDropsTheSpendChip() {
        val line = "done: sent Hi to Jerry  ·  $0.0131 turn · $0.2027 session · $2.3882 day · brain $0.20"
        assertEquals("done: sent Hi to Jerry", VoiceCopy.spokenOutcome(line))
    }

    @Test
    fun spokenOutcomeKeepsALineWithNoChip() {
        assertEquals("stopped: cancelled", VoiceCopy.spokenOutcome("stopped: cancelled"))
    }

    @Test
    fun spokenOutcomeTrimsAndCapsLength() {
        val long = "done: " + "a".repeat(400)
        assertEquals(180, VoiceCopy.spokenOutcome(long).length)
    }
}
