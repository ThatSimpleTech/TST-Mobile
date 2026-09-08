package com.thatsimpletech.assist.core.meter

import java.util.Locale

/**
 * One-line spend chip for the overlay and the persistent task notification (plan §5).
 * Day spend is display-only; the session cap is what pauses (TM-017, TM-025).
 */
object MeterText {
    /**
     * Notification-safe single line: turn, session, day, by tier, remaining.
     * [paused] prefixes `PAUSED at $cap cap` and drops turn / tier / remaining.
     */
    fun chip(update: CostUpdate, capUsd: Double?, paused: Boolean = false): String {
        if (paused) {
            val cap = capUsd ?: 0.0
            return "PAUSED at ${usd(cap, 2)} cap · ${usd(update.sessionCost, 2)} session · ${usd(update.dayCost, 2)} day"
        }
        val parts = ArrayList<String>(5)
        parts += "${usd(update.turnCost, 4)} turn"
        parts += "${usd(update.sessionCost, 4)} session"
        parts += "${usd(update.dayCost, 4)} day"
        for ((tier, cost) in update.costByTier) {
            parts += "$tier ${usd(cost, 2)}"
        }
        if (capUsd != null) {
            parts += "${usd(capUsd - update.sessionCost, 2)} left"
        }
        return parts.joinToString(" · ")
    }

    private fun usd(n: Double, decimals: Int): String {
        val body = "%.${decimals}f".format(Locale.US, kotlin.math.abs(n))
        return if (n < 0.0) "-\$$body" else "\$$body"
    }
}
