package com.thatsimpletech.assist.core

import kotlin.test.Test
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun coreVersionIsSemver() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(Version.CORE))
    }
}
