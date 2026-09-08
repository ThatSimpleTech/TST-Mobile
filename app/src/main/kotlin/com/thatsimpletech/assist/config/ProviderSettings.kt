package com.thatsimpletech.assist.config

import android.content.Context
import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.grammar.HintCodec
import com.thatsimpletech.assist.core.net.ProviderPolicy
import java.net.URI
import java.net.URISyntaxException

/**
 * What the person typed on the settings screen: which brain, which model, which
 * OpenAI-compatible URL, plus spend cap, hint codec, and family mode. The shipped
 * YAML is still the price book; this overlay is the only way a phone picks a model
 * without editing config.yaml.
 */
data class ProviderSettings(
    val mode: Mode,
    val model: String,
    val baseUrl: String,
    val familyMode: Boolean = false,
    val spendCapUsd: Double? = null,
    val hintCodec: String = HintCodec.Numeric.word,
) {
    enum class Mode { CLOUD, LOCAL, CUSTOM }

    val label: String get() = when (mode) {
        Mode.CLOUD -> "OpenRouter"
        Mode.LOCAL -> "Local / LAN"
        Mode.CUSTOM -> "Custom server"
    }

    /** OpenRouter refuses without a key. Local Ollama and most home boxes do not need one. */
    val requiresKey: Boolean get() = mode == Mode.CLOUD

    fun codec(): HintCodec = HintCodec.parse(hintCodec)

    fun resolvedUrl(): String = when (mode) {
        Mode.CLOUD -> OPENROUTER_URL
        else -> normalizeUrl(baseUrl)
    }

    fun toConfig(shipped: AssistConfig): AssistConfig {
        val slug = model.trim().ifEmpty { DEFAULT_CLOUD_MODEL }
        val template = when (mode) {
            Mode.CLOUD -> "tst-default"
            Mode.LOCAL -> "local"
            Mode.CUSTOM -> "home"
        }
        val credential = if (mode == Mode.CLOUD) AssistConfig.DEFAULT_CREDENTIAL else null
        return shipped.withUserEndpoint(resolvedUrl(), slug, credential, template)
            .copy(spendCapUsd = spendCapUsd)
    }

    fun problem(): String? {
        if (model.trim().isEmpty()) return "set a model id"
        if (spendCapUsd != null && spendCapUsd <= 0.0) return "spend cap must be > 0 (leave empty for none)"
        if (mode != Mode.CLOUD) {
            val url = try {
                normalizeUrl(baseUrl)
            } catch (e: IllegalArgumentException) {
                return e.message
            }
            if (!Endpoint.isNetwork(url)) return "base URL must be http or https"
            if (Endpoint.host(url) == null) return "base URL has no host"
        }
        ProviderPolicy.familyBlockReason(resolvedUrl(), familyMode)?.let { return it }
        return null
    }

    companion object {
        const val PREFS = "provider"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_CLOUD_MODEL = "moonshotai/kimi-k3"
        const val DEFAULT_LOCAL_URL = "http://192.168.1.10:11434/v1"

        fun load(ctx: Context): ProviderSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = try {
                Mode.valueOf(p.getString("mode", Mode.CLOUD.name) ?: Mode.CLOUD.name)
            } catch (_: Exception) {
                Mode.CLOUD
            }
            val capRaw = p.getString("spend_cap_usd", null)?.trim().orEmpty()
            return ProviderSettings(
                mode = mode,
                model = p.getString("model", DEFAULT_CLOUD_MODEL) ?: DEFAULT_CLOUD_MODEL,
                baseUrl = p.getString("base_url", DEFAULT_LOCAL_URL) ?: DEFAULT_LOCAL_URL,
                familyMode = p.getBoolean("family_mode", false),
                spendCapUsd = capRaw.toDoubleOrNull()?.takeIf { it > 0.0 },
                hintCodec = HintCodec.parse(p.getString("hint_codec", HintCodec.Numeric.word) ?: HintCodec.Numeric.word).word,
            )
        }

        fun save(ctx: Context, s: ProviderSettings) {
            val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", s.mode.name)
                .putString("model", s.model.trim())
                .putString("base_url", s.baseUrl.trim())
                .putBoolean("family_mode", s.familyMode)
                .putString("hint_codec", HintCodec.parse(s.hintCodec).word)
            if (s.spendCapUsd != null) e.putString("spend_cap_usd", s.spendCapUsd.toString())
            else e.remove("spend_cap_usd")
            e.apply()
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
