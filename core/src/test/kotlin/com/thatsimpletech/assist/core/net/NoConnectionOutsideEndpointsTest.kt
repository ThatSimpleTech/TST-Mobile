package com.thatsimpletech.assist.core.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The "No telemetry" promise as a static check (plan §1): the only way out of core is through
 * the net package, so `Endpoints.destinations()` is the whole story.
 */
class NoConnectionOutsideEndpointsTest {
    private val markers = listOf("OkHttpClient", "java.net.Socket", "HttpURLConnection", "URL.openConnection")

    private fun mainSources(): File {
        val candidates = listOf(File("src/main/kotlin"), File("core/src/main/kotlin"))
        return candidates.firstOrNull { it.isDirectory } ?: fail("core main sources not found from ${File(".").absolutePath}")
    }

    @Test
    fun noConnectionOutsideEndpoints() {
        val root = mainSources()
        val netDir = File(root, "com/thatsimpletech/assist/core/net")
        assertTrue(netDir.isDirectory, "net package missing")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.absoluteFile.startsWith(netDir.absoluteFile) }
            .flatMap { f -> val text = f.readText(); markers.filter { it in text }.map { "${f.relativeTo(root)}: $it" } }
            .toList()
        assertTrue(offenders.isEmpty(), "connections must go through core.net.Endpoints:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun theScanSeesTheNetPackageItself() {
        val netDir = File(mainSources(), "com/thatsimpletech/assist/core/net")
        val mentions = netDir.listFiles()!!.filter { "OkHttpClient" in it.readText() }.map { it.name }
        assertTrue("Endpoints.kt" in mentions, "the scan must be able to see OkHttpClient where it is used: $mentions")
    }
}
