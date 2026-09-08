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
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val result = try {
                client.chat(
                    messages = listOf(
                        ChatMessage("system", "Reply with exactly one action line from the grammar, after any thinking. The person already confirmed the GOAL by tapping Run. Use @x,y percents to pick the right control. Keyboard keys are not in the list. After type, tap the button to the right of the focused edit (higher x, same y). Never done while that button is still the next step. The action line must appear in the message content. No prose."),
                        ChatMessage("user", prompt),
                    ),
                    maxTokens = MAX_ACTION_TOKENS,
                    temperature = 0.0,
                )
            } catch (e: java.io.InterruptedIOException) {
                lastError = e
                return@repeat
            } catch (e: Exception) {
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
        return "ask \"EZER box timed out waiting for a reply (attempt ${(lastError?.message ?: "timeout")}). Phone and box both need Tailscale; LiteLLM must be up on :4000. Then Run again.\""
    }

    companion object {
        /** Grammar is one line. Qwen3-class thinking models burn thousands of tokens first. */
        const val MAX_ACTION_TOKENS = 8192
    }
}
