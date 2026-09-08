package com.thatsimpletech.assist.core.policy

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.Direction
import com.thatsimpletech.assist.core.media.SpotifyCommand
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Role
import com.thatsimpletech.assist.core.observe.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun sendInAnotherAppIsSensitiveNotAnAdmitCard() {
        val d = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Send"), false, false, wa)
        assertEquals("sensitive-control", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
    }

    @Test
    fun longPressOnAPasswordFieldIsRefused() {
        val field = UiNode("pw", Role.EDIT, Rect(0, 0, 1, 1), label = "Password", password = true, editable = true)
        val d = enforcer.decide(Action.Long(1), "com.whatsapp", field, false, false, wa)
        assertEquals("password-field", d.rule)
        assertEquals(Gate.REFUSE, d.gate)
    }

    @Test
    fun archiveByLabelIsSensitive() {
        val d = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Archive"), false, false, TaskContext(goalApps = setOf("com.google.android.gm")))
        assertEquals("sensitive-control", d.rule)
    }

    @Test
    fun confirmedAppsJoinTheGoalSet() {
        val outside = enforcer.decide(Action.Scroll(1, Direction.DOWN), "com.google.android.gm", null, false, false, wa)
        assertEquals("outside-goal-apps", outside.rule)
        val wayOut = enforcer.decide(Action.Home, "com.google.android.gm", null, false, false, wa)
        assertEquals(Gate.PROCEED, wayOut.gate)
        val tap = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Inbox"), false, false, wa)
        assertEquals("outside-goal-apps", tap.rule)
        val confirmed = enforcer.decide(Action.Tap(1), "com.google.android.gm", btn("Inbox"), false, false, wa.copy(confirmedApps = setOf("com.google.android.gm"), taskGranted = true))
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
        assertEquals("open-any-app", d.rule)
        assertEquals(Gate.PROCEED, d.gate)
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

    @Test
    fun callIsEveryTimeEvenInsideGoalApps() {
        val phone = TaskContext(goalApps = setOf("com.google.android.dialer"))
        val d = enforcer.decide(Action.Call("5550100"), "com.google.android.dialer", null, false, false, phone)
        assertEquals("call-place", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
        assertEquals(Gate.NEED_CARD, d.gate)
    }

    @Test
    fun textIsEveryTime() {
        val sms = TaskContext(goalApps = setOf("com.google.android.apps.messaging"))
        val d = enforcer.decide(Action.Text("5550100", "hi"), "com.google.android.apps.messaging", null, false, false, sms)
        assertEquals("text-send", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
    }

    @Test
    fun alarmIsOnce() {
        val clock = TaskContext(goalApps = setOf("com.google.android.deskclock"))
        val d = enforcer.decide(Action.Alarm(7, 30, "wake"), "com.google.android.deskclock", null, false, false, clock)
        assertEquals("alarm-set", d.rule)
        assertEquals(Tier.ONCE_PER_TASK, d.tier)
    }

    @Test
    fun torchIsDeviceControlEveryTime() {
        val d = enforcer.decide(Action.Torch(true), "com.whatsapp", null, false, false, wa)
        assertEquals("device-control", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
    }

    @Test
    fun qsOpenIsSilentLikeRecents() {
        val qs = enforcer.decide(Action.Qs, "com.whatsapp", null, false, false, wa)
        val recents = enforcer.decide(Action.Recents, "com.whatsapp", null, false, false, wa)
        assertEquals("read-only", qs.rule)
        assertEquals(Tier.SILENT, qs.tier)
        assertEquals(Gate.PROCEED, qs.gate)
        assertEquals(recents.tier, qs.tier)
        assertEquals(recents.gate, qs.gate)
    }

    @Test
    fun qsTapIsEveryTimeEvenWhenSystemUiIsNotAllowlisted() {
        val d = enforcer.decide(Action.Tap(1), "com.android.systemui", btn("Wi-Fi"), false, false, wa, onQs = true)
        assertEquals("qs-tile", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
        assertEquals(Gate.NEED_CARD, d.gate)
    }

    @Test
    fun whatsappSendIsEveryTime() {
        val d = enforcer.decide(Action.WhatsApp("Maria", "hi"), "com.whatsapp", null, false, false, wa)
        assertEquals("partner-send", d.rule)
        assertEquals(Tier.EVERY_TIME, d.tier)
    }

    @Test
    fun spotifyPlayIsOnce() {
        val music = TaskContext(goalApps = setOf("com.spotify.music"))
        val d = enforcer.decide(Action.Spotify(SpotifyCommand.Play("jazz")), "com.spotify.music", null, false, false, music)
        assertEquals("partner-media", d.rule)
        assertEquals(Tier.ONCE_PER_TASK, d.tier)
    }

    @Test
    fun keyguardStillRefusesCall() {
        val d = enforcer.decide(Action.Call("5550100"), "com.android.systemui", null, keyguard = true, secure = false, task = wa)
        assertEquals("keyguard-locked", d.rule)
        assertEquals(Gate.REFUSE, d.gate)
        val qs = enforcer.decide(Action.Qs, "com.android.systemui", null, keyguard = true, secure = false, task = wa)
        assertEquals("keyguard-locked", qs.rule)
        assertEquals(Gate.REFUSE, qs.gate)
    }

    @Test
    fun intentVerbsAreNotAppScoped() {
        val bank = TaskContext(goalApps = setOf("com.bank.app"))
        val call = enforcer.decide(Action.Call("5550100"), "com.bank.app", null, false, false, bank)
        assertEquals("call-place", call.rule)
        assertFalse(call.facts.appScoped)
        val torch = enforcer.decide(Action.Torch(false), "com.bank.app", null, false, false, bank)
        assertEquals("device-control", torch.rule)
        assertFalse(torch.facts.appScoped)
        val qs = enforcer.decide(Action.Qs, "com.bank.app", null, false, false, bank)
        assertEquals("read-only", qs.rule)
        assertFalse(qs.facts.appScoped)
        val tap = enforcer.decide(Action.Tap(1), "com.bank.app", btn("Ok"), false, false, bank)
        assertEquals("in-app-control", tap.rule)
        assertTrue(tap.facts.appScoped)
    }
}
