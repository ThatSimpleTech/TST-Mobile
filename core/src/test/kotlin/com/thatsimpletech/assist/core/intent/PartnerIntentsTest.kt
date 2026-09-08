package com.thatsimpletech.assist.core.intent

import com.thatsimpletech.assist.core.media.SpotifyCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartnerIntentsTest {
    @Test
    fun whatsappWaMeUriEncodesBody() {
        val spec = PartnerIntents.whatsapp("+1 (555) 0100", "On my way!")
        assertEquals(PhoneIntents.ACTION_VIEW, spec.action)
        assertEquals(PartnerIntents.PKG_WHATSAPP, spec.pkg)
        assertEquals("https://wa.me/15550100?text=On%20my%20way!", spec.uri)
    }

    @Test
    fun spotifySearchUri() {
        val spec = PartnerIntents.spotifyPlay("pink floyd")
        assertEquals(PhoneIntents.ACTION_VIEW, spec.action)
        assertEquals("spotify:search:pink%20floyd", spec.uri)
        assertEquals(PartnerIntents.PKG_SPOTIFY, spec.pkg)
        val viaCommand = PartnerIntents.spotifyCommand(SpotifyCommand.Play("jazz"))
        assertEquals("spotify:search:jazz", viaCommand.uri)
        assertEquals(PartnerIntents.PKG_SPOTIFY, viaCommand.pkg)
    }

    @Test
    fun gmailMailtoLocksGmailPackage() {
        val spec = PartnerIntents.gmailCompose("a@b.com", "Hi", "See you")
        assertEquals(PhoneIntents.ACTION_SENDTO, spec.action)
        assertEquals("mailto:a@b.com", spec.uri)
        assertEquals(PartnerIntents.PKG_GMAIL, spec.pkg)
        assertEquals(Extra.Str("Hi"), spec.extras[PartnerIntents.EXTRA_SUBJECT])
        assertEquals(Extra.Str("See you"), spec.extras[PartnerIntents.EXTRA_TEXT])
    }

    @Test
    fun noPartnerSpecContainsSkipUi() {
        val specs = listOf(
            PartnerIntents.whatsapp("15550100", "hi"),
            PartnerIntents.spotifyPlay("jazz"),
            PartnerIntents.spotifyCommand(SpotifyCommand.Pause),
            PartnerIntents.spotifyCommand(SpotifyCommand.Next),
            PartnerIntents.spotifyCommand(SpotifyCommand.Prev),
            PartnerIntents.gmailCompose("a@b.com", "s", "b"),
        )
        for (spec in specs) {
            assertTrue(skipUiKeys(spec).isEmpty(), "skip-ui on $spec")
            val skipTrue = spec.extras.values.any { it == Extra.Bool(true) && spec.extras.keys.any { k -> k.contains("SKIP_UI") } }
            assertTrue(!skipTrue, spec.toString())
        }
        val pause = PartnerIntents.spotifyCommand(SpotifyCommand.Pause)
        assertEquals(PartnerIntents.SPOTIFY_PAUSE, pause.action)
        assertEquals(PartnerIntents.PKG_SPOTIFY, pause.pkg)
    }
}
