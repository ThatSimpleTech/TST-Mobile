package com.thatsimpletech.assist.core.intent

/**
 * An Android intent described as data, with no `android.*` types.
 *
 * App executors (`IntentExecutor` in a later PR) copy these fields onto
 * `android.content.Intent`. Core never imports that class.
 *
 * [flags] is a bitmask. Named bits live on [PhoneIntents] (`FLAG_NEW_TASK`);
 * the app calls `intent.addFlags(spec.flags)` and also applies
 * `FLAG_ACTIVITY_NEW_TASK` itself if the spec left flags at 0.
 */
data class IntentSpec(
    val action: String,
    val uri: String? = null,
    val mimeType: String? = null,
    val extras: Map<String, Extra> = emptyMap(),
    val pkg: String? = null,
    val flags: Int = 0,
)

/** Extra values the app copies with `putExtra`. No Parcelable, no `android.*`. */
sealed class Extra {
    data class Str(val value: String) : Extra()
    data class IntNum(val value: Int) : Extra()
    data class Bool(val value: Boolean) : Extra()
    data class LongNum(val value: Long) : Extra()
}

/**
 * Percent-encode the way `android.net.Uri.encode` does (ASCII unreserved plus
 * `_ - . ! ~ * ' ( )`, UTF-8 `%XX` for the rest) so the app can `Uri.parse`
 * these strings without a second encoding pass.
 */
internal fun encodeUri(value: String, allow: String = ""): String {
    val always = "_-!.~'()*"
    fun allowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in always || c in allow
    val hex = "0123456789ABCDEF"
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val start = i
        while (i < value.length && allowed(value[i])) i++
        if (i > start) out.append(value, start, i)
        if (i >= value.length) break
        val bytes = value.substring(i, i + 1).toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            out.append('%').append(hex[u shr 4]).append(hex[u and 0xF])
        }
        i++
    }
    return out.toString()
}
