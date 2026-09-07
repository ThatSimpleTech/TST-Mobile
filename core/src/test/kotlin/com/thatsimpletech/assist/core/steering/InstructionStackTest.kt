package com.thatsimpletech.assist.core.steering

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstructionStackTest {

    private fun <T> withRoot(block: (Path) -> T): T {
        val root = Files.createTempDirectory("tst-stack")
        try {
            return block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun tokenEstimateIsFourCharsPerTokenRoundedUp() {
        assertEquals(0, InstructionStack.estimateTokens(""))
        assertEquals(1, InstructionStack.estimateTokens("abcd"))
        assertEquals(2, InstructionStack.estimateTokens("abcde"))
        assertEquals(2, InstructionStack.estimateTokens("abcdefgh"))
        assertEquals(25, InstructionStack.estimateTokens("x".repeat(100)))
    }

    @Test
    fun layersAreDeviceThenProfileThenTaskWithTheirTokenCosts() = withRoot { root ->
        Files.writeString(root.resolve("ASSISTANT.md"), "Be terse.\n")            // 10 chars -> 3 tokens
        Files.createDirectory(root.resolve("profiles"))
        Files.writeString(root.resolve("profiles/adam.md"), "Spanish.")           // 8 chars -> 2 tokens
        val layers = InstructionStack.resolve(root, "adam", "call mom")           // 8 chars -> 2 tokens

        assertEquals(listOf("device", "profile", "task"), layers.map { it.name })
        assertEquals(root.resolve("ASSISTANT.md").toString(), layers[0].path)
        assertEquals(root.resolve("profiles/adam.md").toString(), layers[1].path)
        assertNull(layers[2].path)
        assertEquals("Be terse.\n", layers[0].text)
        assertEquals("Spanish.", layers[1].text)
        assertEquals("call mom", layers[2].text)
        assertEquals(listOf(3, 2, 2), layers.map { it.tokens })
        assertEquals(7, InstructionStack.totalTokens(layers))
    }

    @Test
    fun noProfileGivesDeviceAndTaskOnly() = withRoot { root ->
        Files.writeString(root.resolve("ASSISTANT.md"), "abcd")
        val layers = InstructionStack.resolve(root, null, "hi")
        assertEquals(listOf("device", "task"), layers.map { it.name })
        assertEquals(1 + 1, InstructionStack.totalTokens(layers))
    }

    @Test
    fun missingFilesGiveEmptyLayersWithNoPath() = withRoot { root ->
        val layers = InstructionStack.resolve(root, "nobody", "x".repeat(9))
        assertEquals(listOf("device", "profile", "task"), layers.map { it.name })
        assertNull(layers[0].path)
        assertEquals("", layers[0].text)
        assertEquals(0, layers[0].tokens)
        assertNull(layers[1].path)
        assertEquals(0, layers[1].tokens)
        assertEquals(3, layers[2].tokens)
        assertEquals(3, InstructionStack.totalTokens(layers))
    }

    @Test
    fun profileNameThatLooksLikeAPathIsRejected() = withRoot { root ->
        for (bad in listOf("../adam", "a/b", "..", ".hidden", "", "a\\b", "adam.md/")) {
            assertFailsWith<IllegalArgumentException>("profile '$bad'") { InstructionStack.resolve(root, bad, "t") }
        }
    }

    @Test
    fun renderListsEveryLayerAndTheTotalForTheInspector() = withRoot { root ->
        Files.writeString(root.resolve("ASSISTANT.md"), "Be terse.\n")
        val layers = InstructionStack.resolve(root, "adam", "call mom")
        val out = InstructionStack.render(layers)
        val lines = out.lines()
        assertTrue(lines[0].startsWith("device") && lines[0].endsWith("3 tokens"), lines[0])
        assertTrue(lines[1].startsWith("profile") && "(none)" in lines[1] && lines[1].endsWith("0 tokens"), lines[1])
        assertTrue(lines[2].startsWith("task") && lines[2].endsWith("2 tokens"), lines[2])
        assertTrue(lines[3].startsWith("total") && lines[3].endsWith("5 tokens"), lines[3])
        assertTrue("## device\nBe terse.\n" in out)
        assertTrue("## task\ncall mom\n" in out)
        assertTrue(out.indexOf("## device") < out.indexOf("## profile") && out.indexOf("## profile") < out.indexOf("## task"))
    }
}
