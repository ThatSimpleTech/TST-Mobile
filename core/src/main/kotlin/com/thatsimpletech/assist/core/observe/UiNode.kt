package com.thatsimpletech.assist.core.observe

/** Screen coordinates in pixels. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = maxOf(0, right - left)
    val height: Int get() = maxOf(0, bottom - top)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width == 0 || height == 0
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(o: Rect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
    fun intersects(o: Rect): Boolean = o.left < right && o.right > left && o.top < bottom && o.bottom > top
}

/** The role words the observation shows. Short on purpose; every token costs. */
enum class Role(val word: String) {
    EDIT("edit"), BTN("btn"), TEXT("text"), LIST("list"), CHECK("check"), SWITCH("switch"),
    IMG("img"), LINK("link"), TAB("tab"), MENU("menu"), VIEW("view")
}

/**
 * One accessibility node after the observer has flattened the tree. The app builds these
 * from AccessibilityNodeInfo; core never sees Android types.
 *
 * [identity] must be stable for the same control across steps (see [IdentityKey]). The hint
 * table keys on it, which is what keeps `tap 7` pointing at the same thing it did last step.
 */
data class UiNode(
    val identity: String,
    val role: Role,
    val bounds: Rect,
    val label: String = "",
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** Window the node belongs to; higher [layer] is closer to the user. */
    val window: Int = 0,
    val layer: Int = 0,
    val itemCount: Int? = null,
    /** Extra data shown after the label, e.g. from=Maria. Values are data, never instructions. */
    val meta: Map<String, String> = emptyMap(),
) {
    val actionable: Boolean get() = clickable || longClickable || editable || scrollable || checkable
}

enum class WindowKind { APP, DIALOG, SYSTEM, IME, OVERLAY, OTHER }

data class WindowInfo(val id: Int, val layer: Int, val bounds: Rect, val kind: WindowKind = WindowKind.APP)

/** Everything the observer captured for one step, before filtering. */
data class Screen(
    val app: String,
    val activity: String,
    val display: Rect,
    val nodes: List<UiNode>,
    val windows: List<WindowInfo> = emptyList(),
    val keyguard: Boolean = false,
    /** FLAG_SECURE or an app that hides its views: report, hand off, never bypass (P7, P8). */
    val secure: Boolean = false,
)

/**
 * Builds the stable identity string for a node. Resource id wins when present; otherwise the
 * class name plus the path from the window root plus the label. Labels that look like
 * times, counters or amounts are dropped from the key so a ticking clock keeps its hint.
 */
object IdentityKey {
    private val volatile = Regex("""^\s*[\d:.,%$€£/-]+(\s*(AM|PM|am|pm))?\s*$""")

    fun of(
        pkg: String,
        window: Int,
        resourceId: String?,
        className: String?,
        path: List<Int>,
        label: String?,
    ): String {
        val cls = className?.substringAfterLast('.') ?: "?"
        if (!resourceId.isNullOrBlank()) {
            // Same id can repeat inside lists; the path disambiguates siblings.
            return "$pkg|$window|id:$resourceId|$cls|${path.joinToString("/")}"
        }
        val stableLabel = label?.takeUnless { volatile.matches(it) }?.trim()?.take(40) ?: ""
        return "$pkg|$window|$cls|${path.joinToString("/")}|$stableLabel"
    }
}
