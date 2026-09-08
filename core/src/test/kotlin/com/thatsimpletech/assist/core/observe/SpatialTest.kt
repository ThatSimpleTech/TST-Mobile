package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialTest {
    private val display = Rect(0, 0, 1080, 2400)

    private fun n(
        id: String, role: Role, left: Int, top: Int, w: Int, h: Int,
        label: String = "", window: Int = 0, clickable: Boolean = role == Role.BTN,
    ) = UiNode(
        identity = id, role = role, bounds = Rect(left, top, left + w, top + h),
        label = label, clickable = clickable, editable = role == Role.EDIT, window = window,
    )

    @Test
    fun trailingButtonIsTheRightmostOnTheEditRow() {
        val screen = Screen(
            "app", "A", display,
            nodes = listOf(
                n("e", Role.EDIT, 80, 2200, 800, 120, "box"),
                n("send", Role.BTN, 920, 2200, 140, 120, "Go"),
                n("mic", Role.BTN, 40, 2300, 80, 80, "Voice"),
                n("list", Role.LIST, 0, 100, 1080, 2000),
            ),
        )
        val obs = ObservationBuilder().observe(screen)
        val trail = Spatial.trailingButton(obs)
        assertNotNull(trail)
        assertEquals("Go", trail.node.label)
        assertTrue(Spatial.at(trail.node, display).startsWith("@"))
    }

    @Test
    fun keyboardWindowIsDroppedFromTheObservation() {
        val kbd = WindowInfo(id = 9, layer = 20, bounds = Rect(0, 1400, 1080, 2400), kind = WindowKind.IME)
        val app = WindowInfo(id = 1, layer = 1, bounds = display, kind = WindowKind.APP)
        val screen = Screen(
            "app", "A", display,
            nodes = listOf(
                n("e", Role.EDIT, 80, 1200, 800, 100, "box", window = 1),
                n("mic", Role.BTN, 900, 2000, 80, 80, "Voice", window = 9),
            ),
            windows = listOf(app, kbd),
        )
        val obs = ObservationBuilder().observe(screen)
        assertTrue(obs.keyboard)
        assertEquals(1, obs.lines.size)
        assertEquals("box", obs.lines[0].node.label)
        assertNull(obs.lines.firstOrNull { it.node.label == "Voice" })
    }

    @Test
    fun unlabeledUsesResourceIdTail() {
        val node = UiNode("s", Role.BTN, Rect(0, 0, 10, 10), resourceId = "com.app:id/voice_note_btn")
        assertEquals("voice note btn", Spatial.shownLabel(node))
        assertFalse(Spatial.compact(n("list", Role.LIST, 0, 0, 1080, 2000), display))
    }
}
