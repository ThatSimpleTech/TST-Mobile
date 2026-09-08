package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.Usage
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ChatResult
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.ObservationFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * After `done`, compare the last screen to the goal (TM-018). Deterministic gates always
 * run; a model check runs only when [model] is configured. Per-step calls stay brain-only.
 */
class CompositeEndStateValidator(
    private val model: ModelEndStateValidator? = null,
) : EndStateValidator {
    override suspend fun validate(goal: String, last: Observation, goalApps: Set<String>): Validation {
        deterministic(last, goalApps)?.let { return it }
        return model?.validate(goal, last) ?: Validation.Pass
    }

    companion object {
        /**
         * Empty (no-a11y) observations pass: nothing to fingerprint; the model may still run.
         * A non-empty last app outside [goalApps] fails. A secure screen fails.
         */
        internal fun deterministic(last: Observation, goalApps: Set<String>): Validation.Fail? {
            if (isEmpty(last)) return null
            if (last.secure) return Validation.Fail("ended on a secure screen")
            if (goalApps.isNotEmpty() && last.app.isNotEmpty() && last.app !in goalApps) {
                return Validation.Fail("ended in ${last.app}, not a goal app")
            }
            return null
        }

        /** No-a11y intent task: blank app and no nodes. */
        internal fun isEmpty(last: Observation): Boolean = last.app.isEmpty() && last.lines.isEmpty()
    }
}

/**
 * One [ProviderClient.chat] on the validator tier, recorded `isClassifier = true` so it
 * does not sit on session spend / the cap. Reply must be `pass` or `fail "reason"`.
 */
class ModelEndStateValidator internal constructor(
    private val complete: suspend (List<ChatMessage>) -> ChatResult,
    private val meter: CostTracker,
    private val prices: TierConfig,
    private val model: String,
) {
    constructor(client: ProviderClient, meter: CostTracker, prices: TierConfig) : this(
        complete = { messages -> client.chat(messages, MAX_TOKENS, 0.0) },
        meter = meter,
        prices = prices,
        model = client.model,
    )

    suspend fun validate(goal: String, last: Observation): Validation {
        val result = try {
            complete(messages(goal, last))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Validation.Fail("validator call failed")
        }
        meter.record(
            TierName.VALIDATOR, model,
            Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
            prices,
            isClassifier = true,
        )
        return parse(result.text)
    }

    companion object {
        const val SYSTEM = "Reply `pass` or `fail \"reason\"`."
        const val MAX_TOKENS = 40
        const val UNPARSEABLE = "unparseable validator reply"

        fun parse(raw: String): Validation {
            val parser = ActionParser()
            val line = parser.firstLine(raw) ?: return Validation.Fail(UNPARSEABLE)
            val tokens = try {
                parser.tokenize(line)
            } catch (_: IllegalArgumentException) {
                return Validation.Fail(UNPARSEABLE)
            }
            if (tokens.size == 1 && !tokens[0].quoted && tokens[0].text.equals("pass", ignoreCase = true)) {
                return Validation.Pass
            }
            if (tokens.size == 2 &&
                !tokens[0].quoted && tokens[0].text.equals("fail", ignoreCase = true) &&
                tokens[1].quoted && tokens[1].text.isNotBlank()
            ) {
                return Validation.Fail(tokens[1].text)
            }
            return Validation.Fail(UNPARSEABLE)
        }

        internal fun messages(goal: String, last: Observation): List<ChatMessage> {
            val user = buildString {
                append("Goal: ").append(goal).append('\n')
                append(ObservationFormatter.format(last)).append('\n')
                append(SYSTEM)
            }
            return listOf(ChatMessage("system", SYSTEM), ChatMessage("user", user))
        }
    }
}
