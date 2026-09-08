package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.PolicyPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalAppsPlannerTest {
    private val pack = PolicyPack.loadDefault()

    @Test
    fun quotedLabelsIntersectAllowlist() {
        val pkgs = GoalAppsPlanner.parse("""apps "WhatsApp" "Messages"""", pack)
        assertEquals(setOf("com.whatsapp", "com.google.android.apps.messaging"), pkgs)
    }

    @Test
    fun inventedPackagesAreDropped() {
        val mixed = GoalAppsPlanner.parse("""apps "com.evil.app" "WhatsApp"""", pack)
        assertEquals(setOf("com.whatsapp"), mixed)
        val onlyInvented = GoalAppsPlanner.parse("""apps "com.evil.app"""", pack)
        assertEquals(emptySet(), onlyInvented)
    }

    @Test
    fun appsNoneIsEmpty() {
        assertEquals(emptySet(), GoalAppsPlanner.parse("apps none", pack))
        assertEquals(emptySet(), GoalAppsPlanner.parse("APPS NONE", pack))
    }

    @Test
    fun aliasesResolve() {
        assertEquals(setOf("com.whatsapp"), GoalAppsPlanner.parse("""apps "wa"""", pack))
    }

    @Test
    fun unknownAliasDropped() {
        assertEquals(emptySet(), GoalAppsPlanner.parse("""apps "not-an-app"""", pack))
        assertEquals(setOf("com.whatsapp"), GoalAppsPlanner.parse("""apps "not-an-app" "wa"""", pack))
    }

    @Test
    fun barePackageNameResolvesIfAllowlisted() {
        assertEquals(setOf("com.whatsapp"), GoalAppsPlanner.parse("""apps "com.whatsapp"""", pack))
        assertEquals(emptySet(), GoalAppsPlanner.parse("""apps "COM.WHATSAPP"""", pack))
    }

    @Test
    fun secondLineIgnored() {
        val raw = """
            apps "WhatsApp"
            apps "Messages"
        """.trimIndent()
        assertEquals(setOf("com.whatsapp"), GoalAppsPlanner.parse(raw, pack))
    }

    @Test
    fun proseIsEmptyNotWholeAllowlist() {
        val pkgs = GoalAppsPlanner.parse("Sure, use WhatsApp and Messages.", pack)
        assertEquals(emptySet(), pkgs)
        assertTrue(pack.packages.isNotEmpty())
        assertTrue(pkgs != pack.packages)
    }

    @Test
    fun promptIncludesGoalAndQuotedLabels() {
        val text = GoalAppsPlanner.prompt("reply in WhatsApp", listOf("WhatsApp", "Messages"))
        assertTrue("reply in WhatsApp" in text, text)
        assertTrue("\"WhatsApp\"" in text, text)
        assertTrue("\"Messages\"" in text, text)
        assertTrue("apps none" in text, text)
    }
}
