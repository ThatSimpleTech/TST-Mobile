package com.thatsimpletech.assist.core.redact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    // Assembled at runtime so the literal never sits in the source tree or a test report.
    private val canary = "sk-or-v1-" + "canary" + "ab".repeat(8)
    private val githubPat = "github_pat_" + "A1_".repeat(12)
    private val ghp = "ghp_" + "x9".repeat(18)
    private val aws = "AKIA" + "0123456789ABCDEF"

    @Test
    fun theRuntimeAssembledCanaryNeverSurvives() {
        val out = Redactor.text("Authorization: Bearer $canary\n")
        assertFalse(out.contains(canary))
        assertEquals("Authorization: Bearer [REDACTED]\n", out)
    }

    @Test
    fun eachDesktopShapeIsMasked() {
        assertEquals("pat=[REDACTED]", Redactor.text("pat=$githubPat"))
        assertEquals("classic=[REDACTED]", Redactor.text("classic=$ghp"))
        assertEquals("aws=[REDACTED]", Redactor.text("aws=$aws"))
        // The PEM header loses its text; the trailing dashes stay, as on the desktop.
        assertEquals("[REDACTED]-----\nMIIE", Redactor.text("-----BEGIN RSA PRIVATE KEY-----\nMIIE"))
        assertEquals("[REDACTED]-----", Redactor.text("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertEquals("[REDACTED]-----", Redactor.text("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    fun dashedKeyFormsAreCaught() {
        val proj = "sk-proj-" + "Q".repeat(20)
        val ant = "sk-ant-api03-" + "z".repeat(30)
        assertEquals("[REDACTED] [REDACTED]", Redactor.text("$proj $ant"))
    }

    @Test
    fun nonSecretTextIsUntouched() {
        val plain = "Open WhatsApp, tap 3, type \"see you at 6\". sk-short, ghp_tooshort, AKIA-not-a-key, BEGIN PUBLIC KEY."
        assertEquals(plain, Redactor.text(plain))
    }

    @Test
    fun patThresholdIsCoresThirtySixNotTheUisTwenty() {
        // ui/src/lib/redact.ts accepts 20+; core's SECRET_PATTERNS need 36+. The core value is the one ported.
        val thirtyFive = "ghp_" + "a".repeat(35)
        val thirtySix = "ghp_" + "a".repeat(36)
        assertEquals(thirtyFive, Redactor.text(thirtyFive))
        assertEquals("[REDACTED]", Redactor.text(thirtySix))
        val pat35 = "github_pat_" + "b".repeat(35)
        val pat36 = "github_pat_" + "b".repeat(36)
        assertEquals(pat35, Redactor.text(pat35))
        assertEquals("[REDACTED]", Redactor.text(pat36))
    }

    @Test
    fun redactionIsIdempotent() {
        val once = Redactor.text("k=$canary; a=$aws; p=$githubPat")
        assertEquals(once, Redactor.text(once))
        assertEquals("k=[REDACTED]; a=[REDACTED]; p=[REDACTED]", once)
    }

    @Test
    fun structureScrubsKeysAndValuesRecursively() {
        val input: Map<Any?, Any?> = linkedMapOf(
            "headers" to linkedMapOf("Authorization" to "Bearer $canary"),
            canary to "key used as a key",
            "list" to listOf("ok", aws, listOf(ghp), 7, null, true),
            "n" to 42,
        )
        val out = Redactor.structure(input) as Map<*, *>
        val expected: Map<Any?, Any?> = linkedMapOf(
            "headers" to linkedMapOf("Authorization" to "Bearer [REDACTED]"),
            "[REDACTED]" to "key used as a key",
            "list" to listOf("ok", "[REDACTED]", listOf("[REDACTED]"), 7, null, true),
            "n" to 42,
        )
        assertEquals(expected, out)
        assertFalse(out.toString().contains(canary))
    }

    @Test
    fun structureLeavesScalarsAndNullAlone() {
        assertEquals(3, Redactor.structure(3))
        assertEquals(null, Redactor.structure(null))
        assertEquals(false, Redactor.structure(false))
        assertEquals(listOf("[REDACTED]"), Redactor.structure(arrayOf(aws)))
    }

    @Test
    fun throwableMessageKeepsTheClassAndDropsTheKey() {
        val t = IllegalStateException("401 from openrouter.ai with Authorization: Bearer $canary")
        val out = Redactor.throwableMessage(t)
        assertEquals("IllegalStateException: 401 from openrouter.ai with Authorization: Bearer [REDACTED]", out)
        assertTrue(!out.contains(canary))
        assertEquals("IllegalStateException", Redactor.throwableMessage(IllegalStateException()))
    }

    @Test
    fun matchesTheDesktopAuditSample() {
        // tests/test_audit.py: token=sk-xxx… and AKIA000… both go through the one redactor.
        val sample = "token=sk-" + "x".repeat(30) + " and key " + "AKIA" + "0".repeat(16)
        assertEquals("token=[REDACTED] and key [REDACTED]", Redactor.text(sample))
    }
}
