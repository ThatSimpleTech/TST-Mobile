package com.thatsimpletech.assist.core.vision

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.Usage
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.redact.Redactor
import kotlin.coroutines.cancellation.CancellationException

/**
 * M6 `screen ask`: one metered vision call through [ProviderClient]. The JPEG is already
 * captured; this object never sees Android. Counted on the session ledger (not a classifier)
 * because a screenshot leaving the phone is the expensive, privacy-bearing step (TM-007).
 */
object VisionAsk {
    const val SYSTEM =
        "You are looking at a screenshot of an Android phone. Answer the question in one short paragraph of plain text. No actions, no JSON, no markdown, no grammar verbs."
    const val MAX_TOKENS = 400
    const val MAX_JPEG_BYTES = 1_200_000
    const val ANSWER_CHARS = 800

    fun userPrompt(question: String): String =
        "Question: $question\nAnswer from the screenshot only. If you cannot see it, say so."

    suspend fun run(
        client: ProviderClient,
        meter: CostTracker,
        tier: TierConfig,
        question: String,
        jpeg: ByteArray,
        tierName: TierName = TierName.BRAIN,
    ): ExecResult {
        if (jpeg.isEmpty()) return ExecResult.error("screenshot was empty")
        if (jpeg.size > MAX_JPEG_BYTES) return ExecResult.error("screenshot is too large to send")
        val result = try {
            client.chat(
                messages = listOf(
                    ChatMessage("system", SYSTEM),
                    ChatMessage("user", userPrompt(question), images = listOf(jpeg)),
                ),
                maxTokens = MAX_TOKENS,
                temperature = 0.0,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExecResult.error("vision call failed: ${Redactor.throwableMessage(e)}")
        }
        meter.record(
            tierName,
            client.model,
            Usage(result.usage.promptTokens, result.usage.cachedPromptTokens, result.usage.completionTokens),
            tier,
        )
        val text = Redactor.text(result.text).trim()
        if (text.isEmpty()) return ExecResult.error("vision model returned nothing")
        return ExecResult(true, text.take(ANSWER_CHARS))
    }
}
