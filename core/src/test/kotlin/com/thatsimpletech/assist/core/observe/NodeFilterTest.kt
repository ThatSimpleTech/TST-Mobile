package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeFilterTest {
    private val display = Rect(0, 0, 1000, 2000)

    private fun btn(id: String, r: Rect, window: Int = 0, label: String = id, visible: Boolean = true, enabled: Boolean = true) =
        UiNode(identity = id, role = Role.BTN, bounds = r, label = label, clickable = true, window = window, visible = visible, enabled = enabled)

    @Test
    fun dropsInvisibleOffscreenEmptyAndDecorative() {
        val screen = Screen(
            "a", "b", display,
            nodes = listOf(
                btn("ok", Rect(0, 0, 100, 100)),
                btn("hidden", Rect(0, 0, 100, 100), visible = false),
                btn("off", Rect(2000, 2000, 2100, 2100)),
                btn("empty", Rect(10, 10, 10, 10)),
                btn("disabled", Rect(0, 200, 100, 300), enabled = false),
                UiNode("deco", Role.IMG, Rect(0, 400, 50, 450)),
                UiNode("text", Role.TEXT, Rect(0, 500, 500, 550), label = "hello"),
            ),
        )
        assertEquals(listOf("ok", "text"), NodeFilter.select(screen).map { it.identity })
    }

    @Test
    fun dialogCoversWhatIsUnderIt() {
        val dialog = WindowInfo(id = 2, layer = 5, bounds = Rect(100, 800, 900, 1200), kind = WindowKind.DIALOG)
        val app = WindowInfo(id = 1, layer = 1, bounds = display)
        val screen = Screen(
            "a", "b", display, windows = listOf(app, dialog),
            nodes = listOf(
                btn("under", Rect(200, 900, 300, 1000), window = 1),
                btn("beside", Rect(0, 1500, 100, 1600), window = 1),
                btn("dialog-ok", Rect(700, 1100, 850, 1180), window = 2),
                btn("dialog-cancel", Rect(500, 1100, 650, 1180), window = 2),
            ),
        )
        // Dialog buttons first (higher window), in reading order; the covered button is gone.
        assertEquals(listOf("dialog-cancel", "dialog-ok", "beside"), NodeFilter.select(screen).map { it.identity })
    }

    @Test
    fun dedupesRepeatedNodes() {
        val screen = Screen(
            "a", "b", display,
            nodes = listOf(
                btn("x", Rect(0, 0, 100, 100)),
                btn("x", Rect(0, 0, 100, 100)),
                btn("y", Rect(0, 0, 100, 100), label = "x"),
                btn("z", Rect(0, 200, 100, 300)),
            ),
        )
        assertEquals(listOf("x", "z"), NodeFilter.select(screen).map { it.identity })
    }
}
