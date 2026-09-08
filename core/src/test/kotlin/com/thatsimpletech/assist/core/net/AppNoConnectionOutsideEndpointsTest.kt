package com.thatsimpletech.assist.core.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TM-023: the no-telemetry scan covers `app/` too (reality-check §8 finding 5). App Functions,
 * `startActivity`, and `CameraManager` are not sockets. Silent radios and skipped composers
 * are refused in the same walk (Q2/Q3 stayed dial-only / SMS draft).
 */
class AppNoConnectionOutsideEndpointsTest {
    private val socketMarkers = listOf("OkHttpClient", "java.net.Socket", "HttpURLConnection", "URL.openConnection")
    private val silentMarkers = listOf(
        "WifiManager.setWifiEnabled",
        "BluetoothAdapter.enable",
        "BluetoothAdapter.disable",
        "SmsManager",
        "Intent.ACTION_CALL",
        "EXTRA_SKIP_UI",
    )

    private fun appSources(): File {
        val candidates = listOf(
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
            File("../../app/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("app main sources not found from ${File(".").absolutePath}")
    }

    private fun hits(markers: List<String>): List<String> {
        val root = appSources()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val text = f.readText()
                markers.filter { it in text }.map { "${f.relativeTo(root)}: $it" }
            }
            .toList()
    }

    @Test
    fun appHasNoOkHttpOrSockets() {
        val offenders = hits(socketMarkers)
        assertTrue(offenders.isEmpty(), "app/ connections must go through core.net.Endpoints:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun appHasNoSilentWifiOrBluetoothToggles() {
        val offenders = hits(listOf("WifiManager.setWifiEnabled", "BluetoothAdapter.enable", "BluetoothAdapter.disable"))
        assertTrue(offenders.isEmpty(), "no silent Wi-Fi/Bluetooth toggles in app/:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun appHasNoActionCallOrSmsManager() {
        val offenders = hits(listOf("SmsManager", "Intent.ACTION_CALL", "EXTRA_SKIP_UI"))
        assertTrue(offenders.isEmpty(), "no ACTION_CALL / SmsManager / EXTRA_SKIP_UI in app/ (Q2/Q3):\n" + offenders.joinToString("\n"))
    }

    @Test
    fun appNeverCreatesTheCloudSpeechRecognizer() {
        val root = appSources()
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if ("createSpeechRecognizer" in line && "createOnDeviceSpeechRecognizer" !in line) {
                        "${f.relativeTo(root)}:${i + 1}: $line"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue(hits.isEmpty(), "cloud SpeechRecognizer.createSpeechRecognizer is refused; use createOnDeviceSpeechRecognizer:\n" + hits.joinToString("\n"))
    }

    @Test
    fun onDeviceListenIsTheSpeechPath() {
        val root = appSources()
        val listen = File(root, "com/thatsimpletech/assist/voice/OnDeviceListen.kt")
        assertTrue(listen.isFile, "OnDeviceListen.kt must exist under $root")
        val text = listen.readText()
        assertTrue("createOnDeviceSpeechRecognizer" in text)
        assertTrue("EXTRA_PREFER_OFFLINE" in text)
    }

    @Test
    fun theScanSeesTheAppSources() {
        val root = appSources()
        assertTrue(root.walkTopDown().any { it.name == "MainActivity.kt" }, "scan must see MainActivity under $root")
        assertTrue(silentMarkers.size == 6)
    }
}
