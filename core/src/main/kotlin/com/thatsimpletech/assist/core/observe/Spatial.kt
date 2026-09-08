package com.thatsimpletech.assist.core.observe

/**
 * Geometry the model and the loop both use. Not app-specific: any compose row is an
 * edit plus the compact controls that share its vertical band.
 */
object Spatial {
    fun at(n: UiNode, display: Rect): String {
        if (display.width <= 0 || display.height <= 0) return ""
        val x = pct(n.bounds.centerX - display.left, display.width)
        val y = pct(n.bounds.centerY - display.top, display.height)
        return "@$x,$y"
    }

    fun compact(n: UiNode, display: Rect): Boolean =
        display.height > 0 && n.bounds.height < display.height / 4

    fun sameRow(a: UiNode, b: UiNode, display: Rect): Boolean {
        if (!compact(a, display) || !compact(b, display)) return false
        val band = maxOf(a.bounds.height, b.bounds.height)
        return kotlin.math.abs(a.bounds.centerY - b.bounds.centerY) <= band
    }

    /** Edit fields, and the compact controls that sit on the same row (Send, search, attach). */
    fun chrome(n: UiNode, all: List<UiNode>, display: Rect): Boolean {
        if (n.role == Role.EDIT || n.editable) return true
        if (!n.actionable || n.scrollable || n.role == Role.LIST) return false
        return all.any { e -> (e.role == Role.EDIT || e.editable) && sameRow(e, n, display) }
    }

    fun shownLabel(n: UiNode): String {
        if (n.label.isNotBlank()) return n.label
        return n.resourceId?.substringAfter('/')?.replace('_', ' ').orEmpty()
    }

    /** Rightmost button on the focused (or any) edit row — the usual submit. */
    fun trailingButton(obs: Observation): NodeLine? {
        val edit = obs.lines.firstOrNull { it.node.focused && it.node.editable } ?:
            obs.lines.firstOrNull { it.node.role == Role.EDIT || it.node.editable } ?: return null
        return obs.lines.filter {
            it.hint != edit.hint &&
                it.node.role == Role.BTN &&
                sameRow(edit.node, it.node, obs.display) &&
                it.node.bounds.centerX > edit.node.bounds.centerX
        }.maxByOrNull { it.node.bounds.centerX }
    }

    fun isIme(node: UiNode, screen: Screen): Boolean =
        screen.windows.any { it.id == node.window && it.kind == WindowKind.IME }

    /**
     * `@50,67` or `50,67` — screen percents, 0..99. Null if the token is a hint number/letter.
     */
    fun parseAt(text: String): Pair<Int, Int>? {
        val s = text.trim().removePrefix("@")
        val m = AT.matchEntire(s) ?: return null
        val x = m.groupValues[1].toInt()
        val y = m.groupValues[2].toInt()
        if (x > 99 || y > 99) return null
        return x to y
    }

    /**
     * Hint of the control at screen percents. Smallest node that contains the point
     * wins (a Send icon over the conversation list). Else the nearest center within
     * 15% of the display. Null when the observation is empty or the point is empty space.
     */
    fun nearestHint(obs: Observation, xPct: Int, yPct: Int): Int? {
        if (obs.lines.isEmpty() || obs.display.width <= 0 || obs.display.height <= 0) return null
        val x = obs.display.left + xPct * obs.display.width / 100
        val y = obs.display.top + yPct * obs.display.height / 100
        val hit = obs.lines.filter { it.node.bounds.contains(x, y) }
            .minByOrNull { it.node.bounds.area }
        if (hit != null) return hit.hint
        val limit = (maxOf(obs.display.width, obs.display.height) * 15L / 100).let { it * it }
        return obs.lines.minByOrNull { line ->
            val dx = line.node.bounds.centerX - x
            val dy = line.node.bounds.centerY - y
            dx.toLong() * dx + dy.toLong() * dy
        }?.takeIf { line ->
            val dx = line.node.bounds.centerX - x
            val dy = line.node.bounds.centerY - y
            dx.toLong() * dx + dy.toLong() * dy <= limit
        }?.hint
    }

    private fun pct(value: Int, span: Int): Int =
        if (span <= 0) 0 else (value * 100 / span).coerceIn(0, 99)

    private val AT = Regex("""^(\d{1,2})\s*,\s*(\d{1,2})$""")
}
