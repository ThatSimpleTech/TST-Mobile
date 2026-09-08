package com.thatsimpletech.assist.core.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderClientTest {
    private val key = "sk-canary-0123456789abcdef"
    private val messages = listOf(ChatMessage("system", "You are terse."), ChatMessage("user", "hi"))

    private fun <T> withServer(block: (MockWebServer, Endpoints) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server, Endpoints(setOf(server.url("/").host)))
        } finally {
            server.shutdown()
        }
    }

    private fun reply(usage: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
        """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"tap 7"}}],"usage":$usage}""",
    )

    @Test
    fun cachedTokensComeFromPromptTokensDetails() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":120,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":96}}"""))
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "gpt-x")
        val r = runBlocking { client.chat(messages, 64, 0.0) }
        assertEquals("tap 7", r.text)
        assertEquals(ProviderUsage(120, 96, 4), r.usage)
    }

    @Test
    fun cachedTokensAreNullWhenTheProviderSaysNothingAboutCaching() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":120,"completion_tokens":4}"""))
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "gpt-x")
        val r = runBlocking { client.chat(messages, 64, 0.0) }
        assertNull(r.usage.cachedPromptTokens)
        assertEquals(120, r.usage.promptTokens)
        assertEquals(4, r.usage.completionTokens)
    }

    @Test
    fun cachedTokensAreZeroWhenTheProviderSaysZero() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":120,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":0}}"""))
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "gpt-x")
        val r = runBlocking { client.chat(messages, 64, 0.0) }
        assertEquals(0, r.usage.cachedPromptTokens)
    }

    @Test
    fun keylessClientSendsNoAuthorizationHeader() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":1,"completion_tokens":1}"""))
        val client = ProviderClient(endpoints, server.url("/v1").toString(), null, "local-model")
        runBlocking { client.chat(messages, 8, 0.2) }
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", req.path)
    }

    @Test
    fun keyedClientSendsBearerAndTheChatCompletionsShape() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":1,"completion_tokens":1}"""))
        val client = ProviderClient(endpoints, server.url("/v1/").toString(), key, "gpt-x")
        runBlocking { client.chat(messages, 64, 0.5) }
        val req = server.takeRequest()
        assertEquals("Bearer $key", req.getHeader("Authorization"))
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("gpt-x", body["model"]!!.jsonPrimitive.content)
        assertEquals(64, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.5, body["temperature"]!!.jsonPrimitive.content.toDouble())
        val sent = body["messages"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf("system", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("You are terse.", "hi"), sent.map { it["content"]!!.jsonPrimitive.content })
    }

    @Test
    fun errorStatusMapsToProviderExceptionWithStatusAndNoKey() = withServer { server, endpoints ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided: $key"}}"""))
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "gpt-x")
        val e = assertFailsWith<ProviderException> { runBlocking { client.chat(messages, 8, 0.0) } }
        assertEquals(401, e.status)
        assertTrue("401" in e.message!!, e.message)
        assertFalse(key in e.message!!, "key leaked into the exception: ${e.message}")
        assertTrue("Incorrect API key" in e.message!!, e.message)
    }

    @Test
    fun emptyContentFallsBackToReasoningContent() = withServer { server, endpoints ->
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"<think>plan</think>\ntap 3"}}],"usage":{"prompt_tokens":10,"completion_tokens":40}}""",
            ),
        )
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "ezer-chat")
        val r = runBlocking { client.chat(messages, 1024, 0.0) }
        assertEquals("tap 3", r.text)
    }

    @Test
    fun nullContentFallsBackToReasoning() = withServer { server, endpoints ->
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"open WhatsApp"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
            ),
        )
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "ezer-chat")
        val r = runBlocking { client.chat(messages, 64, 0.0) }
        assertEquals("open WhatsApp", r.text)
    }

    @Test
    fun toStringNeverRevealsTheKey() {
        val client = ProviderClient(Endpoints(setOf("api.example.com")), "https://api.example.com/v1", key, "gpt-x")
        assertFalse(key in client.toString(), client.toString())
        assertTrue("api.example.com" in client.toString())
        assertTrue("key=none" in ProviderClient(Endpoints(setOf("api.example.com")), "https://api.example.com/v1", null, "m").toString())
    }

    @Test
    fun baseUrlOutsideConfigIsRefusedBeforeAnySocketOpens() = withServer { server, _ ->
        server.enqueue(reply("""{"prompt_tokens":1,"completion_tokens":1}"""))
        val strangers = Endpoints(setOf("api.example.com"))
        val client = ProviderClient(strangers, server.url("/v1").toString(), key, "gpt-x")
        val e = assertFailsWith<HostNotNamedException> { runBlocking { client.chat(messages, 8, 0.0) } }
        assertEquals(server.url("/").host, e.host)
        assertFalse(key in e.message!!)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun imageMessagesUseTheOpenAiContentArray() = withServer { server, endpoints ->
        server.enqueue(reply("""{"prompt_tokens":10,"completion_tokens":5}"""))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val client = ProviderClient(endpoints, server.url("/v1").toString(), key, "gpt-x")
        runBlocking { client.chat(listOf(ChatMessage("user", "what is the total?", images = listOf(jpeg))), 64, 0.0) }
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is the total?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        val decoded = java.util.Base64.getDecoder().decode(url.removePrefix("data:image/jpeg;base64,"))
        assertEquals(jpeg.toList(), decoded.toList())
    }

    @Test
    fun chatMessageToStringDoesNotDumpTheJpeg() {
        val jpeg = ByteArray(32) { 0xFF.toByte() }
        val m = ChatMessage("user", "q", images = listOf(jpeg))
        assertEquals("ChatMessage(role=user, chars=1, images=1)", m.toString())
        assertFalse("/9j" in m.toString())
    }
}
