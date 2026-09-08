package com.thatsimpletech.assist.core.steering

/** The shipped default instructions (core resource ASSISTANT.md). The app copies it into its agent-unwritable files dir. */
object DefaultInstructions {
    fun load(): String = resource("/ASSISTANT.md")

    /** One-line letters-codec note copied into `rules/profiles/letters.md`. */
    fun lettersProfile(): String = resource("/profiles/letters.md")

    private fun resource(path: String): String {
        val stream = DefaultInstructions::class.java.getResourceAsStream(path)
            ?: error("$path missing from the core jar")
        return stream.use { it.readBytes().decodeToString() }
    }
}
