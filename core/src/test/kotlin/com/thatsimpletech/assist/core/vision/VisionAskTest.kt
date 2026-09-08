package com.thatsimpletech.assist.core.vision

import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.net.Endpoints
import com.thatsimpletech.assist.core.net.ProviderClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionAskTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private fun tier(base: String) = TierConfig(
        slug = "ezer-chat",
        baseUrl = base,
        inputPrice = 1.0,
        outputPrice = 2.0,
        cacheReadPrice = 0.0,
        contextWindow = 8000,
        maxOutputTokens = 400,
    )

    @Test
    fun emptyJpegIsAnErrorWithoutASocket() {
        val meter = CostTracker()
        val client = ProviderClient(Endpoints(setOf("example.com")), "https://example.com/v1", null, "m")
        val r = runBlocking { VisionAsk.run(client, meter, tier("https://example.com/v1"), "what?", ByteArray(0)) }
        assertFalse(r.ok)
        assertTrue("empty" in r.detail, r.detail)
        assertEquals(0, meter.calls.size)
    }

    @Test
    fun oversizedJpegIsAnErrorWithoutASocket() {
        val meter = CostTracker()
        val client = ProviderClient(Endpoints(setOf("example.com")), "https://example.com/v1", null, "m")
        val big = ByteArray(VisionAsk.MAX_JPEG_BYTES + 1)
        val r = runBlocking { VisionAsk.run(client, meter, tier("https://example.com/v1"), "what?", big) }
        assertFalse(r.ok)
        assertTrue("too large" in r.detail, r.detail)
        assertEquals(0, meter.calls.size)
    }

    @Test
    fun successRecordsSpendAndReturnsTheAnswer() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"The total is 47.20"}}],"usage":{"prompt_tokens":1200,"completion_tokens":8}}""",
                ),
            )
            val base = server.url("/v1").toString()
            val client = ProviderClient(Endpoints(setOf(server.url("/").host)), base, null, "ezer-chat")
            val meter = CostTracker()
            val r = runBlocking { VisionAsk.run(client, meter, tier(base), "what is the total?", jpeg) }
            assertTrue(r.ok, r.detail)
            assertEquals("The total is 47.20", r.detail)
            assertEquals(1, meter.calls.size)
            assertEquals(1200, meter.calls[0].promptTokens)
            assertEquals(8, meter.calls[0].completionTokens)
            assertFalse(meter.calls[0].classifier)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun userPromptNamesTheQuestion() {
        val p = VisionAsk.userPrompt("what is the total?")
        assertTrue("what is the total?" in p)
        assertTrue("screenshot" in p.lowercase())
    }
}
