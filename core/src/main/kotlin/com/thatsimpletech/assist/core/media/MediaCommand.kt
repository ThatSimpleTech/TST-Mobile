package com.thatsimpletech.assist.core.media

/** Playback command for `media play|pause|next|prev`. Seek is not M2. */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Next : MediaCommand
    data object Prev : MediaCommand
}

/** Partner command for `spotify play "<q>"` / `pause` / `next` / `prev`. Seek is not M2. */
sealed interface SpotifyCommand {
    data class Play(val query: String) : SpotifyCommand
    data object Pause : SpotifyCommand
    data object Next : SpotifyCommand
    data object Prev : SpotifyCommand
}
