package com.thatsimpletech.assist.core.intent

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ISO-8601 event strings stay opaque in [com.thatsimpletech.assist.core.grammar.Action.Event].
 * The executor converts them to epoch millis for [PhoneIntents.insertEvent].
 */
object EventTimes {
    fun millis(iso: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = iso.trim()
        if (t.isEmpty()) return null
        try {
            return Instant.parse(t).toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return ZonedDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        return null
    }
}
