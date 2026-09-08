package com.thatsimpletech.assist.core.intent

/**
 * Visible Settings panels for Wi-Fi / Bluetooth (Q5). Never `WifiManager` /
 * `BluetoothAdapter`. `open "Settings"` still launches Settings; these specs
 * fire only when the open label names the radio.
 */
object SettingsIntents {
    const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
    const val PKG_SETTINGS = "com.android.settings"

    fun wifi(): IntentSpec = IntentSpec(
        action = ACTION_WIFI_SETTINGS,
        flags = PhoneIntents.FLAG_NEW_TASK,
    )

    fun bluetooth(): IntentSpec = IntentSpec(
        action = ACTION_BLUETOOTH_SETTINGS,
        flags = PhoneIntents.FLAG_NEW_TASK,
    )

    /**
     * Dedicated panel for a Wi-Fi / Bluetooth open label, or null for a generic
     * Settings launch. Does not match the label "Settings".
     */
    fun panelFor(label: String): IntentSpec? {
        val compact = label.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
        return when (compact) {
            "wifi", "internet" -> wifi()
            "bluetooth", "bt" -> bluetooth()
            else -> null
        }
    }
}
