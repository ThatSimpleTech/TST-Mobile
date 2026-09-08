package com.thatsimpletech.assist.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCommandTest {
    @Test
    fun playPauseNextPrevOnly() {
        val words = listOf(
            MediaCommand.Play,
            MediaCommand.Pause,
            MediaCommand.Next,
            MediaCommand.Prev,
        ).map { cmd ->
            when (cmd) {
                MediaCommand.Play -> "play"
                MediaCommand.Pause -> "pause"
                MediaCommand.Next -> "next"
                MediaCommand.Prev -> "prev"
            }
        }
        assertEquals(listOf("play", "pause", "next", "prev"), words)
    }

    @Test
    fun spotifyCommandsMatchMediaPlusPlayQuery() {
        fun word(cmd: SpotifyCommand): String = when (cmd) {
            is SpotifyCommand.Play -> "play"
            SpotifyCommand.Pause -> "pause"
            SpotifyCommand.Next -> "next"
            SpotifyCommand.Prev -> "prev"
        }
        assertEquals("pause", word(SpotifyCommand.Pause))
        assertEquals("next", word(SpotifyCommand.Next))
        assertEquals("prev", word(SpotifyCommand.Prev))
        assertEquals("play", word(SpotifyCommand.Play("jazz")))
        assertEquals("jazz", SpotifyCommand.Play("jazz").query)
    }
}
