package com.thatsimpletech.assist.core.steering

import java.nio.file.Files
import java.nio.file.Path

/** Why a write was refused. [code] is the machine-readable half, for the audit row and the model. */
data class Refusal(val code: String, val reason: String)

/**
 * The phone's copy of TST Desk's boundary guard plus `is_steering_write` (plan §1, "The agent
 * cannot rewrite its own rules"; §6). The rules files are refused before any approval and no
 * approval can grant them. Everything is judged on the canonical path, so `..`, symlinks and
 * case games do not reach a different answer than the plain spelling would.
 */
class RulesBoundary(root: Path) {
    private val root: Path = canonical(root.toAbsolutePath().normalize())

    /** Null when the write may proceed to the policy enforcer; a [Refusal] otherwise. */
    fun check(pathToWrite: String): Refusal? = check(Path.of(pathToWrite))

    fun check(pathToWrite: Path): Refusal? {
        // A relative path is joined to the files root, never to the process cwd (desktop TD-608).
        val joined = if (pathToWrite.isAbsolute) pathToWrite else root.resolve(pathToWrite)
        val lexical = joined.normalize()
        val real = canonical(lexical)
        if (!real.startsWith(root)) {
            return Refusal(OUTSIDE_ROOT, "path outside the files root: $lexical")
        }
        // Judge both spellings: the one the model wrote (a symlinked `profiles/` still
        // *means* profiles) and the one the filesystem will land on (`notes.md -> ASSISTANT.md`).
        val steering = steeringReason(parts(root.relativize(lexical))) ?: steeringReason(parts(root.relativize(real)))
        if (steering != null) return Refusal(STEERING_FILE, steering)
        return null
    }

    private fun steeringReason(rel: List<String>): String? {
        if (rel.isEmpty()) return null
        val folded = rel.map(::fold)
        if (folded.last() in STEERING_BASENAMES) {
            return "${rel.last()} is a rules file and is read-only to the agent"
        }
        if (folded.first() in STEERING_DIRS) {
            return "${rel.first()}/ holds rules files and is read-only to the agent"
        }
        return null
    }

    companion object {
        const val OUTSIDE_ROOT = "outside_root"
        const val STEERING_FILE = "steering_file"

        /** ASSISTANT.md, CHARTER.md, the policy pack, and AGENTS.md so a synced desktop tree is safe too. */
        val STEERING_BASENAMES: Set<String> = setOf("assistant.md", "charter.md", "policy.yaml", "agents.md")
        val STEERING_DIRS: Set<String> = setOf("profiles", "rules")

        /**
         * Case-insensitive, and blind to trailing dots and spaces: Windows strips those at open
         * time, so `ASSISTANT.md.` aliases the real file on a synced tree and is an inert
         * lookalike everywhere else. Refused everywhere, like the desktop (TD-4820).
         */
        fun fold(component: String): String = component.trimEnd('.', ' ').lowercase()

        private fun parts(p: Path): List<String> = (0 until p.nameCount).map { p.getName(it).toString() }.filter { it.isNotEmpty() && it != "." }

        /**
         * Real path of the deepest existing ancestor plus the rest, so a file that does not
         * exist yet is still judged where it would land.
         */
        private fun canonical(p: Path): Path {
            var existing: Path = p
            val rest = ArrayList<Path>()
            while (!Files.exists(existing)) {
                val name = existing.fileName ?: return p
                rest.add(0, name)
                existing = existing.parent ?: return p
            }
            var out = existing.toRealPath()
            for (r in rest) out = out.resolve(r)
            return out.normalize()
        }
    }
}
