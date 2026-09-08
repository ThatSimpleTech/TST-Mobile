package com.thatsimpletech.assist.core.meter

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One recorded call: the port of `cost.CallRecord`. [cachedPromptTokens] is the provider's own figure, never inferred. */
data class CallRecord(
    val tier: TierName,
    val model: String,
    val promptTokens: Int,
    val cachedPromptTokens: Int?,
    val completionTokens: Int,
    val cost: Cost,
    /** Epoch millis from the tracker's clock. */
    val timestamp: Long,
    /** A decision-classifier call, kept off the turn and session ledgers (desktop TD-703). */
    val classifier: Boolean = false,
) {
    val tokens: Int get() = promptTokens + completionTokens
}

/**
 * The meter's snapshot: what the status chip and the `cost_update` wire event carry.
 * Serial names are tstd's `CostUpdate` fields; its `total_cost` is the day's spend.
 */
@Serializable
data class CostUpdate(
    @SerialName("turn_cost") val turnCost: Double,
    @SerialName("session_cost") val sessionCost: Double,
    @SerialName("total_cost") val dayCost: Double,
    @SerialName("classifier_cost") val classifierCost: Double = 0.0,
    /** Session spend per tier, tiers with a call only. */
    @SerialName("cost_by_tier") val costByTier: Map<String, Double> = emptyMap(),
)

/** The spend cap (plan §5): the session pauses once its spend reaches the cap. Null is no cap. */
object SpendCap {
    /** `>=`, as `loop.py` checks it: a session that has spent exactly the cap is paused. */
    fun exceeded(sessionCost: Double, capUsd: Double?): Boolean = capUsd != null && sessionCost >= capUsd
}

/**
 * Per-session spend, the port of `cost.CostTracker` (plan §5). Pure: the clock is injected so
 * the day boundary is testable, and pricing arrives with each call so a preset switch mid-session
 * needs no rebinding. Not thread-safe; the loop owns it.
 */
class CostTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val all = ArrayList<CallRecord>()
    private var turn = ArrayList<CallRecord>()
    private val classifierCalls = ArrayList<CallRecord>()
    private val listeners = ArrayList<(CallRecord) -> Unit>()
    /** Prior sessions' spend for the local day this tracker was seeded; 0 if never seeded. */
    private var seededDayCost: Double = 0.0
    private var seededDay: LocalDate? = null

    /** Every main-loop call this session, in order. Classifier calls are in [classifierCalls]. */
    val calls: List<CallRecord> get() = all

    /** The audit trail's feed: one listener here sees every model call without the loop knowing the store exists. */
    fun addListener(listener: (CallRecord) -> Unit) {
        listeners += listener
    }

    /** Once per turn, never per provider call: a turn with a tool call spends several calls and all of them are the turn's. */
    fun beginTurn() {
        turn = ArrayList()
    }

    /**
     * Records one call and returns its dollar cost. A classifier call is priced the same way
     * but ledgered apart, so the ambiguity fallback shows on its own line rather than folded
     * into main-loop spend.
     */
    fun record(tier: TierName, model: String, usage: Usage, prices: TierConfig, isClassifier: Boolean = false): Double {
        val cost = Cost.of(usage, prices)
        val rec = CallRecord(
            tier = tier,
            model = model,
            promptTokens = usage.promptTokens,
            cachedPromptTokens = usage.cachedPromptTokens,
            completionTokens = usage.completionTokens,
            cost = cost,
            timestamp = clock(),
            classifier = isClassifier,
        )
        if (isClassifier) {
            classifierCalls += rec
        } else {
            all += rec
            turn += rec
        }
        for (l in listeners) l(rec)
        return cost.total
    }

    fun turnCost(): Double = sum(turn)
    fun turnTokens(): Int = turn.sumOf { it.tokens }
    fun sessionCost(): Double = sum(all)
    fun sessionTokens(): Int = all.sumOf { it.tokens }
    fun classifierCost(): Double = sum(classifierCalls)

    /**
     * Hydrate [dayCost] from prior sessions (TM-025). Call before the first [record] of this
     * session so this session is not double-counted. The seed applies only on the local date
     * it was taken; after midnight it drops, same as in-session rows from yesterday.
     */
    fun seedDayCost(usd: Double) {
        seededDayCost = Cost.round6(usd)
        seededDay = dateOf(clock())
    }

    /** Spend on calls whose local date, in this tracker's zone, is the clock's today. */
    fun dayCost(): Double {
        val today = dateOf(clock())
        val seed = if (seededDay == today) seededDayCost else 0.0
        return Cost.round6(seed + all.filter { dateOf(it.timestamp) == today }.sumOf { it.cost.total })
    }

    fun dayTokens(): Int {
        val today = dateOf(clock())
        return all.filter { dateOf(it.timestamp) == today }.sumOf { it.tokens }
    }

    /** Session spend per tier, keyed by the YAML tier word, tiers that spent a call only. */
    fun costByTier(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (c in all) out[c.tier.word] = (out[c.tier.word] ?: 0.0) + c.cost.total
        return out.mapValues { Cost.round6(it.value) }
    }

    /** Whether any main-loop call has come back yet; tells "no data" from "the provider said nothing about cache". */
    val cacheObserved: Boolean get() = all.isNotEmpty()

    /** The provider's cached figure on the last call, null when it gave none. Never 0 on the strength of a missing field. */
    val lastCachedPromptTokens: Int? get() = all.lastOrNull()?.cachedPromptTokens

    /** Whether any call this turn carried a cache figure. Turn-scoped so a silent turn after a hit does not inherit "reported". */
    fun turnCacheReported(): Boolean = turn.any { it.cachedPromptTokens != null }

    /** Reported cache reads this turn; silent calls contribute nothing. */
    fun turnCachedTokens(): Int = turn.sumOf { Cost.billableCachedTokens(it.cachedPromptTokens) }

    /** Share of this turn's prompt the provider reported as reused. 0.0 when nothing was reported and when nothing was spent. */
    fun turnCacheRatio(): Double {
        val total = turn.sumOf { it.promptTokens }
        return if (total > 0) turnCachedTokens().toDouble() / total else 0.0
    }

    /** True once session spend has reached [capUsd]; the loop pauses (plan §5). Null is no cap. */
    fun capExceeded(capUsd: Double?): Boolean = SpendCap.exceeded(sessionCost(), capUsd)

    fun snapshot(): CostUpdate = CostUpdate(
        turnCost = turnCost(),
        sessionCost = sessionCost(),
        dayCost = dayCost(),
        classifierCost = classifierCost(),
        costByTier = costByTier(),
    )

    private fun sum(records: List<CallRecord>): Double = Cost.round6(records.sumOf { it.cost.total })

    private fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
