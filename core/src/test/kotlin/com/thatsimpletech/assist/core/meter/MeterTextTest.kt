package com.thatsimpletech.assist.core.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeterTextTest {
    private val sample = CostUpdate(
        turnCost = 0.0123,
        sessionCost = 0.4500,
        dayCost = 1.2000,
        classifierCost = 0.0,
        costByTier = mapOf("brain" to 0.40),
    )

    @Test
    fun includesTurnSessionDayAndTier() {
        val text = MeterText.chip(sample, capUsd = 0.50)
        assertTrue("\$0.0123 turn" in text, text)
        assertTrue("\$0.4500 session" in text, text)
        assertTrue("\$1.2000 day" in text, text)
        assertTrue("brain \$0.40" in text, text)
        assertEquals(
            "\$0.0123 turn · \$0.4500 session · \$1.2000 day · brain \$0.40 · \$0.05 left",
            text,
        )
    }

    @Test
    fun remainingIsCapMinusSession() {
        assertEquals(
            "\$0.05 left",
            MeterText.chip(sample, capUsd = 0.50).substringAfterLast(" · "),
        )
        val over = sample.copy(sessionCost = 0.60)
        assertEquals(
            "\$0.40 left",
            MeterText.chip(over, capUsd = 1.00).substringAfterLast(" · "),
        )
        assertTrue(MeterText.chip(over, capUsd = 0.50).endsWith("-\$0.10 left"))
    }

    @Test
    fun nullCapOmitsRemaining() {
        val text = MeterText.chip(sample, capUsd = null)
        assertFalse("left" in text, text)
        assertEquals(
            "\$0.0123 turn · \$0.4500 session · \$1.2000 day · brain \$0.40",
            text,
        )
    }

    @Test
    fun pausedPrefix() {
        val atCap = sample.copy(sessionCost = 0.50, dayCost = 1.20)
        assertEquals(
            "PAUSED at \$0.50 cap · \$0.50 session · \$1.20 day",
            MeterText.chip(atCap, capUsd = 0.50, paused = true),
        )
        assertFalse("turn" in MeterText.chip(atCap, capUsd = 0.50, paused = true))
        assertFalse("left" in MeterText.chip(atCap, capUsd = 0.50, paused = true))
        assertFalse("brain" in MeterText.chip(atCap, capUsd = 0.50, paused = true))
    }
}
