package com.thatsimpletech.assist.core.observe

/**
 * Plan C3: hints must not shift between steps. A control keeps its number for as long as it
 * stays on screen. A number freed when its control disappears is quarantined for a step so
 * the next new control cannot inherit a number the model still remembers.
 */
class HintTable(private val quarantineSteps: Int = 1) {
    private val byIdentity = LinkedHashMap<String, Int>()
    private val inUse = HashSet<Int>()
    private val freedAt = HashMap<Int, Int>()
    private var step = 0

    val size: Int get() = byIdentity.size

    /** Assigns hints for this step's nodes, in list order. Returns identity → hint. */
    fun assign(nodes: List<UiNode>): Map<String, Int> {
        step++
        val present = nodes.mapTo(LinkedHashSet()) { it.identity }

        val gone = byIdentity.keys.filter { it !in present }
        for (id in gone) {
            val n = byIdentity.remove(id) ?: continue
            inUse.remove(n)
            freedAt[n] = step
        }
        freedAt.entries.removeIf { step - it.value > quarantineSteps }

        val result = LinkedHashMap<String, Int>()
        var next = 1
        for (id in present) {
            val existing = byIdentity[id]
            if (existing != null) {
                result[id] = existing
                continue
            }
            while (next in inUse || freedAt.containsKey(next)) next++
            byIdentity[id] = next
            inUse.add(next)
            result[id] = next
            next++
        }
        return result
    }

    fun hintOf(identity: String): Int? = byIdentity[identity]

    fun identityOf(hint: Int): String? = byIdentity.entries.firstOrNull { it.value == hint }?.key

    fun reset() {
        byIdentity.clear()
        inUse.clear()
        freedAt.clear()
        step = 0
    }
}
