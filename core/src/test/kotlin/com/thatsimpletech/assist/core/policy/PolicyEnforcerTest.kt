package com.thatsimpletech.assist.core.policy

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.Direction
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Role
import com.thatsimpletech.assist.core.observe.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicyEnforcerTest {
    private val enforcer = PolicyEnforcer(PolicyPack.loadDefault())
    private val wa = TaskContext(goalApps = setOf("com.whatsapp"))

    private fun btn(label: String, id: String? = null) =
        UiNode("n", Role.BTN, Rect(0, 0, 1, 1), label = label, resourceId = id, clickable = true)

    @Test
    fun tier1NeedsTheTaskGrantOnceThenProceeds() {
        val d1 = enforcer.decide(Action.Type(1, "hi"), "com.whatsapp", UiNode("e", Role.EDIT, Rect(0, 0, 1, 1), editable = true), false, false, wa)
        assertEquals(Tier.ONCE_PER_TASK, d1.tier)
        assertEquals(Gate.NEED_TASK_GRANT, d1.gate)
        val d2 = enforcer.decide(Action.Type(1, "hi"), "com.whatsapp", UiNode("e", Role.EDIT, Rect(0, 0, 1, 1), editable = true), false, false, wa.copy(taskGranted = true))
        assertEquals(Gate.PROCEED, d2.gate)
    }

    @Test
    fun tier2IsACardEveryTimeAndOnlyOncePerTurn() {
        val d1 = enforcer.decide(Action.Tap(2), "com.whatsapp", btn("Send"), false, false, wa.copy(taskGranted = true))
        assertEquals(Tier.EVERY_TIME, d1.tier)
        assertEquals(Gate.NEED_CARD, d1.gate)
        assertEquals("sensitive-control", d1.rule)
        val d2 = enforcer.decide(Action.Tap(2), "com.whatsapp", btn("Send"), false, false, wa.copy(taskGranted = true, tier2ThisTurn = 1))
        assertEquals(Gate.TURN_LIMIT, d2.gate)
    }

    @Test
    fun refusalsIgnoreGrants() {
        val d = enforcer.decide(Action.Tap(1), "com.whatsapp", btn("Ok"), keyguard = true, secure = false, task = wa.copy(taskGranted = true))
        assertEquals(Gate.REFUSE, d.gate)
        assertEquals("keyguard-locked", d.rule)
        assertTrue(d.reason.contains("unlocked"))
    }

    @Test
    fun confirmedAppsJoinTheGoalSet() {
        val outside = enforcer.decide(Action.Scroll(1, Direction.DOWN), "com.google.android.gm", null, false, false, wa)
        assertEquals("outside-goal-apps", outside.rule)
        val wayOut = enforcer.decide(Action.Home, "com.google.android.gm", null, false, false, wa)
        assertEquals(Gate.PROCEED, wayOut.gate)
        val tap = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Archive"), false, false, wa)
        assertEquals("outside-goal-apps", tap.rule)
        val confirmed = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Archive"), false, false, wa.copy(confirmedApps = setOf("com.google.android.gm"), taskGranted = true))
        assertEquals("in-app-control", confirmed.rule)
        assertEquals(Gate.PROCEED, confirmed.gate)
    }

    @Test
    fun openResolvesLabelsAndAliases() {
        val pack = enforcer.pack
        assertEquals("com.spotify.music", pack.resolveOpen("spotify")?.pkg)
        assertEquals("com.spotify.music", pack.resolveOpen("Música")?.pkg)
        assertEquals("com.google.android.dialer", pack.resolveOpen("TELEFONO")?.pkg)
        assertEquals(null, pack.resolveOpen("Terminal"))
        val d = enforcer.decide(Action.Open("Terminal"), "com.android.launcher3", null, false, false, wa)
        assertEquals("open-not-allowlisted", d.rule)
        assertEquals(Gate.REFUSE, d.gate)
    }

    @Test
    fun sensitiveMatchingIsWholeWordAndAccentInsensitive() {
        assertTrue(enforcer.isSensitive(btn("Enviar"), null))
        assertTrue(enforcer.isSensitive(btn("Enviár"), null))
        assertTrue(!enforcer.isSensitive(btn("Sender name"), null))
        assertTrue(!enforcer.isSensitive(btn("Friends"), null))
        assertTrue(enforcer.isSensitive(btn("", "com.whatsapp:id/send"), enforcer.pack.app("com.whatsapp")))
        assertTrue(!enforcer.isSensitive(btn("", "com.whatsapp:id/send"), null))
    }

    @Test
    fun strictYamlRejectsTypos() {
        val bad = """
            version: 1
            rules:
              - id: x
                when: { verbs: [tap] }
                tier: silent
        """.trimIndent()
        assertFailsWith<Exception> { PolicyPack.parse(bad) }
    }

    @Test
    fun validationCatchesDuplicateIdsAndUnknownVerbs() {
        val bad = """
            version: 1
            rules:
              - id: x
                when: { verb: [tapp] }
                tier: silent
              - id: x
                when: { verb: [tap] }
                tier: silent
        """.trimIndent()
        val e = assertFailsWith<IllegalArgumentException> { PolicyPack.parse(bad) }
        assertTrue("duplicate rule id" in e.message!!)
        assertTrue("unknown verbs" in e.message!!)
    }
}
