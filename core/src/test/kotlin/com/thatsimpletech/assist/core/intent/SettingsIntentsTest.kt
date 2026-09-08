package com.thatsimpletech.assist.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsIntentsTest {
    @Test
    fun wifiPanelUsesWifiSettings() {
        val spec = SettingsIntents.wifi()
        assertEquals(SettingsIntents.ACTION_WIFI_SETTINGS, spec.action)
        assertEquals("android.settings.WIFI_SETTINGS", spec.action)
        assertNull(spec.uri)
        assertTrue(spec.extras.isEmpty())
        assertEquals(PhoneIntents.FLAG_NEW_TASK, spec.flags)
    }

    @Test
    fun bluetoothPanelUsesBluetoothSettings() {
        val spec = SettingsIntents.bluetooth()
        assertEquals(SettingsIntents.ACTION_BLUETOOTH_SETTINGS, spec.action)
        assertEquals("android.settings.BLUETOOTH_SETTINGS", spec.action)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun panelForWifiAndBluetoothAliases() {
        assertEquals(SettingsIntents.ACTION_WIFI_SETTINGS, SettingsIntents.panelFor("Wi-Fi")?.action)
        assertEquals(SettingsIntents.ACTION_WIFI_SETTINGS, SettingsIntents.panelFor("wifi")?.action)
        assertEquals(SettingsIntents.ACTION_WIFI_SETTINGS, SettingsIntents.panelFor("Internet")?.action)
        assertEquals(SettingsIntents.ACTION_BLUETOOTH_SETTINGS, SettingsIntents.panelFor("Bluetooth")?.action)
        assertEquals(SettingsIntents.ACTION_BLUETOOTH_SETTINGS, SettingsIntents.panelFor("bt")?.action)
        assertNull(SettingsIntents.panelFor("Settings"))
        assertNull(SettingsIntents.panelFor("ajustes"))
    }

    @Test
    fun neverLooksLikeASilentAdapter() {
        for (spec in listOf(SettingsIntents.wifi(), SettingsIntents.bluetooth())) {
            assertTrue(spec.action.startsWith("android.settings."), spec.action)
            assertTrue("WIFI_SETTINGS" in spec.action || "BLUETOOTH_SETTINGS" in spec.action, spec.action)
        }
    }
}
