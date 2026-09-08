package com.thatsimpletech.assist.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderKeyTest {
    @Test
    fun trimsAndStripsBearer() {
        assertEquals("abc", ProviderKey.sanitize("  Bearer abc \n"))
        assertEquals("abc", ProviderKey.sanitize("bearer abc"))
    }

    @Test
    fun ezerStripsSkPrefix() {
        assertEquals("4413", ProviderKey.sanitize("sk-4413", stripSkPrefix = true))
        assertEquals("sk-or-v1-xx", ProviderKey.sanitize("sk-or-v1-xx", stripSkPrefix = false))
    }
}
