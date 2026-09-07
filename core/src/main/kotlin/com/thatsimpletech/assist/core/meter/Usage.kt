package com.thatsimpletech.assist.core.meter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Token usage as the provider reported it: the port of `provider.Usage`.
 *
 * [cachedPromptTokens] is null when the response carried no cached-token figure at all, and
 * an integer, 0 included, when it did. Silence is not a report of zero reuse: Ollama's
 * OpenAI-compatible endpoint omits `prompt_tokens_details` entirely, and folding that into 0
 * would let every surface claim a cache miss the provider never made (desktop TD-1811).
 */
data class Usage(
    val promptTokens: Int = 0,
    val cachedPromptTokens: Int? = null,
    val completionTokens: Int = 0,
) {
    /** True when the provider said anything at all about cache, even "0". */
    val cacheReported: Boolean get() = cachedPromptTokens != null

    companion object {
        /** Parses an OpenAI-shaped `usage` object. A missing or malformed `prompt_tokens_details` is unknown, not zero. */
        fun fromApi(usage: JsonObject?): Usage {
            if (usage == null) return Usage()
            val details = usage["prompt_tokens_details"] as? JsonObject
            val cached = details?.get("cached_tokens")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            return Usage(
                promptTokens = usage.int("prompt_tokens"),
                cachedPromptTokens = cached,
                completionTokens = usage.int("completion_tokens"),
            )
        }

        private fun JsonObject.int(key: String): Int =
            this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
    }
}
