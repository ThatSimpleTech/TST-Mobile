package com.thatsimpletech.assist.core.config

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** Where a tier's endpoint lives. Decides whether a slug is required and what the outbound allowlist contains (plan §3, §5). */
enum class EndpointKind {
    /** `device://…`: the on-device brain. No socket, no host, nothing leaves the phone. */
    ON_DEVICE,
    /** Loopback: 127/8, ::1, localhost. On-box by construction; may discover its model from /v1/models. */
    ON_BOX,
    /** A Tailscale address: off box, but ours. A home tstd or vLLM over the tailnet. */
    TAILNET,
    /** Everything else, and anything we cannot classify: a third party. */
    REMOTE,
}

/**
 * Endpoint classification, the Kotlin twin of `config.is_loopback_url` and
 * `tailscale_bind.is_tailscale_address`. Only literals are inspected: a hostname that merely
 * looks local is remote, and nothing here ever resolves a name, so classification cannot make
 * a network call.
 */
object Endpoint {
    const val DEVICE_SCHEME = "device"

    fun kind(url: String): EndpointKind = when {
        isOnDevice(url) -> EndpointKind.ON_DEVICE
        isOnBox(url) -> EndpointKind.ON_BOX
        isTailnet(url) -> EndpointKind.TAILNET
        else -> EndpointKind.REMOTE
    }

    /** `device://` is the on-device model runtime, not a network endpoint. */
    fun isOnDevice(url: String): Boolean = parse(url)?.scheme?.equals(DEVICE_SCHEME, ignoreCase = true) == true

    /** 127.0.0.0/8, ::1, or `localhost`. Mirrors `config.is_loopback_url`: the unclassifiable is not on box. */
    fun isOnBox(url: String): Boolean {
        val host = host(url) ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        return literal(host)?.isLoopbackAddress == true
    }

    /** CGNAT 100.64.0.0/10 or the Tailscale ULA fd7a:115c:a1e0::/48. */
    fun isTailnet(url: String): Boolean {
        val addr = literal(host(url) ?: return false) ?: return false
        val b = addr.address
        return when (addr) {
            // 100.64.0.0/10: first octet 100, second octet's top two bits are 01 (64..127).
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40
            is Inet6Address -> b.size == 16 &&
                (b[0].toInt() and 0xff) == 0xfd && (b[1].toInt() and 0xff) == 0x7a &&
                (b[2].toInt() and 0xff) == 0x11 && (b[3].toInt() and 0xff) == 0x5c &&
                (b[4].toInt() and 0xff) == 0xa1 && (b[5].toInt() and 0xff) == 0xe0
            else -> false
        }
    }

    /** True for the schemes a tier may use at all. */
    fun isNetwork(url: String): Boolean {
        val scheme = parse(url)?.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Lowercased host, brackets and IPv6 zone stripped; null when there is none or the URL will not parse. */
    fun host(url: String): String? {
        val raw = parse(url)?.host ?: return null
        return raw.removePrefix("[").removeSuffix("]").substringBefore('%').lowercase().ifEmpty { null }
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim()).takeIf { it.scheme != null }
    } catch (_: URISyntaxException) {
        null
    }

    /** An IP literal, or null. Names are never resolved: `InetAddress.getByName` only touches DNS for non-literals. */
    private fun literal(host: String): InetAddress? {
        val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        if (!ipv4.matches(host) && !host.contains(':')) return null
        return try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }
}
