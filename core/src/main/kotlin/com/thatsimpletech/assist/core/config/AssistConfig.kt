package com.thatsimpletech.assist.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The three router tiers. The YAML words are the desktop's. */
@Serializable
enum class TierName(val word: String) {
    @SerialName("brain") BRAIN("brain"),
    @SerialName("worker") WORKER("worker"),
    @SerialName("validator") VALIDATOR("validator"),
}

/**
 * One model tier: the same keys as TST Desk's `config.yaml` (plan §5), so a preset can be
 * copied between the two files unchanged. Prices are dollars per million tokens.
 *
 * `slug` is optional only on a loopback endpoint, where the desktop discovers the model from
 * `/v1/models`; anywhere else, including `device://`, a missing slug is a config error, not a
 * null model on the wire. `credential` names a [com.thatsimpletech.assist.core.secrets.SecretStore]
 * account (`tst-<credential>`); omitted means keyless on box and the `openrouter` key off box,
 * as on the desktop.
 */
@Serializable
data class TierConfig(
    val slug: String? = null,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("input_price") val inputPrice: Double,
    @SerialName("output_price") val outputPrice: Double,
    @SerialName("cache_read_price") val cacheReadPrice: Double,
    @SerialName("context_window") val contextWindow: Int,
    @SerialName("max_output_tokens") val maxOutputTokens: Int,
    val credential: String? = null,
) {
    val kind: EndpointKind get() = Endpoint.kind(baseUrl)

    /** True when every price is zero: the meter must read exactly $0.00 on this tier. */
    val isFree: Boolean get() = inputPrice == 0.0 && outputPrice == 0.0 && cacheReadPrice == 0.0

    /** The credential id this tier sends with, or null for a keyless loopback tier (desktop `resolve_credential_id`). */
    val credentialId: String?
        get() = credential?.trim()?.ifEmpty { null }
            ?: if (kind == EndpointKind.ON_BOX || kind == EndpointKind.ON_DEVICE) null else AssistConfig.DEFAULT_CREDENTIAL

    fun validate(path: String): List<String> {
        val p = ArrayList<String>()
        if (baseUrl.isBlank()) p += "$path.base_url must not be empty"
        else if (!Endpoint.isNetwork(baseUrl) && !Endpoint.isOnDevice(baseUrl)) p += "$path.base_url must be http(s) or device://: $baseUrl"
        if (slug != null && slug.isEmpty()) p += "$path.slug must not be empty when given"
        if (inputPrice < 0) p += "$path.input_price must be >= 0"
        if (outputPrice < 0) p += "$path.output_price must be >= 0"
        if (cacheReadPrice < 0) p += "$path.cache_read_price must be >= 0"
        if (contextWindow <= 0) p += "$path.context_window must be > 0"
        if (maxOutputTokens <= 0) p += "$path.max_output_tokens must be > 0"
        if (slug == null && kind != EndpointKind.ON_BOX) {
            p += "$path.slug is required for the off-box endpoint $baseUrl; only a loopback endpoint discovers its model from /v1/models"
        }
        return p
    }
}

@Serializable
data class Preset(val brain: TierConfig, val worker: TierConfig, val validator: TierConfig) {
    fun tier(name: TierName): TierConfig = when (name) {
        TierName.BRAIN -> brain
        TierName.WORKER -> worker
        TierName.VALIDATOR -> validator
    }

    val tiers: Map<TierName, TierConfig> get() = TierName.entries.associateWith { tier(it) }

    /** Hostnames this preset can open. `device://` contributes nothing; there is no socket. */
    val hosts: Set<String> get() = tiers.values.filter { Endpoint.isNetwork(it.baseUrl) }.mapNotNullTo(LinkedHashSet()) { Endpoint.host(it.baseUrl) }
}

/** A named key: the secret lives in the SecretStore, only the display name lives here. */
@Serializable
data class CredentialConfig(val name: String, @SerialName("base_url") val baseUrl: String? = null)

/**
 * The phone's `config.yaml` (plan §5): the desktop's presets shape plus a spend cap. Loaded
 * strictly, so a misspelt key fails at load instead of silently keeping a default price.
 */
