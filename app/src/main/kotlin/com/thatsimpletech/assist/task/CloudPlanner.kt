package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.Usage
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.redact.Redactor

/**
 * Cloud-key and Home (vLLM) modes: one OpenAI-compatible chat call per step, brain tier only
 * (plan §9.3 M6). Every call is metered; when the spend cap is hit the planner answers with
 * an `ask` line so the loop ends the turn cleanly instead of spending more.
 */
class CloudPlanner(
    private val client: ProviderClient,
    private val meter: CostTracker,
    private val tier: TierConfig,
    private val tierName: TierName = TierName.BRAIN,
    private val spendCapUsd: Double?,
) : Planner {
    override suspend fun next(prompt: String): String {
        if (meter.capExceeded(spendCapUsd)) {
            return "ask \"The spend cap of \$${"%.2f".format(spendCapUsd)} is reached. Raise it in the app to continue.\""
        }
        meter.beginTurn()
        val result = try {
            client.chat(
                messages = listOf(
                    ChatMessage("system", "Reply with exactly one action line from the grammar, after any thinking. The action line must appear in the message content. No prose."),
                    ChatMessage("user", prompt),
                ),
                maxTokens = MAX_ACTION_TOKENS,
                temperature = 0.0,
            )
        } catch (e: Exception) {
            // The reason reaches the person as a question, never as a stack trace, and never with a key in it.
            return "ask \"The model call failed: ${Redactor.throwableMessage(e).replace('"', '\'')}\""
        }
        if (result.text.isBlank()) {
            return "ask \"The model returned an empty action (thinking used the token budget). Retry.\""
        }
        meter.record(
            tierName, client.model,
            Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
            tier,
        )
        return result.text
    }

    companion object {
        /** Grammar is one line. Qwen3-class thinking models burn thousands of tokens first. */
        const val MAX_ACTION_TOKENS = 8192
    }
}
