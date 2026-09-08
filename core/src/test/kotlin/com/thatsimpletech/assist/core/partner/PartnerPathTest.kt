package com.thatsimpletech.assist.core.partner

import com.thatsimpletech.assist.core.intent.PartnerIntents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PartnerPathTest {
    private val spec = PartnerIntents.whatsapp("5550100", "hi")

    @Test
    fun prefersAppFunctionWhenAvailable() {
        val path = PartnerLadder.choose("WhatsApp", PartnerIntents.PKG_WHATSAPP, "send", true, spec)
        assertIs<PartnerPath.AppFunction>(path)
        assertEquals(PartnerIntents.PKG_WHATSAPP, path.pkg)
        assertEquals("send", path.functionId)
        assertEquals("app-function", path.kind)
    }

    @Test
    fun fallsBackToIntentWhenUnpublished() {
        val unpublished = PartnerLadder.choose("WhatsApp", PartnerIntents.PKG_WHATSAPP, null, false, spec)
        assertIs<PartnerPath.Intent>(unpublished)
        assertEquals(spec, unpublished.spec)
        assertEquals("intent", unpublished.kind)

        val knownIdButOff = PartnerLadder.choose("WhatsApp", PartnerIntents.PKG_WHATSAPP, "send", false, spec)
        assertIs<PartnerPath.Intent>(knownIdButOff)
        assertEquals("intent", knownIdButOff.kind)
    }

    @Test
    fun missingBothIsHonestError() {
        val path = PartnerLadder.choose("WhatsApp", PartnerIntents.PKG_WHATSAPP, null, false, null)
        assertIs<PartnerPath.Missing>(path)
        assertEquals("missing", path.kind)
        assertTrue("UI driving is not in this version" in path.reason, path.reason)
        assertTrue("WhatsApp" in path.reason, path.reason)
        assertEquals(PartnerLadder.missingMessage("WhatsApp"), path.reason)
    }

    @Test
    fun neverReturnsTreeDrive() {
        val paths = listOf(
            PartnerLadder.choose("WhatsApp", PartnerIntents.PKG_WHATSAPP, "send", true, spec),
            PartnerLadder.choose("Spotify", PartnerIntents.PKG_SPOTIFY, null, false, spec),
            PartnerLadder.choose("Gmail", PartnerIntents.PKG_GMAIL, null, false, null),
        )
        val kinds = paths.map { path ->
            // Exhaustive: a tree-drive variant would fail to compile here.
            when (path) {
                is PartnerPath.AppFunction -> "app-function"
                is PartnerPath.Intent -> "intent"
                is PartnerPath.Missing -> "missing"
            }
        }
        assertEquals(listOf("app-function", "intent", "missing"), kinds)
        assertTrue(kinds.none { it == "tree" || it == "tap" || it == "type" })
    }
}
