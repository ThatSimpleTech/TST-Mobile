package com.thatsimpletech.assist.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-endpoint eval stats from the three-brain suite (plan §9.3 M1). Rates are 0 when
 * there is no denominator — never NaN. The agent has no verb that writes this.
 */
@Serializable
data class ModelProfile(
    @SerialName("base_url") val baseUrl: String,
    val slug: String,
    @SerialName("parse_rate") val parseRate: Double,
    @SerialName("repair_rate") val repairRate: Double,
    @SerialName("loop_rate") val loopRate: Double,
    @SerialName("letter_delta") val letterDelta: Double,
    @SerialName("latency_ms_p50") val latencyMsP50: Long,
    @SerialName("validator_agreement") val validatorAgreement: Double,
    @SerialName("suite_pass_rate") val suitePassRate: Double,
    val n: Int,
) {
    /** Replaces NaN/Inf with 0 so a bad file cannot poison the chip. */
    fun sanitized(): ModelProfile = copy(
        parseRate = parseRate.finiteOrZero(),
        repairRate = repairRate.finiteOrZero(),
        loopRate = loopRate.finiteOrZero(),
        letterDelta = letterDelta.finiteOrZero(),
        latencyMsP50 = latencyMsP50.coerceAtLeast(0L),
        validatorAgreement = validatorAgreement.finiteOrZero(),
        suitePassRate = suitePassRate.finiteOrZero(),
        n = n.coerceAtLeast(0),
    )

    companion object {
        fun empty(baseUrl: String = "", slug: String = "") = ModelProfile(
            baseUrl = baseUrl,
            slug = slug,
            parseRate = 0.0,
            repairRate = 0.0,
            loopRate = 0.0,
            letterDelta = 0.0,
            latencyMsP50 = 0L,
            validatorAgreement = 0.0,
            suitePassRate = 0.0,
            n = 0,
        )
    }
}

internal fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

internal fun ratio(num: Int, den: Int): Double = if (den <= 0) 0.0 else num.toDouble() / den
