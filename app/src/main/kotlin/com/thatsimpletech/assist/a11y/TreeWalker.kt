package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect as ARect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.thatsimpletech.assist.core.observe.IdentityKey
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Role
import com.thatsimpletech.assist.core.observe.UiNode
import com.thatsimpletech.assist.core.observe.WindowInfo
import com.thatsimpletech.assist.core.observe.WindowKind

/**
 * Flattens the accessibility tree into core [UiNode]s and finds a node again by identity.
 * The identity rule lives in core ([IdentityKey]); this class only feeds it the same inputs
 * every time, which is what makes hints stable across steps (C3).
 */
class TreeWalker(private val service: AccessibilityService) {

    data class Walk(val nodes: List<UiNode>, val windows: List<WindowInfo>, val activePackage: String)

    fun walk(maxNodes: Int = MAX_NODES): Walk {
        val windows = service.windows ?: emptyList()
        val nodes = ArrayList<UiNode>()
        val infos = ArrayList<WindowInfo>()
        var active = ""
        for (w in windows) {
            val b = ARect().also { w.getBoundsInScreen(it) }
            infos += WindowInfo(id = w.id, layer = w.layer, bounds = b.core(), kind = kindOf(w.type))
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (w.isActive) active = pkg
            visit(root, pkg, w.id, w.layer, ArrayList(), nodes, maxNodes)
        }
        if (active.isEmpty()) active = service.rootInActiveWindow?.packageName?.toString() ?: ""
        return Walk(nodes, infos, active)
    }

    /** Identities only, for the cheap stale check before every execution. */
    fun identities(maxNodes: Int = MAX_NODES): List<String> = walk(maxNodes).nodes.map { it.identity }

    /** Re-walks the live tree and returns the node whose identity matches, or null if it is gone. */
    fun find(identity: String): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        for (w in windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            val hit = search(root, pkg, w.id, ArrayList(), identity, intArrayOf(0))
            if (hit != null) return hit
        }
        return null
    }

    private fun visit(
        node: AccessibilityNodeInfo, pkg: String, window: Int, layer: Int,
        path: ArrayList<Int>, out: ArrayList<UiNode>, maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        out += toUiNode(node, pkg, window, layer, path)
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            path.add(i)
            visit(child, pkg, window, layer, path, out, maxNodes)
            path.removeAt(path.size - 1)
        }
    }

    private fun search(
        node: AccessibilityNodeInfo, pkg: String, window: Int, path: ArrayList<Int>, wanted: String, budget: IntArray,
    ): AccessibilityNodeInfo? {
        if (budget[0]++ > MAX_NODES) return null
        if (identityOf(node, pkg, window, path) == wanted) return node
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            path.add(i)
            val hit = search(child, pkg, window, path, wanted, budget)
            path.removeAt(path.size - 1)
            if (hit != null) return hit
        }
        return null
    }

    private fun identityOf(node: AccessibilityNodeInfo, pkg: String, window: Int, path: List<Int>): String =
        IdentityKey.of(
            pkg = pkg, window = window, resourceId = node.viewIdResourceName,
            className = node.className?.toString(), path = path, label = labelOf(node),
        )

    private fun toUiNode(node: AccessibilityNodeInfo, pkg: String, window: Int, layer: Int, path: List<Int>): UiNode {
        val b = ARect().also { node.getBoundsInScreen(it) }
        val cls = node.className?.toString() ?: ""
        return UiNode(
            identity = identityOf(node, pkg, window, path),
            role = roleOf(cls, node),
            bounds = b.core(),
            label = if (node.isPassword) (node.contentDescription?.toString() ?: node.hintText?.toString() ?: "Password") else effectiveLabel(node),
            resourceId = node.viewIdResourceName,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            password = node.isPassword,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            window = window,
            layer = layer,
            itemCount = node.collectionInfo?.let { c -> if (c.rowCount > 0) c.rowCount else null },
        )
    }

    companion object {
        const val MAX_NODES = 2000

        /**
         * The label the person sees. A menu row, list row or bottom-sheet item is usually a
         * clickable container whose words live in a child TextView; without this a "Delete" row
         * would carry no label, and the sensitive-control rule could not see it (plan §4).
         * Identity still uses the node's own label, so the hint stays stable.
         */
        fun effectiveLabel(node: AccessibilityNodeInfo): String {
            val own = labelOf(node)
            if (own.isNotEmpty() || !(node.isClickable || node.isLongClickable)) return own
            return descendantLabel(node, depth = 0)
        }

        private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int): String {
            if (depth > 3) return ""
            val count = minOf(node.childCount, 20)
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                val l = labelOf(child)
                if (l.isNotEmpty()) return l
                val deeper = descendantLabel(child, depth + 1)
                if (deeper.isNotEmpty()) return deeper
            }
            return ""
        }

        /** Text first, then content description, then hint. Password contents are never read. */
        fun labelOf(node: AccessibilityNodeInfo): String {
            if (node.isPassword) return ""
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
            val d = node.contentDescription?.toString()?.trim()
            if (!d.isNullOrEmpty()) return d
            return node.hintText?.toString()?.trim() ?: ""
        }

        fun roleOf(className: String, node: AccessibilityNodeInfo): Role {
            val c = className.substringAfterLast('.')
            return when {
                node.isEditable || c == "EditText" || c.endsWith("EditText") -> Role.EDIT
                c == "Switch" || c == "SwitchCompat" || c.endsWith("Switch") || c == "ToggleButton" -> Role.SWITCH
                c == "CheckBox" || c == "RadioButton" || c.endsWith("CheckBox") -> Role.CHECK
                c == "Button" || c == "ImageButton" || c.endsWith("Button") -> Role.BTN
                c == "ImageView" || c.endsWith("ImageView") -> if (node.isClickable) Role.BTN else Role.IMG
                c == "RecyclerView" || c == "ListView" || c == "ScrollView" || c == "GridView" ||
                    c.endsWith("RecyclerView") || c.endsWith("ListView") || c.endsWith("ScrollView") ||
                    c == "ViewPager" || c.endsWith("ViewPager2") || node.collectionInfo != null -> Role.LIST
                c == "TabLayout" || c.contains("Tab") -> Role.TAB
                c.contains("Menu") -> Role.MENU
                c == "TextView" || c.endsWith("TextView") -> if (node.isClickable) Role.LINK else Role.TEXT
                node.isClickable -> Role.BTN
                else -> Role.VIEW
            }
        }

        fun kindOf(type: Int): WindowKind = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APP
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.IME
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.OVERLAY
            else -> WindowKind.OTHER
        }

        fun ARect.core(): Rect = Rect(left, top, right, bottom)
    }
}
