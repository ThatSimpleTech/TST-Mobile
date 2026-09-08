package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.GoalAppsPlanner
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.Usage
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.redact.Redactor
import kotlin.coroutines.cancellation.CancellationException

/**
 * One OpenAI-compatible chat call per step. Spend cap is owned by the loop (TM-017).
 * Timeouts retry once; Qwen3 thinking needs a large token budget.
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
        repeat(2) {
            val result = try {
                client.chat(
                    messages = listOf(
                        ChatMessage("system", SYSTEM_ACTION),
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

    override suspend fun planGoalApps(goal: String): Set<String> {
        meter.beginTurn()
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
        } catch (_: Exception) {
            return emptySet()
        }
        meter.record(
            tierName, client.model,
            Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
            tier,
        )
        return GoalAppsPlanner.parse(result.text, pack)
    }

    companion object {
        const val MAX_ACTION_TOKENS = 8192
        const val MAX_APPS_TOKENS = 60
        const val SYSTEM_ACTION = "Reply with exactly one action line from the grammar, after any thinking. The person already confirmed the GOAL by tapping Run. Use @x,y percents to pick the right control. Keyboard keys are not in the list. After type, tap the button to the right of the focused edit (higher x, same y). Never done while that button is still the next step. The action line must appear in the message content. No prose."
        const val SYSTEM_APPS = "Reply with one apps line. Labels must be from this allowlist."
    }
}
