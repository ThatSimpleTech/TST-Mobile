package com.thatsimpletech.assist.core.net

import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRetryTest {
    @Test
    fun rateLimitAndGatewayAreRetryable() {
        assertTrue(ProviderRetry.retryable(ProviderException(429, "rate")))
        assertTrue(ProviderRetry.retryable(ProviderException(502, "bad gateway")))
        assertTrue(ProviderRetry.retryable(ProviderException(503, "unavailable")))
        assertTrue(ProviderRetry.retryable(ProviderException(504, "timeout")))
        assertTrue(ProviderRetry.retryable(InterruptedIOException("timeout")))
    }

    @Test
    fun authAndNotFoundAreNotRetryable() {
        assertFalse(ProviderRetry.retryable(ProviderException(401, "no")))
        assertFalse(ProviderRetry.retryable(ProviderException(404, "missing")))
        assertFalse(ProviderRetry.retryable(IllegalStateException("no")))
    }

    @Test
    fun rateLimitAskIsShortAndDropsProviderJson() {
        val raw = ProviderException(
            429,
            "provider openrouter.ai returned HTTP 429: {'error':{'message':'Provider returned error','code':429}}",
        )
        val line = ProviderRetry.askLine(raw)
        assertEquals(
            "ask \"This model is rate-limited. Wait and Run again, or tap EZER for the home box.\"",
            line,
        )
        assertFalse("openrouter" in line)
        assertFalse("429" in line)
    }

    @Test
    fun gatewayAskNamesTheStatus() {
        val line = ProviderRetry.askLine(ProviderException(504, "provider openrouter.ai returned HTTP 504: aborted"))
        assertEquals(
            "ask \"The model provider timed out (504). Wait and Run again, or tap EZER for the home box.\"",
            line,
        )
        assertFalse("openrouter" in line)
    }
}
