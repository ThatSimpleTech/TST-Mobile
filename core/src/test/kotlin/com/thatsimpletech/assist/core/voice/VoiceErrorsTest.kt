package com.thatsimpletech.assist.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceErrorsTest {
    @Test
    fun permissionAndSilenceArePlain() {
        assertEquals("mic permission is off", VoiceErrors.listen(VoiceErrors.PERMISSION))
        assertEquals("heard nothing", VoiceErrors.listen(VoiceErrors.NO_MATCH))
        assertEquals("waiting for speech timed out", VoiceErrors.listen(VoiceErrors.SPEECH_TIMEOUT))
    }

    @Test
    fun networkCodesDoNotClaimACloudCall() {
        val line = VoiceErrors.listen(VoiceErrors.SERVER)
        assertTrue("on-device" in line)
        assertFalse("model" in line)
        assertFalse("cloud" in line.lowercase())
    }
}
