package com.thatsimpletech.assist.core.net

import java.security.SecureRandom

/**
 * The socket credential: 32 random bytes as 64 lowercase hex, the same shape as TST Desk's
 * `generate_token`. It is the only thing standing between a peer and the device socket, so
 * it never prints itself and is only compared in constant time.
 */
class AuthToken private constructor(private val value: String) {

    /** The one deliberate way out: for the port file and the hello frame. Never for a log line. */
    fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is AuthToken && equals(value, other.value)
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "<token>"

    companion object {
        const val BYTES = 32
        private val random = SecureRandom()

        fun mint(): AuthToken {
            val bytes = ByteArray(BYTES)
            random.nextBytes(bytes)
            return AuthToken(bytes.joinToString("") { "%02x".format(it) })
        }

        /** Wrap a token read back from the port file or typed by the person. */
        fun of(hex: String): AuthToken = AuthToken(hex)

        /**
         * Constant-time comparison on UTF-8 bytes. Runs over the longer input every time, so
         * neither an early mismatch nor a length difference shortens the loop.
         */
        fun equals(a: String, b: String): Boolean {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            var diff = x.size xor y.size
            val n = maxOf(x.size, y.size)
            for (i in 0 until n) {
                val xi = if (i < x.size) x[i].toInt() else 0
                val yi = if (i < y.size) y[i].toInt() else 0
                diff = diff or (xi xor yi)
            }
            return diff == 0
        }
    }
}
