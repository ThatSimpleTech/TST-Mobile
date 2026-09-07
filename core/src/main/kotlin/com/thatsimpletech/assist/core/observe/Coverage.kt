package com.thatsimpletech.assist.core.observe

enum class CoverageLevel(val word: String) { OK("ok"), LOW("low"), NONE("none") }

data class Coverage(val level: CoverageLevel, val reason: String) {
    companion object {
        val OK = Coverage(CoverageLevel.OK, "")
    }
}

/**
 * Plan C1: decide whether the accessibility tree describes the screen well enough to
 * drive. Below threshold the loop falls back to a labeled screenshot (vision brain) or
 * tells a text-only brain "screen not readable here".
 */
class CoverageDetector(
    private val knownBad: Set<String> = emptySet(),
    private val minNodesPerMegapixel: Double = 3.0,
    private val maxUnlabeledShare: Double = 0.5,
) {
    fun assess(screen: Screen, selected: List<UiNode>): Coverage {
        if (screen.app in knownBad) return Coverage(CoverageLevel.LOW, "known canvas app")
        if (selected.isEmpty()) return Coverage(CoverageLevel.NONE, "no readable nodes")
        val megapixels = screen.display.area / 1_000_000.0
        if (megapixels > 0 && selected.size / megapixels < minNodesPerMegapixel) {
            return Coverage(CoverageLevel.LOW, "sparse tree")
        }
        val actionable = selected.filter { it.actionable }
        if (actionable.size >= 4) {
            val unlabeled = actionable.count { it.label.isBlank() }
            if (unlabeled.toDouble() / actionable.size > maxUnlabeledShare) {
                return Coverage(CoverageLevel.LOW, "unlabeled controls")
            }
        }
        return Coverage.OK
    }
}
