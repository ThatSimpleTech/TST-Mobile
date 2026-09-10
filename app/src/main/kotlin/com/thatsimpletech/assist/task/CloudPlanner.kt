package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.GoalAppsPlanner
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.Usage
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.net.ProviderException
import com.thatsimpletech.assist.core.net.ProviderRetry
import com.thatsimpletech.assist.core.policy.PolicyPack
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * One OpenAI-compatible chat call per step. Spend cap is owned by the loop (TM-017).
 * Timeouts and transient provider codes (429/502/503/504) retry once (TM-031).
 * Qwen3 thinking needs a large token budget.
 */
class CloudPlanner(
    private val client: ProviderClient,
    private val meter: CostTracker,
    private val tier: TierConfig,
    private val tierName: TierName = TierName.BRAIN,
    private val pack: PolicyPack,
) : Planner {
    override suspend fun next(prompt: String): String {
        meter.beginTurn()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val result = try {
                client.chat(
                    messages = listOf(
                        ChatMessage("system", SYSTEM_ACTION),
                        ChatMessage("user", prompt),
                    ),
                    maxTokens = MAX_ACTION_TOKENS,
                    temperature = 0.0,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ProviderRetry.retryable(e)) {
                    lastError = e
                    if (attempt == 0) delay(RETRY_MS)
                    return@repeat
                }
                return ProviderRetry.askLine(e)
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
        val e = lastError
        if (e is ProviderException) return ProviderRetry.askLine(e)
        return "ask \"EZER box timed out waiting for a reply (attempt ${(e?.message ?: "timeout")}). Phone and box both need Tailscale; LiteLLM must be up on :4000. Then Run again.\""
    }

    override suspend fun planGoalApps(goal: String): Set<String> {
        meter.beginTurn()
        repeat(2) { attempt ->
            val result = try {
                client.chat(
                    messages = listOf(
                        ChatMessage("system", SYSTEM_APPS),
                        ChatMessage("user", GoalAppsPlanner.prompt(goal, pack.apps.map { it.label })),
                    ),
                    maxTokens = MAX_APPS_TOKENS,
                    temperature = 0.0,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ProviderRetry.retryable(e) && attempt == 0) {
                    delay(RETRY_MS)
                    return@repeat
                }
                return emptySet()
            }
            meter.record(
                tierName, client.model,
                Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
                tier,
            )
            return GoalAppsPlanner.parse(result.text, pack)
        }
        return emptySet()
    }

    companion object {
        const val MAX_ACTION_TOKENS = 8192
        const val MAX_APPS_TOKENS = 60
        const val RETRY_MS = 2000L
        const val SYSTEM_ACTION = "Reply with exactly one action line from the grammar, after any thinking. The person already confirmed the GOAL by tapping Run. Prefer a whole-job verb (torch, timer, alarm, call, text, whatsapp, gmail, navigate, media, spotify) over open/tap. whatsapp/call/text take a name or a number. A hint is the [number] on an observation line. `@x,y` is the same control by screen percent: `tap @80,92` taps whatever is there; a line that is only `@80,92` is a tap. Keyboard keys are not in the list. After type or whatsapp, tap the button to the right of the focused edit (higher x, same y). Never done while that button is still the next step. The action line must appear in the message content. No prose."
        const val SYSTEM_APPS = "Reply with one apps line. Labels must be from this allowlist."
    }
}
