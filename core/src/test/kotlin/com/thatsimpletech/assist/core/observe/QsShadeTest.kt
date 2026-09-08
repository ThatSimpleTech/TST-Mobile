package com.thatsimpletech.assist.core.observe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QsShadeTest {
    @Test
    fun systemUiPlusQuickSettingsIsQs() {
        assertTrue(QsShade.detected(QsShade.SYSTEM_UI, "QuickSettings", emptyList()))
        assertTrue(QsShade.detected(QsShade.SYSTEM_UI, "", listOf("com.android.systemui.qs.QSPanel")))
        assertTrue(QsShade.detected(QsShade.SYSTEM_UI, "", listOf("NotificationShade")))
    }

    @Test
    fun systemUiWithoutAMarkerIsNotQs() {
        assertFalse(QsShade.detected(QsShade.SYSTEM_UI, "VolumeDialog", listOf("VolumeDialog")))
        assertFalse(QsShade.detected(QsShade.SYSTEM_UI, "", emptyList()))
    }

    @Test
    fun otherPackagesAreNeverQs() {
        assertFalse(QsShade.detected("com.whatsapp", "QuickSettings", listOf("QuickSettings")))
        assertFalse(QsShade.detected("com.android.settings", "QSPanel", listOf("QSPanel")))
    }
}
