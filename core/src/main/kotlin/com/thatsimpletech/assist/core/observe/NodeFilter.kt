package com.thatsimpletech.assist.core.observe

/**
 * Plan C4: keep what is visible and either actionable or informative, respect window
 * z-order, drop duplicates. Order is top window first, then reading order.
 *
 * Keyboard (IME) keys are not app controls — they live in another window and steal taps
 * (voice-input mic vs Send). Clickable parents that fully contain a smaller actionable
 * child are dropped so a tap hits the child, not the slab.
 */
object NodeFilter {
    fun select(screen: Screen): List<UiNode> {
        val topLayerByWindow = screen.windows.associate { it.id to it }
        val display = screen.display
        val imeWindows = screen.windows.filter { it.kind == WindowKind.IME }.map { it.id }.toSet()

        val candidates = screen.nodes.asSequence()
            .filter { it.visible && it.enabled }
            .filter { !it.bounds.isEmpty && it.bounds.intersects(display) }
            .filter { it.actionable || it.label.isNotBlank() }
            .filter { it.window !in imeWindows }
            .toList()

        val covering = screen.windows.sortedByDescending { it.layer }
        val visible = candidates.filter { node ->
            val own = topLayerByWindow[node.window]?.layer ?: node.layer
            covering.none { w -> w.layer > own && w.bounds.contains(node.bounds) }
        }

        val seenIdentity = HashSet<String>()
        val seenBox = HashSet<Triple<Rect, Role, String>>()
        val unique = visible.filter { n ->
            seenIdentity.add(n.identity) && seenBox.add(Triple(n.bounds, n.role, n.label))
        }

        val withoutSlabs = unique.filter { n ->
            if (n.scrollable || n.editable || n.role == Role.LIST || n.role == Role.EDIT) true
            else unique.none { c ->
                c.identity != n.identity && n.bounds.contains(c.bounds) &&
                    c.bounds.area < n.bounds.area && c.actionable
            }
        }

        return withoutSlabs.sortedWith(
            compareByDescending<UiNode> { topLayerByWindow[it.window]?.layer ?: it.layer }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left },
        )
    }
}
