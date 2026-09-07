package com.thatsimpletech.assist.task

/**
 * Wires a task run: observer, executor, planner, approvals, meter and audit. Filled in once
 * the core loop, meter and audit modules are merged; until then a run reports that plainly.
 */
object TaskController {
    suspend fun run(goal: String, onMeter: (String) -> Unit): String {
        onMeter("not wired")
        return "stopped: task runner not wired yet"
    }
}
