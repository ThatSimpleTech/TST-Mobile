package com.thatsimpletech.assist.config

import android.content.Context
import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.grammar.HintCodec
import com.thatsimpletech.assist.core.net.ProviderPolicy
import java.net.URI
import java.net.URISyntaxException

/**
 * What the person typed on the settings screen. Default is EZER's home box.
 * Spend cap, hint codec, and family mode come from M2.
 */
data class ProviderSettings(
    val mode: Mode,
    val model: String,
    val baseUrl: String,
    val familyMode: Boolean = false,
    val spendCapUsd: Double? = null,
    val hintCodec: String = HintCodec.Numeric.word,
) {
    enum class Mode { EZER, CLOUD, LOCAL }

    val label: String get() = when (mode) {
        Mode.EZER -> "EZER home"
        Mode.CLOUD -> "OpenRouter"
        Mode.LOCAL -> "Local / LAN"
    }

    val requiresKey: Boolean get() = mode == Mode.CLOUD

    val credentialId: String? get() = when (mode) {
        Mode.EZER -> AssistConfig.EZER_CREDENTIAL
        Mode.CLOUD -> AssistConfig.DEFAULT_CREDENTIAL
        Mode.LOCAL -> null
    }

    fun codec(): HintCodec = HintCodec.parse(hintCodec)

    fun resolvedUrl(): String = when (mode) {
        Mode.CLOUD -> OPENROUTER_URL
        Mode.EZER -> if (baseUrl.isBlank()) DEFAULT_EZER_URL else normalizeUrl(baseUrl)
        Mode.LOCAL -> normalizeUrl(baseUrl)
    }

    fun toConfig(shipped: AssistConfig): AssistConfig {
        val slug = model.trim().ifEmpty {
            if (mode == Mode.EZER) DEFAULT_EZER_MODEL else DEFAULT_CLOUD_MODEL
        }
        val next = when (mode) {
            Mode.EZER -> shipped.withHome(resolvedUrl(), slug, AssistConfig.EZER_CREDENTIAL)
            Mode.CLOUD -> shipped.withUserEndpoint(
                OPENROUTER_URL, slug, AssistConfig.DEFAULT_CREDENTIAL, "tst-default",
            )
            Mode.LOCAL -> shipped.withUserEndpoint(resolvedUrl(), slug, null, "local")
        }
        return next.copy(spendCapUsd = spendCapUsd)
    }

    fun problem(): String? {
        if (model.trim().isEmpty()) return "set a model id"
        if (spendCapUsd != null && spendCapUsd <= 0.0) return "spend cap must be > 0 (leave empty for none)"
        if (mode != Mode.CLOUD) {
            val url = try {
                if (mode == Mode.EZER && baseUrl.isBlank()) DEFAULT_EZER_URL else normalizeUrl(baseUrl)
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
        const val DEFAULT_EZER_URL = "https://llm.ezer-server.ts.net/v1"
        const val DEFAULT_EZER_MODEL = "ezer-chat"
        const val DEFAULT_LOCAL_URL = "http://192.168.1.10:11434/v1"

        fun load(ctx: Context): ProviderSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = p.getString("mode", Mode.EZER.name) ?: Mode.EZER.name
            val mode = when (raw) {
                "CUSTOM" -> Mode.EZER
                else -> try {
                    Mode.valueOf(raw)
                } catch (_: Exception) {
                    Mode.EZER
                }
            }
            val defaultModel = if (mode == Mode.EZER) DEFAULT_EZER_MODEL else DEFAULT_CLOUD_MODEL
            val defaultUrl = if (mode == Mode.EZER) DEFAULT_EZER_URL else DEFAULT_LOCAL_URL
            val capRaw = p.getString("spend_cap_usd", null)?.trim().orEmpty()
            return ProviderSettings(
                mode = mode,
                model = p.getString("model", defaultModel) ?: defaultModel,
                baseUrl = p.getString("base_url", defaultUrl) ?: defaultUrl,
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
