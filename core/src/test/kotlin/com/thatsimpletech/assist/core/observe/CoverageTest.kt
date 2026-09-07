package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageTest {
    private val display = Rect(0, 0, 1000, 2000) // 2 megapixels

    private fun btn(i: Int, label: String = "b$i") =
        UiNode("n$i", Role.BTN, Rect(0, i * 10, 100, i * 10 + 10), label = label, clickable = true)

    @Test
    fun knownBadAppsAreLow() {
        val d = CoverageDetector(knownBad = setOf("com.game"))
        val s = Screen("com.game", "Play", display, nodes = (1..20).map { btn(it) })
        assertEquals(CoverageLevel.LOW, d.assess(s, s.nodes).level)
    }

    @Test
    fun emptyIsNone() {
        val s = Screen("com.x", "A", display, nodes = emptyList())
        assertEquals(CoverageLevel.NONE, CoverageDetector().assess(s, emptyList()).level)
    }

    @Test
    fun sparseTreesAreLow() {
        val s = Screen("com.x", "A", display, nodes = listOf(btn(1), btn(2)))
        assertEquals(CoverageLevel.LOW, CoverageDetector(minNodesPerMegapixel = 3.0).assess(s, s.nodes).level)
        val ok = Screen("com.x", "A", display, nodes = (1..10).map { btn(it) })
        assertEquals(CoverageLevel.OK, CoverageDetector(minNodesPerMegapixel = 3.0).assess(ok, ok.nodes).level)
    }

    @Test
    fun mostlyUnlabeledControlsAreLow() {
        val s = Screen("com.x", "A", display, nodes = (1..10).map { btn(it, label = if (it <= 7) "" else "b$it") })
        val c = CoverageDetector().assess(s, s.nodes)
        assertEquals(CoverageLevel.LOW, c.level)
        assertEquals("unlabeled controls", c.reason)
    }
}
