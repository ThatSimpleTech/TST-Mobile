package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.PolicyPack
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalAppsTest {
    private val pack = PolicyPack.loadDefault()

    @Test
    fun inferStillEmptyWhenGoalNamesNothing() {
        assertEquals(emptySet(), GoalApps.infer("reply to Maria", pack))
    }

    @Test
    fun inferFindsWhatsAppByAlias() {
        assertEquals(setOf("com.whatsapp"), GoalApps.infer("reply in wa", pack))
    }

    @Test
    fun mergePrefersPlanner() {
        val emitted = setOf("com.google.android.apps.messaging")
        val inferred = GoalApps.infer("reply in WhatsApp", pack)
        assertEquals(setOf("com.whatsapp"), inferred)
        assertEquals(emitted, GoalApps.merge(emitted, inferred))
    }

    @Test
    fun mergeFallsBackToInferWhenPlannerEmpty() {
        val inferred = GoalApps.infer("reply in WhatsApp", pack)
        assertEquals(inferred, GoalApps.merge(emptySet(), inferred))
        assertEquals(emptySet(), GoalApps.merge(emptySet(), GoalApps.infer("reply to Maria", pack)))
    }
}
