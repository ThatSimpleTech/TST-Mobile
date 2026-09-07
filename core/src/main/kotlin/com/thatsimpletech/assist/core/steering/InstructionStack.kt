package com.thatsimpletech.assist.core.steering

import java.nio.file.Files
import java.nio.file.Path

/** One layer of the resolved stack. [path] is null when the layer has no file (missing, or the task text). */
data class Layer(val name: String, val path: String?, val text: String, val tokens: Int)

/**
 * The ASSISTANT.md hierarchy (plan §6): device, then profile, then the task, in that order,
 * so the later layer can narrow the earlier one but never delete it. The inspector shows this
 * list and its token cost, same as TST Desk's instruction inspector.
 */
object InstructionStack {
    const val DEVICE = "device"
    const val PROFILE = "profile"
    const val TASK = "task"
    const val DEVICE_FILE = "ASSISTANT.md"
    const val PROFILES_DIR = "profiles"

    /** A profile name is a label, not a path: one segment, no separators, nothing hidden. */
    private val PROFILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * Four characters per token, rounded up. The inspector needs a number the person can
     * compare between layers, not a tokenizer that changes with the model.
     */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun resolve(root: Path, profile: String?, taskText: String): List<Layer> {
        val layers = ArrayList<Layer>(3)
        layers.add(fileLayer(DEVICE, root.resolve(DEVICE_FILE)))
        if (profile != null) {
            require(PROFILE_NAME.matches(profile) && ".." !in profile) { "profile name must be a plain label, not a path: '$profile'" }
            layers.add(fileLayer(PROFILE, root.resolve(PROFILES_DIR).resolve("$profile.md")))
        }
        layers.add(Layer(TASK, null, taskText, estimateTokens(taskText)))
        return layers
    }

    fun totalTokens(layers: List<Layer>): Int = layers.sumOf { it.tokens }

    /** The inspector view: a per-layer table, the total, then each layer's text under its own heading. */
    fun render(layers: List<Layer>): String = buildString {
        for (l in layers) {
            append(l.name.padEnd(8)).append(' ').append((l.path ?: "(none)").padEnd(40)).append(' ').append(l.tokens).append(" tokens\n")
        }
        append("total    ").append(totalTokens(layers)).append(" tokens\n")
        for (l in layers) {
            append("\n## ").append(l.name).append('\n')
            append(l.text.trimEnd()).append('\n')
        }
    }

    private fun fileLayer(name: String, path: Path): Layer {
        if (!Files.isRegularFile(path)) return Layer(name, null, "", 0)
        val text = Files.readString(path)
        return Layer(name, path.toString(), text, estimateTokens(text))
    }
}
