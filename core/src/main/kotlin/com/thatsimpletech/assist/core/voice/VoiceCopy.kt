package com.thatsimpletech.assist.core.voice

/**
 * Spoken lines for the on-device TTS path (M4). The spend chip rides the same LastRun
 * string as the status bar; reading dollar amounts aloud is noise.
 */
object VoiceCopy {
    /**
     * Keep the outcome, drop the meter. [TaskController] appends `  ·  $…` after the
     * result; anything before that is what the person should hear.
     */
    fun spokenOutcome(line: String): String {
        val body = line.substringBefore("  ·  $").trim()
        return body.take(180)
    }
}
