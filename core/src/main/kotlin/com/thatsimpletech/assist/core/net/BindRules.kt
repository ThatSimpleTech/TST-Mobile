package com.thatsimpletech.assist.core.net

import java.io.IOException

/** What a host may be bound as. Mirrors TST Desk's `validate_interface` plus `tailscale_bind` (plan §1, "No server"). */
sealed interface BindClass {
    data object Loopback : BindClass
    /** [address] is the normalized literal: dotted IPv4 (mapped forms unwrapped), or the zone-stripped IPv6. */
    data class Tailscale(val address: String) : BindClass
    data class Refused(val reason: String) : BindClass
}

/** A bind that the rules would not allow. Same failure class as the desktop's ValueError. */
class BindRefusedException(reason: String) : IllegalArgumentException(reason)

/** One bound socket. The app wraps its ServerSocket; tests use a fake. */
interface Listener : AutoCloseable {
    val host: String
    val port: Int
}

/** Opens a listener, or throws IOException when the OS will not. Port 0 means ephemeral. */
fun interface ListenerFactory {
    @Throws(IOException::class)
    fun listen(host: String, port: Int): Listener
}

/** Loopback always, plus at most one Tailscale listener on the same port. */
class BoundListeners(val loopback: Listener, val extra: Listener?) : AutoCloseable {
    val port: Int get() = loopback.port
    val hosts: List<String> get() = listOfNotNull(loopback.host, extra?.host)
    override fun close() {
        extra?.close()
        loopback.close()
    }
}

/**
 * The bind rules for the device-side socket. Loopback is always bound; the only other
 * legal listener is one Tailscale address, and only because the person named it. Never
 * 0.0.0.0 or ::. On Android the VPN is tun0, so there is no interface-name path as on the
 * desktop (tailscale*, utun*): an address is Tailscale by CIDR or it is not.
 */
object BindRules {
    const val LOOPBACK_HOST = "127.0.0.1"

    /** Every spelling of "all interfaces" the desktop's ws.py and remote-connect.ts refuse. */
    val UNSPECIFIED_HOSTS: Set<String> = setOf("0.0.0.0", "::", "", "*", "[::]", "::ffff:0.0.0.0")

    fun isUnspecified(host: String): Boolean {
        val bare = IpAddress.strip(host)
        if (bare in UNSPECIFIED_HOSTS) return true
        val bytes = IpAddress.parse(bare) ?: return false
        return IpAddress.isZero(IpAddress.unwrapMapped(bytes))
    }

    /** 127/8, ::1, and the literal word localhost (also through the IPv4-mapped coat). */
    fun isLoopback(host: String): Boolean {
        val bare = IpAddress.strip(host)
        if (bare == "localhost") return true
        val bytes = IpAddress.unwrapMapped(IpAddress.parse(bare) ?: return false)
        return when (bytes.size) {
            4 -> bytes[0] == 127.toByte()
            else -> bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
        }
    }

    /** CGNAT 100.64.0.0/10 or Tailscale ULA fd7a:115c:a1e0::/48, zone stripped, mapped IPv4 unwrapped. */
    fun isTailscale(host: String): Boolean {
        val bytes = IpAddress.unwrapMapped(IpAddress.parse(IpAddress.strip(host)) ?: return false)
        return when (bytes.size) {
            4 -> bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xc0) == 0x40
            else -> bytes[0] == 0xfd.toByte() && bytes[1] == 0x7a.toByte() &&
                bytes[2] == 0x11.toByte() && bytes[3] == 0x5c.toByte() &&
                bytes[4] == 0xa1.toByte() && bytes[5] == 0xe0.toByte()
        }
    }

    fun classify(host: String): BindClass {
        if (isUnspecified(host)) {
            return BindClass.Refused("refusing to bind to '${host.trim()}': never 0.0.0.0 / :: (loopback or one Tailscale address only)")
        }
        if (isLoopback(host)) return BindClass.Loopback
        if (isTailscale(host)) {
            val bytes = IpAddress.unwrapMapped(IpAddress.parse(IpAddress.strip(host))!!)
            val normalized = if (bytes.size == 4) IpAddress.toDotted(bytes) else IpAddress.strip(host)
            return BindClass.Tailscale(normalized)
        }
        return BindClass.Refused("refusing to bind to '${host.trim()}': not a Tailscale address (need 100.64.0.0/10 or fd7a:115c:a1e0::/48)")
    }

    /**
     * Bind loopback on an ephemeral port, then the extra Tailscale listener on the same port.
     * [extraHost] null means loopback only. Anything but a Tailscale address is refused before
     * a socket opens, and if the second listener fails the loopback one is closed too: a
     * half-bound server is not the server the person configured.
     */
    @Throws(IOException::class)
    fun bind(factory: ListenerFactory, extraHost: String?): BoundListeners {
        val extra = extraHost?.let {
            when (val c = classify(it)) {
                is BindClass.Tailscale -> c.address
                is BindClass.Loopback -> throw BindRefusedException("refusing extra bind to '${it.trim()}': loopback is already bound; the extra listener must be a Tailscale address")
                is BindClass.Refused -> throw BindRefusedException(c.reason)
            }
        }
        val loopback = factory.listen(LOOPBACK_HOST, 0)
        if (extra == null) return BoundListeners(loopback, null)
        val second = try {
            factory.listen(extra, loopback.port)
        } catch (e: IOException) {
            loopback.close()
            throw e
        }
        return BoundListeners(loopback, second)
    }
}