@Serializable
data class AssistConfig(
    val preset: String = DEFAULT_PRESET,
    val presets: Map<String, Preset>,
    /** Session spend at which the loop pauses. Null is no cap. */
    @SerialName("spend_cap_usd") val spendCapUsd: Double? = null,
    val credentials: Map<String, CredentialConfig> = emptyMap(),
) {
    val active: Preset get() = presets[preset] ?: error("preset '$preset' is not in the config")

    fun tier(name: TierName): TierConfig = active.tier(name)

    /**
     * The only hosts the app may open: the active preset's endpoints (plan §1, "no telemetry").
     * The outbound-hosts test fails on any destination not in this set.
     */
    val allowedHosts: Set<String> get() = active.hosts

    /**
     * The settings screen's overlay: one OpenAI-compatible endpoint and slug for all three
     * router tiers, prices taken from [template]. The host of [baseUrl] becomes the only
     * name [com.thatsimpletech.assist.core.net.Endpoints] will open.
     */
    fun withUserEndpoint(
        baseUrl: String,
        slug: String,
        credential: String?,
        template: String,
        name: String = USER_PRESET,
    ): AssistConfig {
        val src = presets[template] ?: error("template preset '$template' is not in the config")
        fun rewire(t: TierConfig) = t.copy(slug = slug, baseUrl = baseUrl, credential = credential)
        val preset = Preset(rewire(src.brain), rewire(src.worker), rewire(src.validator))
        return copy(preset = name, presets = presets + (name to preset))
    }

    /**
     * Point every router tier at EZER's box: [baseUrl] + [slug], $0, credential [credentialId]
     * (default `ezer`). The active preset becomes `home`. Pure; the caller persists it.
     */
    fun withHome(
        baseUrl: String,
        slug: String,
        credentialId: String? = EZER_CREDENTIAL,
        credentialName: String = "EZER",
    ): AssistConfig {
        val cred = credentialId?.trim()?.ifEmpty { null }
        val tier = TierConfig(
            slug = slug.trim(),
            baseUrl = baseUrl.trim(),
            inputPrice = 0.0,
            outputPrice = 0.0,
            cacheReadPrice = 0.0,
            contextWindow = 32768,
            maxOutputTokens = 4096,
            credential = cred,
        )
        val creds = if (cred != null && cred !in credentials) {
            credentials + (cred to CredentialConfig(name = credentialName))
        } else credentials
        return copy(preset = HOME_PRESET, presets = presets + (HOME_PRESET to Preset(tier, tier, tier)), credentials = creds)
    }

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (presets.isEmpty()) problems += "presets must not be empty"
        if (preset !in presets) problems += "preset '$preset' is not one of ${presets.keys}"
        val known = credentials.keys + DEFAULT_CREDENTIAL
        for ((pName, p) in presets) {
            for ((tName, t) in p.tiers) {
                val path = "presets.$pName.${tName.word}"
                problems += t.validate(path)
                val cid = t.credential?.trim()?.ifEmpty { null }
                if (cid != null && cid !in known) problems += "$path.credential '$cid' is not a declared credential"
            }
        }
        if (spendCapUsd != null && spendCapUsd <= 0) problems += "spend_cap_usd must be > 0 when set (omit it for no cap)"
        return problems
    }

    companion object {
        const val DEFAULT_PRESET = "tst-default"
        const val HOME_PRESET = "home"

        /** Written by the settings screen; never shipped in config.yaml. */
        const val USER_PRESET = "user"

        /** The desktop's implicit key: an off-box tier with no credential uses it. */
        const val DEFAULT_CREDENTIAL = "openrouter"

        /** Keystore account for the EZER home box (LiteLLM / vLLM on the tailnet). */
        const val EZER_CREDENTIAL = "ezer"

        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

        fun parse(text: String): AssistConfig {
            val cfg = yaml.decodeFromString(serializer(), text)
            val problems = cfg.validate()
            require(problems.isEmpty()) { "config invalid: ${problems.joinToString("; ")}" }
            return cfg
        }

        /** The config shipped in the core jar (core/src/main/resources/config.yaml). */
        fun loadDefault(): AssistConfig {
            val stream = AssistConfig::class.java.getResourceAsStream("/config.yaml")
                ?: error("config.yaml missing from the core jar")
            return stream.use { parse(it.readBytes().decodeToString()) }
        }
    }
}
