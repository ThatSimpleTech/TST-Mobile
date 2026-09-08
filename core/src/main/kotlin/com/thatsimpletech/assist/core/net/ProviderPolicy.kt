package com.thatsimpletech.assist.core.net

import com.thatsimpletech.assist.core.config.Endpoint

/**
 * Default picker and family-mode host block (TM-020). Chinese cloud *hosts* are
 * blocked, not OpenRouter slugs: the shipped brain is `moonshotai/kimi-k3` on
 * `openrouter.ai`. Classification is a string parse of [Endpoint.host]; nothing
 * here resolves DNS.
 */
object ProviderPolicy {
    const val FAMILY_BLOCKED = "that host is blocked in family mode"

    val blockedHosts: Set<String> = setOf(
        "api.deepseek.com",
        "api.moonshot.cn",
        "api.moonshot.ai",
        "dashscope.aliyuncs.com",
        "dashscope-intl.aliyuncs.com",
        "open.bigmodel.cn",
        "api.minimax.chat",
        "api.minimax.io",
        "qianfan.baidubce.com",
        "hunyuan.tencentcloudapi.com",
        "api.stepfun.com",
        "api.lingyiwanwu.com",
        "spark-api.xf-yun.com",
        "api.sensenova.cn",
    )

    /** Chips the settings screen offers. No blocked host, no Device/LiteRT. */
    fun defaultPickerModes(): List<String> = listOf("OpenRouter", "Local / LAN", "Custom server")

    /**
     * Device and loopback are always allowed. With family mode off, any other
     * host is allowed (BYOM); [com.thatsimpletech.assist.core.net.Endpoints]
     * still allowlists only that host. With family mode on, a blocked host is
     * refused even if a custom URL names it.
     */
    fun hostAllowed(baseUrl: String, familyMode: Boolean): Boolean {
        if (Endpoint.isOnDevice(baseUrl) || Endpoint.isOnBox(baseUrl)) return true
        if (!familyMode) return true
        val host = Endpoint.host(baseUrl) ?: return false
        return host !in blockedHosts
    }

    /** The sentence [hostAllowed] maps to, or null when the URL may be used. */
    fun familyBlockReason(baseUrl: String, familyMode: Boolean): String? =
        if (hostAllowed(baseUrl, familyMode)) null else FAMILY_BLOCKED
}
