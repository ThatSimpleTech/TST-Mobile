package com.thatsimpletech.assist.core.net

import com.thatsimpletech.assist.core.redact.Redactor
import java.io.InterruptedIOException

/**
 * Transient provider failures (TM-031). Timeouts already retry in CloudPlanner;
 * 429 / 502 / 503 / 504 join that set. A 429 must not dump OpenRouter JSON into Last run.
 */
object ProviderRetry {
    val TRANSIENT = setOf(429, 502, 503, 504)

    fun retryable(e: Exception): Boolean = when (e) {
        is InterruptedIOException -> true
        is ProviderException -> e.status in TRANSIENT
        else -> false
    }

    fun askLine(e: Exception): String {
        val inner = when {
            e is ProviderException && e.status == 429 ->
                "This model is rate-limited. Wait and Run again, or tap EZER for the home box."
            e is ProviderException && e.status in 502..504 ->
                "The model provider timed out (${e.status}). Wait and Run again, or tap EZER for the home box."
            else ->
                "The model call failed: ${Redactor.throwableMessage(e).replace('"', '\'')}"
        }
        return "ask \"$inner\""
    }
}
