package com.thatsimpletech.assist.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneIntentsTest {
    @Test
    fun dialUsesActionDialNotCall() {
        val spec = PhoneIntents.dial("5551234")
        assertEquals(PhoneIntents.ACTION_DIAL, spec.action)
        assertNotEquals("android.intent.action.CALL", spec.action)
        assertEquals("tel:5551234", spec.uri)
        assertNull(spec.mimeType)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun dialNeverSetsSkipUi() {
        val spec = PhoneIntents.dial("+1 (555) 0100")
        assertTrue(spec.extras.isEmpty(), "dial extras: ${spec.extras}")
        assertTrue(skipUiKeys(spec).isEmpty(), "dial must not set EXTRA_SKIP_UI")
        val uri = requireNotNull(spec.uri)
        assertTrue(uri.startsWith("tel:"), uri)
        assertTrue("555" in uri && "0100" in uri, uri)
    }

    @Test
    fun smsDraftUsesSendToNotSmsManagerShape() {
        val spec = PhoneIntents.smsDraft("5550100", "On my way")
        assertEquals(PhoneIntents.ACTION_SENDTO, spec.action)
        assertNotEquals("android.intent.action.SEND", spec.action)
        val uri = requireNotNull(spec.uri)
        assertTrue(uri.startsWith("smsto:"), uri)
        assertTrue("5550100" in uri, uri)
        assertEquals(Extra.Str("On my way"), spec.extras[PhoneIntents.EXTRA_SMS_BODY])
        assertNull(spec.mimeType, "SmsManager / ACTION_SEND uses vnd.android-dir/mms-sms; drafts do not")
        assertTrue(skipUiKeys(spec).isEmpty())
    }

    @Test
    fun alarmSkipUiIsFalse() {
        val spec = PhoneIntents.setAlarm(7, 30, "wake")
        assertEquals(PhoneIntents.ACTION_SET_ALARM, spec.action)
        assertEquals(Extra.IntNum(7), spec.extras[PhoneIntents.EXTRA_ALARM_HOUR])
        assertEquals(Extra.IntNum(30), spec.extras[PhoneIntents.EXTRA_ALARM_MINUTES])
        assertEquals(Extra.Str("wake"), spec.extras[PhoneIntents.EXTRA_ALARM_MESSAGE])
        assertEquals(Extra.Bool(false), spec.extras[PhoneIntents.EXTRA_SKIP_UI])
    }

    @Test
    fun timerSkipUiIsFalse() {
        val spec = PhoneIntents.setTimer(60, "eggs")
        assertEquals(PhoneIntents.ACTION_SET_TIMER, spec.action)
        assertEquals(Extra.IntNum(60), spec.extras[PhoneIntents.EXTRA_ALARM_LENGTH])
        assertEquals(Extra.Str("eggs"), spec.extras[PhoneIntents.EXTRA_ALARM_MESSAGE])
        assertEquals(Extra.Bool(false), spec.extras[PhoneIntents.EXTRA_SKIP_UI])
    }

    @Test
    fun eventIsInsertNotContentWrite() {
        val spec = PhoneIntents.insertEvent("Dentist", 1_000L, 2_000L)
        assertEquals(PhoneIntents.ACTION_INSERT, spec.action)
        assertEquals(PhoneIntents.URI_CALENDAR_EVENTS, spec.uri)
        assertEquals(Extra.Str("Dentist"), spec.extras[PhoneIntents.EXTRA_EVENT_TITLE])
        assertEquals(Extra.LongNum(1_000L), spec.extras[PhoneIntents.EXTRA_EVENT_BEGIN_TIME])
        assertEquals(Extra.LongNum(2_000L), spec.extras[PhoneIntents.EXTRA_EVENT_END_TIME])
        assertNotEquals("", spec.action, "silent ContentResolver.insert has no activity action")
        val openEnded = PhoneIntents.insertEvent("Hold", 5_000L)
        assertNull(openEnded.extras[PhoneIntents.EXTRA_EVENT_END_TIME])
    }

    @Test
    fun navigateLocksMapsPackageWhenAsked() {
        val locked = PhoneIntents.navigate("home", lockMapsPackage = true)
        assertEquals(PhoneIntents.ACTION_VIEW, locked.action)
        assertEquals("google.navigation:q=home", locked.uri)
        assertEquals(PhoneIntents.PKG_MAPS, locked.pkg)

        val fallback = PhoneIntents.navigate("home", lockMapsPackage = false)
        assertEquals(PhoneIntents.ACTION_VIEW, fallback.action)
        assertEquals("geo:0,0?q=home", fallback.uri)
        assertNull(fallback.pkg)
    }

    @Test
    fun lookupAndInsertContactAreViewAndInsert() {
        val lookup = PhoneIntents.lookupContact("Maria Garcia")
        assertEquals(PhoneIntents.ACTION_VIEW, lookup.action)
        assertEquals(
            "${PhoneIntents.URI_CONTACTS_FILTER}/Maria%20Garcia",
            lookup.uri,
        )
        val insert = PhoneIntents.insertContact("Maria", "5550100")
        assertEquals(PhoneIntents.ACTION_INSERT, insert.action)
        assertEquals(PhoneIntents.URI_CONTACTS, insert.uri)
        assertEquals(Extra.Str("Maria"), insert.extras[PhoneIntents.EXTRA_CONTACT_NAME])
        assertEquals(Extra.Str("5550100"), insert.extras[PhoneIntents.EXTRA_CONTACT_PHONE])
    }

    @Test
    fun flagNewTaskMatchesFrameworkBit() {
        assertEquals(0x10000000, PhoneIntents.FLAG_NEW_TASK)
        assertEquals(PhoneIntents.FLAG_NEW_TASK, PhoneIntents.dial("1").flags)
    }
}

internal fun skipUiKeys(spec: IntentSpec): List<String> =
    spec.extras.keys.filter { it.contains("SKIP_UI", ignoreCase = true) }
