package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A recycled list row keeps its identity when its contents change; the fingerprint must not. */
class FingerprintContentTest {
    private val display = Rect(0, 0, 1080, 2400)
    private fun row(label: String) = UiNode("row|id:row|3", Role.BTN, Rect(0, 100, 1080, 200), label = label, clickable = true)
    private fun text(label: String) = UiNode("t", Role.TEXT, Rect(0, 300, 1080, 400), label = label)

    @Test
    fun anActionableNodeWithNewContentsChangesTheFingerprint() {
        val a = Fingerprint.ofNodes("app", "A", listOf(row("Delete conversation with Maria")))
        val b = Fingerprint.ofNodes("app", "A", listOf(row("Call Maria")))
        assertNotEquals(a, b)
    }

    @Test
    fun aTextNodeWithNewContentsDoesNot() {
        val a = Fingerprint.ofNodes("app", "A", listOf(row("Send"), text("6:42 PM")))
        val b = Fingerprint.ofNodes("app", "A", listOf(row("Send"), text("6:43 PM")))
        assertEquals(a, b)
    }

    @Test
    fun aToggleFlipChangesTheFingerprint() {
        val sw = UiNode("sw", Role.SWITCH, Rect(0, 0, 100, 50), label = "Wi-Fi", checkable = true, checked = false)
        val a = Fingerprint.ofNodes("app", "A", listOf(sw))
        val b = Fingerprint.ofNodes("app", "A", listOf(sw.copy(checked = true)))
        assertNotEquals(a, b)
    }

    @Test
    fun theBuilderUsesTheSameRule() {
        val screen = Screen("app", "A", display, listOf(row("Send")))
        val builder = ObservationBuilder(coverage = CoverageDetector(minNodesPerMegapixel = 0.0))
        val obs = builder.observe(screen)
        assertEquals(Fingerprint.ofNodes("app", "A", NodeFilter.select(screen)), obs.fingerprint)
    }
}
