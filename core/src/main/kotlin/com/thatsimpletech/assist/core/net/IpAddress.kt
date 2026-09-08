package com.thatsimpletech.assist.core.net

/**
 * Literal IP parsing with no resolver behind it. `InetAddress.getByName` would look a
 * hostname up in DNS, and a bind check must never cause a network round trip; a string that
 * is not a literal address is simply not an address here.
 */
internal object IpAddress {
    /** 4 bytes for IPv4, 16 for IPv6, null when [host] is not a literal address. */
    fun parse(host: String): ByteArray? {
        val bare = strip(host)
        if (bare.isEmpty()) return null
        return if (':' in bare) parseV6(bare) else parseV4(bare)
    }

    /** Brackets and an IPv6 zone id (`fe80::1%wlan0`) are transport detail, not identity. */
    fun strip(host: String): String {
        var s = host.trim()
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        return s.substringBefore('%').lowercase()
    }

    /** `::ffff:a.b.c.d` is the IPv4 address in an IPv6 coat; judge it as IPv4. */
    fun unwrapMapped(bytes: ByteArray): ByteArray {
        if (bytes.size != 16) return bytes
        for (i in 0 until 10) if (bytes[i] != 0.toByte()) return bytes
        if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return bytes
        return bytes.copyOfRange(12, 16)
    }

    fun isZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    fun toDotted(v4: ByteArray): String = v4.joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun parseV4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    private fun parseV6(s: String): ByteArray? {
        val halves = s.split("::")
        if (halves.size > 2) return null
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val total = head.size + tail.size
        if (halves.size == 1 && total != 8) return null
        if (halves.size == 2 && total > 7) return null
        val out = ByteArray(16)
        var i = 0
        for (g in head) { out[i++] = (g shr 8).toByte(); out[i++] = g.toByte() }
        i = 16 - tail.size * 2
        for (g in tail) { out[i++] = (g shr 8).toByte(); out[i++] = g.toByte() }
        return out
    }

    /** Hex groups; a trailing dotted quad (`::ffff:1.2.3.4`) counts as two groups. */
    private fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        val pieces = part.split(':')
        for ((idx, p) in pieces.withIndex()) {
            if (idx == pieces.lastIndex && '.' in p) {
                val v4 = parseV4(p) ?: return null
                out.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                out.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
            } else {
                if (p.isEmpty() || p.length > 4 || !p.all { it in '0'..'9' || it in 'a'..'f' }) return null
                out.add(p.toInt(16))
            }
        }
        return out
    }
}
