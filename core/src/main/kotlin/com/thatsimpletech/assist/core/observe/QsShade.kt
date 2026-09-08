package com.thatsimpletech.assist.core.observe

/**
 * Best-effort Quick Settings shade detection (Workstream H). Prefer false negatives:
 * a miss becomes `app-not-allowlisted` (fail-closed). A false positive would turn a
 * random SystemUI dialog into a Tier 2 tile tap.
 *
 * Markers are substrings the Pixel 7 Pro (and similar) actually report. They are
 * not a promise of class names on every OEM.
 */
object QsShade {
    const val SYSTEM_UI = "com.android.systemui"

    private val MARKERS = listOf("quicksettings", "qspanel", "notificationshade")

    fun detected(app: String, activity: String, titlesAndClasses: List<String>): Boolean {
        if (app != SYSTEM_UI) return false
        return (titlesAndClasses + activity).any { haystack ->
            val h = haystack.lowercase()
            MARKERS.any { it in h }
        }
    }
}
