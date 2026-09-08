package com.thatsimpletech.assist.core.observe

/**
 * Plan C4: keep what is visible and either actionable or informative, respect window
 * z-order, drop duplicates. Order is top window first, then reading order.
 */
object NodeFilter {
    fun select(screen: Screen): List<UiNode> {
        val topLayerByWindow = screen.windows.associate { it.id to it }
        val display = screen.display

        val candidates = screen.nodes.asSequence()
            .filter { it.visible && it.enabled }
            .filter { !it.bounds.isEmpty && it.bounds.intersects(display) }
            .filter { it.actionable || it.label.isNotBlank() }
            .toList()

        // Occlusion: a node fully inside a higher-layer window's bounds is covered by it.
        val covering = screen.windows.sortedByDescending { it.layer }
        val visible = candidates.filter { node ->
            val own = topLayerByWindow[node.window]?.layer ?: node.layer
            covering.none { w -> w.layer > own && w.bounds.contains(node.bounds) }
        }

        // Dedupe: same identity, then same box with the same role and label.
        val seenIdentity = HashSet<String>()
        val seenBox = HashSet<Triple<Rect, Role, String>>()
        val unique = visible.filter { n ->
            seenIdentity.add(n.identity) && seenBox.add(Triple(n.bounds, n.role, n.label))
        }

        return unique.sortedWith(
            compareByDescending<UiNode> { topLayerByWindow[it.window]?.layer ?: it.layer }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left },
        )
    }
}
