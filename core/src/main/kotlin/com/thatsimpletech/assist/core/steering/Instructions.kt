package com.thatsimpletech.assist.core.steering

/** The shipped default instructions (core resource ASSISTANT.md). The app copies it into its agent-unwritable files dir. */
object DefaultInstructions {
    fun load(): String {
        val stream = DefaultInstructions::class.java.getResourceAsStream("/ASSISTANT.md")
            ?: error("ASSISTANT.md missing from the core jar")
        return stream.use { it.readBytes().decodeToString() }
    }
}
