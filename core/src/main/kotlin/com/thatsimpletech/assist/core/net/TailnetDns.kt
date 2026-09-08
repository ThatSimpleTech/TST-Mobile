package com.thatsimpletech.assist.core.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Pins MagicDNS names to the tailnet. A `*.ts.net` name is only ever connected to at a
 * Tailscale address (CGNAT 100.64.0.0/10 or fd7a:115c:a1e0::/48). When the Tailscale VPN is
 * off, the phone's ordinary resolver answers for such names, and a plain ws:// or http://
 * call would then carry a token or key to whoever answered. Every other name resolves as usual.
 */
class TailnetDns(private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = resolve(hostname)
        if (!isMagicDns(hostname)) return all
        val pinned = all.filter { BindRules.isTailscale(it.hostAddress ?: "") }
        if (pinned.isEmpty()) {
            throw UnknownHostException("$hostname did not resolve to a tailnet address; is Tailscale on?")
        }
        return pinned
    }

    companion object {
        fun isMagicDns(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            return h.endsWith(".ts.net") && h.removeSuffix(".ts.net").isNotEmpty()
        }
    }
}

/**
 * Where plain http:// or ws:// is allowed at all: loopback, a tailnet literal, or a MagicDNS
 * name (which [TailnetDns] then pins). This is core's own rule, enforced in [Endpoints]; the
 * app's network security config repeats it for anything that bypasses OkHttp.
 */
object CleartextPolicy {
    fun allowed(host: String): Boolean =
        BindRules.isLoopback(host) || BindRules.isTailscale(host) || TailnetDns.isMagicDns(host)
}
