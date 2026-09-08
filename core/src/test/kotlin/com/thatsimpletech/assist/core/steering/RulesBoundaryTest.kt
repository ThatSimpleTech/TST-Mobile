package com.thatsimpletech.assist.core.steering

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesBoundaryTest {

    private fun <T> withRoot(block: (Path, RulesBoundary) -> T): T {
        val tmp = Files.createTempDirectory("tst-files")
        try {
            val root = Files.createDirectory(tmp.resolve("files"))
            return block(root, RulesBoundary(root))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun everyRulesFileShapeIsRefusedAsSteering() = withRoot { _, b ->
        val shapes = listOf(
            "ASSISTANT.md", "assistant.md", "Assistant.MD",
            "CHARTER.md", "charter.md",
            "policy.yaml", "POLICY.YAML",
            "AGENTS.md", "agents.md",
            "profiles/adam.md", "PROFILES/adam.md", "profiles/nested/deep.md",
            "rules/custom.md", "Rules/x.yaml",
            "ASSISTANT.md.", "ASSISTANT.md ", "ASSISTANT.md. .", "policy.yaml.", "CHARTER.md ",
            "profiles./adam.md", "rules /x.md",
            "sub/ASSISTANT.md", "sub/deep/CHARTER.md",
            "memory/ASSISTANT.md",
            "./ASSISTANT.md", "memory/../ASSISTANT.md",
        )
        for (s in shapes) {
            val r = assertNotNull(b.check(s), "expected '$s' refused")
            assertEquals(RulesBoundary.STEERING_FILE, r.code, "code for '$s'")
            assertTrue(r.reason.isNotBlank())
        }
    }

    @Test
    fun ordinaryAndMemoryWritesAreAllowed() = withRoot { _, b ->
        for (s in listOf("memory/notes.md", "memory/deep/facts.md", "notes.md", "conversations/2026-09-07.json", "profile.md", "my-profiles/x.md", "assistant.md.txt", "policy.yaml.bak")) {
            assertNull(b.check(s), "expected '$s' allowed")
        }
    }

    @Test
    fun dotDotEscapeIsRefusedAsOutsideRoot() = withRoot { _, b ->
        for (s in listOf("../escape.md", "memory/../../escape.md", "../files-other/notes.md", "../../etc/passwd")) {
            val r = assertNotNull(b.check(s), "expected '$s' refused")
            assertEquals(RulesBoundary.OUTSIDE_ROOT, r.code, "code for '$s'")
        }
    }

    @Test
    fun absolutePathOutsideRootIsRefusedAndInsideIsJudgedNormally() = withRoot { root, b ->
        assertEquals(RulesBoundary.OUTSIDE_ROOT, b.check(root.parent.resolve("stray.md").toString())?.code)
        assertNull(b.check(root.resolve("memory/notes.md").toString()))
        assertEquals(RulesBoundary.STEERING_FILE, b.check(root.resolve("ASSISTANT.md").toString())?.code)
    }

    @Test
    fun symlinkOutOfRootIsRefusedAsOutsideRoot() = withRoot { root, b ->
        val outside = Files.createDirectory(root.parent.resolve("outside"))
        Files.createSymbolicLink(root.resolve("linked-dir"), outside)
        val target = Files.writeString(outside.resolve("secret.txt"), "x")
        Files.createSymbolicLink(root.resolve("linked-file.md"), target)

        assertEquals(RulesBoundary.OUTSIDE_ROOT, b.check("linked-dir/notes.md")?.code)
        assertEquals(RulesBoundary.OUTSIDE_ROOT, b.check("linked-dir/new/deeper.md")?.code)
        assertEquals(RulesBoundary.OUTSIDE_ROOT, b.check("linked-file.md")?.code)
    }

    @Test
    fun symlinkToARulesFileInsideRootIsRefusedAsSteering() = withRoot { root, b ->
        Files.writeString(root.resolve("ASSISTANT.md"), "be brief")
        Files.createSymbolicLink(root.resolve("alias.md"), root.resolve("ASSISTANT.md"))
        Files.createDirectory(root.resolve("profiles"))
        Files.createSymbolicLink(root.resolve("people"), root.resolve("profiles"))
        assertEquals(RulesBoundary.STEERING_FILE, b.check("alias.md")?.code)
        assertEquals(RulesBoundary.STEERING_FILE, b.check("people/adam.md")?.code)
    }

    @Test
    fun rootGivenThroughASymlinkStillContainsItsFiles() = withRoot { root, _ ->
        val link = Files.createSymbolicLink(root.parent.resolve("files-link"), root)
        val b = RulesBoundary(link)
        assertNull(b.check("memory/notes.md"))
        assertNull(b.check(root.resolve("memory/notes.md").toString()))
        assertEquals(RulesBoundary.OUTSIDE_ROOT, b.check("../stray.md")?.code)
    }
}
