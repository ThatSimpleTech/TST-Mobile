package com.thatsimpletech.assist.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContactPickTest {
    private val jerry = ContactPick.Hit("Jerry Smith", "+1 (555) 010-0100", mobile = true)
    private val jerryHome = ContactPick.Hit("Jerry Smith", "555-010-0999")
    private val jerryB = ContactPick.Hit("Jerry Jones", "5550200111")
    private val maria = ContactPick.Hit("Maria", "5550300111")

    @Test
    fun aPhoneNumberDoesNotNeedContacts() {
        val d = ContactPick.choose("+1 (555) 0100", emptyList())
        val ready = assertIs<ContactPick.Decision.Ready>(d)
        assertEquals("15550100", ready.digits)
        assertEquals(null, ready.name)
    }

    @Test
    fun uniqueGivenNameMatches() {
        val d = ContactPick.choose("Jerry", listOf(jerry, maria))
        val ready = assertIs<ContactPick.Decision.Ready>(d)
        assertEquals("15550100100", ready.digits)
        assertEquals("Jerry Smith", ready.name)
    }

    @Test
    fun twoJerrysAreAmbiguous() {
        val d = ContactPick.choose("Jerry", listOf(jerry, jerryB, maria))
        val amb = assertIs<ContactPick.Decision.Ambiguous>(d)
        assertEquals(listOf("Jerry Smith", "Jerry Jones"), amb.names)
    }

    @Test
    fun exactFullNameWinsAmongJerrys() {
        val d = ContactPick.choose("Jerry Smith", listOf(jerry, jerryB))
        val ready = assertIs<ContactPick.Decision.Ready>(d)
        assertEquals("15550100100", ready.digits)
    }

    @Test
    fun samePersonTwoNumbersPrefersMobile() {
        val d = ContactPick.choose("Jerry", listOf(jerry, jerryHome, maria))
        val ready = assertIs<ContactPick.Decision.Ready>(d)
        assertEquals("15550100100", ready.digits)
    }

    @Test
    fun sameNumberWithAndWithoutCountryCodeIsOneHit() {
        val local = ContactPick.Hit("Jerry Smith", "(555) 010-0100")
        val d = ContactPick.choose("Jerry", listOf(jerry, local))
        val ready = assertIs<ContactPick.Decision.Ready>(d)
        assertEquals("15550100100", ready.digits)
    }

    @Test
    fun unknownNameIsNone() {
        assertIs<ContactPick.Decision.None>(ContactPick.choose("Skippy", listOf(jerry, maria)))
    }

    @Test
    fun shortDigitStringIsNotANumber() {
        assertTrue(!ContactPick.looksLikeNumber("911"))
        assertTrue(ContactPick.looksLikeNumber("5550100"))
    }

    @Test
    fun explainNamesTheMiss() {
        assertTrue("no contact matching" in ContactPick.explain(ContactPick.Decision.None, "Skippy"))
        assertEquals("contacts permission is off", ContactPick.explain(ContactPick.Decision.NeedPermission, "Jerry"))
        val amb = ContactPick.explain(ContactPick.Decision.Ambiguous(listOf("Jerry Smith", "Jerry Jones")), "Jerry")
        assertTrue("several contacts matching" in amb)
        assertTrue("Jerry Smith" in amb)
    }
}
