package com.thatsimpletech.assist.core.intent

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EventTimesTest {
    @Test
    fun instantAndLocalDateTimeParse() {
        assertEquals(0L, EventTimes.millis("1970-01-01T00:00:00Z"))
        val local = EventTimes.millis("2026-09-08T10:00:00", ZoneOffset.UTC)
        assertEquals(EventTimes.millis("2026-09-08T10:00:00Z"), local)
        assertNotNull(EventTimes.millis("2026-09-08"))
    }

    @Test
    fun blankAndGarbageAreNull() {
        assertNull(EventTimes.millis(""))
        assertNull(EventTimes.millis("   "))
        assertNull(EventTimes.millis("tomorrow morning"))
        assertNull(EventTimes.millis("10:00"))
    }
}
