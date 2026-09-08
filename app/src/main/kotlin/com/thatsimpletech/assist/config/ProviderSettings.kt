package com.thatsimpletech.assist.config

import android.content.Context
import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.Endpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * What the person typed on the settings screen. Default is EZER's home box
 * (`llm.ezer-server.ts.net` / `ezer-chat`). OpenRouter and a LAN Ollama stay as
 * BYOM fallbacks. The shipped YAML is the price book; this overlay is how the
 * phone picks a brain without editing config.yaml.
 */
data class ProviderSettings(
    val mode: Mode,
    val model: String,
    val baseUrl: String,
) {
    enum class Mode { EZER, CLOUD, LOCAL }

    val label: String get() = when (mode) {
        Mode.EZER -> "EZER home"
        Mode.CLOUD -> "OpenRouter"
        Mode.LOCAL -> "Local / LAN"
    }

    /** OpenRouter refuses without a key. EZER and local do not require one. */
    val requiresKey: Boolean get() = mode == Mode.CLOUD

    val credentialId: String? get() = when (mode) {
        Mode.EZER -> AssistConfig.EZER_CREDENTIAL
        Mode.CLOUD -> AssistConfig.DEFAULT_CREDENTIAL
        Mode.LOCAL -> null
    }

    fun resolvedUrl(): String = when (mode) {
        Mode.CLOUD -> OPENROUTER_URL
        Mode.EZER -> if (baseUrl.isBlank()) DEFAULT_EZER_URL else normalizeUrl(baseUrl)
        Mode.LOCAL -> normalizeUrl(baseUrl)
    }

    fun toConfig(shipped: AssistConfig): AssistConfig {
        val slug = model.trim().ifEmpty {
            if (mode == Mode.EZER) DEFAULT_EZER_MODEL else DEFAULT_CLOUD_MODEL
        }
        return when (mode) {
            Mode.EZER -> shipped.withHome(resolvedUrl(), slug, AssistConfig.EZER_CREDENTIAL)
            Mode.CLOUD -> shipped.withUserEndpoint(
                OPENROUTER_URL, slug, AssistConfig.DEFAULT_CREDENTIAL, "tst-default",
            )
            Mode.LOCAL -> shipped.withUserEndpoint(resolvedUrl(), slug, null, "local")
        }
    }

    fun problem(): String? {
        if (model.trim().isEmpty()) return "set a model id"
        if (mode == Mode.CLOUD) return null
        val url = try {
            if (mode == Mode.EZER && baseUrl.isBlank()) DEFAULT_EZER_URL else normalizeUrl(baseUrl)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        if (!Endpoint.isNetwork(url)) return "base URL must be http or https"
        if (Endpoint.host(url) == null) return "base URL has no host"
        return null
    }

    companion object {
        const val PREFS = "provider"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_CLOUD_MODEL = "moonshotai/kimi-k3"
        const val DEFAULT_EZER_URL = "https://llm.ezer-server.ts.net/v1"
        const val DEFAULT_EZER_MODEL = "ezer-chat"
        const val DEFAULT_LOCAL_URL = "http://192.168.1.10:11434/v1"

        fun load(ctx: Context): ProviderSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = p.getString("mode", Mode.EZER.name) ?: Mode.EZER.name
            val mode = when (raw) {
                "CUSTOM" -> Mode.EZER // previous "Custom server" becomes EZER home
                else -> try {
                    Mode.valueOf(raw)
                } catch (_: Exception) {
                    Mode.EZER
                }
            }
            val defaultModel = if (mode == Mode.EZER) DEFAULT_EZER_MODEL else DEFAULT_CLOUD_MODEL
            val defaultUrl = if (mode == Mode.EZER) DEFAULT_EZER_URL else DEFAULT_LOCAL_URL
            return ProviderSettings(
                mode = mode,
                model = p.getString("model", defaultModel) ?: defaultModel,
                baseUrl = p.getString("base_url", defaultUrl) ?: defaultUrl,
            )
        }

        fun save(ctx: Context, s: ProviderSettings) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", s.mode.name)
                .putString("model", s.model.trim())
                .putString("base_url", s.baseUrl.trim())
                .apply()
        }

        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            require(u.isNotEmpty()) { "set a base URL" }
            if (!u.contains("://")) u = "http://$u"
            val uri = try {
                URI(u)
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("base URL is not a URL")
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "base URL must be http or https" }
            require(!uri.host.isNullOrBlank()) { "base URL has no host" }
            return u.trimEnd('/')
        }
    }
}
