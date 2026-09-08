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
 * Cloud-key and Home (vLLM) modes: one OpenAI-compatible chat call per step, brain tier only
 * (plan §9.3 M6). Every call is metered. The spend cap is owned by the loop ([com.thatsimpletech.assist.core.loop.Outcome.Paused]),
 * not synthesized here as an `ask` line (TM-017).
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
        val result = try {
            client.chat(
                messages = listOf(
                    ChatMessage("system", "Reply with exactly one action line from the grammar. No prose."),
                    ChatMessage("user", prompt),
                ),
                maxTokens = MAX_ACTION_TOKENS,
                temperature = 0.0,
            )
        } catch (e: Exception) {
            // The reason reaches the person as a question, never as a stack trace, and never with a key in it.
            return "ask \"The model call failed: ${Redactor.throwableMessage(e).replace('"', '\'')}\""
        }
        meter.record(
            tierName, client.model,
            Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
            tier,
        )
        return result.text
    }

    /**
     * One metered brain call, not a loop step (TM-019). HTTP failure returns empty so the
     * controller can apply the Q8 infer fallback; it does not throw into the loop.
     */
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
        /** One grammar line; the longest legal line is a `type` with a sentence of text. */
        const val MAX_ACTION_TOKENS = 120
        /** One `apps` line; labels are short. */
        const val MAX_APPS_TOKENS = 60
        const val SYSTEM_APPS = "Reply with one apps line. Labels must be from this allowlist."
    }
}
