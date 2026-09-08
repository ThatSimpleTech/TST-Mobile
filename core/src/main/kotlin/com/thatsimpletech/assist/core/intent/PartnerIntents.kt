package com.thatsimpletech.assist.core.intent

import com.thatsimpletech.assist.core.media.SpotifyCommand

/**
 * Documented partner intents as [IntentSpec] data (Workstream B).
 *
 * App Functions, if published, are a later executor ladder. These specs are
 * the intent rung. Missing both is an honest error in the app — never a
 * fall-through to `tap`/`type`. No [PhoneIntents.EXTRA_SKIP_UI] on any of these.
 */
object PartnerIntents {
    const val ACTION_VIEW = PhoneIntents.ACTION_VIEW
    const val ACTION_SENDTO = PhoneIntents.ACTION_SENDTO

    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"

    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_SPOTIFY = "com.spotify.music"
    const val PKG_GMAIL = "com.google.android.gm"

    /**
     * Spotify's documented widget actions for pause / next / previous.
     * If the extras surface is unpublished on a given build, the spec still
     * builds; `startActivity` in the app reports the honest miss.
     */
    const val SPOTIFY_PAUSE = "com.spotify.mobile.android.ui.widget.PAUSE"
    const val SPOTIFY_NEXT = "com.spotify.mobile.android.ui.widget.NEXT"
    const val SPOTIFY_PREV = "com.spotify.mobile.android.ui.widget.PREVIOUS"

    /** Click-to-chat: `https://wa.me/<digits>?text=`. Non-digits in [to] are dropped; resolve names first (TM-028). */
    fun whatsapp(to: String, body: String): IntentSpec {
        val digits = to.filter { it.isDigit() }
        val uri = "https://wa.me/$digits?text=${encodeUri(body)}"
        return IntentSpec(
            action = ACTION_VIEW,
            uri = uri,
            pkg = PKG_WHATSAPP,
            flags = PhoneIntents.FLAG_NEW_TASK,
        )
    }

    fun spotifyPlay(query: String): IntentSpec = IntentSpec(
        action = ACTION_VIEW,
        uri = "spotify:search:${encodeUri(query)}",
        pkg = PKG_SPOTIFY,
        flags = PhoneIntents.FLAG_NEW_TASK,
    )

    fun spotifyCommand(cmd: SpotifyCommand): IntentSpec = when (cmd) {
        is SpotifyCommand.Play -> spotifyPlay(cmd.query)
        SpotifyCommand.Pause -> widget(SPOTIFY_PAUSE)
        SpotifyCommand.Next -> widget(SPOTIFY_NEXT)
        SpotifyCommand.Prev -> widget(SPOTIFY_PREV)
    }

    fun gmailCompose(to: String, subject: String, body: String): IntentSpec = IntentSpec(
        action = ACTION_SENDTO,
        uri = "mailto:${encodeUri(to, allow = "@.+-_")}",
        extras = mapOf(
            EXTRA_SUBJECT to Extra.Str(subject),
            EXTRA_TEXT to Extra.Str(body),
        ),
        pkg = PKG_GMAIL,
        flags = PhoneIntents.FLAG_NEW_TASK,
    )

    private fun widget(action: String): IntentSpec = IntentSpec(
        action = action,
        pkg = PKG_SPOTIFY,
        flags = PhoneIntents.FLAG_NEW_TASK,
    )
}
