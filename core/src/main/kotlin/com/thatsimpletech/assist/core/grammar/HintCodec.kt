package com.thatsimpletech.assist.core.grammar

/**
 * How hint numbers appear in the grammar. Numeric is the default; letters are a profile
 * option the eval suite decides (plan §7, C5). Both map to the same 1-based hint index.
 */
sealed interface HintCodec {
    fun encode(hint: Int): String
    fun decode(token: String): Int?

    data object Numeric : HintCodec {
        override fun encode(hint: Int): String = hint.toString()
        override fun decode(token: String): Int? =
            if (token.isNotEmpty() && token.all { it.isDigit() }) token.toIntOrNull()?.takeIf { it >= 1 } else null
    }

    /** a..z, then aa..az, ba.., like spreadsheet columns. */
    data object Letters : HintCodec {
        override fun encode(hint: Int): String {
            require(hint >= 1) { "hint must be >= 1" }
            var n = hint
            val sb = StringBuilder()
            while (n > 0) {
                n--
                sb.append('a' + (n % 26))
                n /= 26
            }
            return sb.reverse().toString()
        }

        override fun decode(token: String): Int? {
            if (token.isEmpty() || !token.all { it in 'a'..'z' }) return null
            var n = 0
            for (c in token) n = n * 26 + (c - 'a' + 1)
            return n
        }
    }
}
