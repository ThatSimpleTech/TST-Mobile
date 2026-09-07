package com.thatsimpletech.assist.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatMessage(val role: String, val content: String)

/**
 * What the provider reported. [cachedPromptTokens] is null when the provider said nothing
 * about caching and 0 when it said zero; the meter prices cache reads only in the first
 * case's absence, exactly as tstd does (plan §5).
 */
data class ProviderUsage(val promptTokens: Int, val cachedPromptTokens: Int?, val completionTokens: Int)

data class ChatResult(val text: String, val usage: ProviderUsage)

/** A non-2xx reply. The message carries the status and a scrubbed excerpt, never the key. */
class ProviderException(val status: Int, message: String) : IOException(message)

/**
 * The Cloud-key mode brain: one OpenAI-compatible `/chat/completions` call per step, through
 * [Endpoints] so the base URL's host must be named in config. The key lives in memory for
 * the life of this object and appears in exactly one place, the Authorization header.
 */
class ProviderClient(
    private val endpoints: Endpoints,
    baseUrl: String,
    private val apiKey: String?,
    val model: String,
) {
    private val client = endpoints.httpClient()
    private val url = baseUrl.trimEnd('/').let { "$it/chat/completions" }.toHttpUrl()
    val host: String get() = url.host

    override fun toString(): String = "ProviderClient(host=$host, model=$model, key=${if (apiKey == null) "none" else "<redacted>"})"

    @Throws(IOException::class)
    suspend fun chat(messages: List<ChatMessage>, maxTokens: Int, temperature: Double): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
        }.toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = client.newCall(request).await()
        response.use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw ProviderException(r.code, "provider $host returned HTTP ${r.code}: ${scrub(text)}")
            return parse(text)
        }
    }

    private fun parse(text: String): ChatResult {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ProviderException(200, "provider $host returned a reply that is not a JSON object")
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ProviderException(200, "provider $host returned no choices")
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull ?: ""
        val usage = obj["usage"] as? JsonObject
        val details = usage?.get("prompt_tokens_details") as? JsonObject
        return ChatResult(
            text = content,
            usage = ProviderUsage(
                promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
                cachedPromptTokens = (details?.get("cached_tokens") as? JsonPrimitive)?.intOrNull,
                completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            ),
        )
    }

    /** Providers echo keys in error bodies ("Incorrect API key provided: sk-..."). Ours never leaves this object. */
    private fun scrub(body: String): String {
        val cleaned = if (apiKey.isNullOrEmpty()) body else body.replace(apiKey, "<redacted>")
        return cleaned.replace(Regex("\\s+"), " ").trim().take(EXCERPT_CHARS)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val EXCERPT_CHARS = 200
    }
}
