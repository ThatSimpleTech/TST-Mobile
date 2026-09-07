package com.thatsimpletech.assist.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTokenTest {

    @Test
    fun mintIs64LowercaseHex() {
        val t = AuthToken.mint().reveal()
        assertEquals(64, t.length)
        assertTrue(t.all { it in '0'..'9' || it in 'a'..'f' }, t)
    }

    @Test
    fun twoMintsDiffer() {
        assertNotEquals(AuthToken.mint().reveal(), AuthToken.mint().reveal())
    }

    @Test
    fun toStringNeverRevealsTheValue() {
        val t = AuthToken.mint()
        assertEquals("<token>", t.toString())
        assertFalse(t.reveal() in "$t")
        assertFalse(t.reveal().substring(0, 8) in "$t")
    }

    @Test
    fun constantTimeEqualsAgreesWithPlainEquality() {
        val a = "a".repeat(64)
        assertTrue(AuthToken.equals(a, "a".repeat(64)))
        assertFalse(AuthToken.equals(a, "a".repeat(63) + "b"))
        assertFalse(AuthToken.equals(a, "b" + "a".repeat(63)))
        assertFalse(AuthToken.equals(a, "a".repeat(63)))
        assertFalse(AuthToken.equals(a, "a".repeat(65)))
        assertFalse(AuthToken.equals(a, ""))
        assertTrue(AuthToken.equals("", ""))
        assertTrue(AuthToken.equals("ñ", "ñ"))
    }

    @Test
    fun instanceEqualityComparesValuesNotIdentity() {
        val hex = AuthToken.mint().reveal()
        assertEquals(AuthToken.of(hex), AuthToken.of(hex))
        assertNotEquals(AuthToken.of(hex), AuthToken.mint())
    }
}
