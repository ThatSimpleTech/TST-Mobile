package com.thatsimpletech.assist.core.meter

import com.thatsimpletech.assist.core.config.TierConfig
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The dollar cost of one call, broken down the way `cost.compute_call_details` does it.
 * Prices are per million tokens and every figure is rounded to six decimals, so the ledger
 * here matches the one tstd would write for the same call.
 */
data class Cost(
    val uncachedPromptTokens: Int,
    val billableCachedTokens: Int,
    val promptCost: Double,
    val cachedCost: Double,
    val completionCost: Double,
) {
    val total: Double get() = round6(promptCost + cachedCost + completionCost)

    companion object {
        const val PER: Double = 1_000_000.0

        /**
         * Cached tokens to price, given what the provider reported (`billable_cached_tokens`).
         * Unreported bills as zero cached: the whole prompt at the input rate. A provider that
         * stays silent about reuse is one whose invoice we cannot assume was discounted, so the
         * meter must not quietly under-state spend. This is a pricing fallback only; nothing
         * that reports cache *state* to the person may use it.
         */
        fun billableCachedTokens(cached: Int?): Int = cached ?: 0

        /** `compute_call_cost`: cache reads at the cache rate, the rest of the prompt at the input rate, completion at the output rate. */
        fun of(usage: Usage, tier: TierConfig): Cost {
            val cached = billableCachedTokens(usage.cachedPromptTokens)
            val uncached = maxOf(0, usage.promptTokens - cached)
            return Cost(
                uncachedPromptTokens = uncached,
                billableCachedTokens = cached,
                promptCost = round6(uncached * tier.inputPrice / PER),
                cachedCost = round6(cached * tier.cacheReadPrice / PER),
                completionCost = round6(usage.completionTokens * tier.outputPrice / PER),
            )
        }

        /** Six decimals, as the desktop's `round(x, 6)`. Zero stays exactly 0.0. */
        fun round6(x: Double): Double =
            if (x == 0.0) 0.0 else BigDecimal(x).setScale(6, RoundingMode.HALF_EVEN).toDouble()
    }
}
