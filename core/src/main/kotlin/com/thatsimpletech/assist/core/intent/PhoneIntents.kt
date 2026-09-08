package com.thatsimpletech.assist.core.intent

/**
 * Standard phone intents as [IntentSpec] data (Workstream B, TM-021).
 *
 * Visible composers only:
 * - Dial is [ACTION_DIAL] + `tel:`. Never `android.intent.action.CALL`, never
 *   [EXTRA_SKIP_UI], no `CALL_PHONE` permission.
 * - SMS is [ACTION_SENDTO] + `smsto:` + [EXTRA_SMS_BODY]. Never SmsManager,
 *   never `vnd.android-dir/mms-sms`, no `SEND_SMS` permission.
 * - Calendar is [ACTION_INSERT] on [URI_CALENDAR_EVENTS]. Never a silent
 *   `ContentResolver.insert`.
 *
 * Alarm/timer set [EXTRA_SKIP_UI] to **false** so Clock still shows its UI.
 * App executors consume these objects; they do not invent URI shapes.
 */
object PhoneIntents {
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    const val ACTION_SET_TIMER = "android.intent.action.SET_TIMER"

    /** `Intent.FLAG_ACTIVITY_NEW_TASK` (`0x10000000`). App applies this with `addFlags`. */
    const val FLAG_NEW_TASK = 0x10000000

    const val EXTRA_SMS_BODY = "sms_body"
    const val EXTRA_ALARM_HOUR = "android.intent.extra.alarm.HOUR"
    const val EXTRA_ALARM_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val EXTRA_ALARM_MESSAGE = "android.intent.extra.alarm.MESSAGE"
    const val EXTRA_ALARM_LENGTH = "android.intent.extra.alarm.LENGTH"
    /** Always written as false. Never true, never omitted on alarm/timer. */
    const val EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"

    const val EXTRA_EVENT_TITLE = "title"
    const val EXTRA_EVENT_BEGIN_TIME = "beginTime"
    const val EXTRA_EVENT_END_TIME = "endTime"

    const val EXTRA_CONTACT_NAME = "name"
    const val EXTRA_CONTACT_PHONE = "phone"

    const val URI_CALENDAR_EVENTS = "content://com.android.calendar/events"
    const val URI_CONTACTS = "content://com.android.contacts/contacts"
    const val URI_CONTACTS_FILTER = "content://com.android.contacts/contacts/filter"

    const val PKG_MAPS = "com.google.android.apps.maps"

    /** Characters `tel:` / `smsto:` keep unencoded (RFC 3966 visual separators). */
    private const val TEL_ALLOW = "+-.*#()"

    /** Visible dialer with the number filled in. The person taps Call. */
    fun dial(number: String): IntentSpec = IntentSpec(
        action = ACTION_DIAL,
        uri = "tel:${encodeUri(number, TEL_ALLOW)}",
        flags = FLAG_NEW_TASK,
        // extras empty on purpose: no EXTRA_SKIP_UI, no speakerphone, no CALL extras.
    )

    /** SMS composer with the body as a draft. The person taps Send. */
    fun smsDraft(number: String, body: String): IntentSpec = IntentSpec(
        action = ACTION_SENDTO,
        uri = "smsto:${encodeUri(number, TEL_ALLOW)}",
        extras = mapOf(EXTRA_SMS_BODY to Extra.Str(body)),
        flags = FLAG_NEW_TASK,
        // mimeType stays null: the SmsManager / ACTION_SEND shape uses
        // vnd.android-dir/mms-sms and is not this path.
    )

    fun setAlarm(hour: Int, minute: Int, label: String): IntentSpec = IntentSpec(
        action = ACTION_SET_ALARM,
        extras = mapOf(
            EXTRA_ALARM_HOUR to Extra.IntNum(hour),
            EXTRA_ALARM_MINUTES to Extra.IntNum(minute),
            EXTRA_ALARM_MESSAGE to Extra.Str(label),
            EXTRA_SKIP_UI to Extra.Bool(false),
        ),
        flags = FLAG_NEW_TASK,
    )

    fun setTimer(seconds: Int, label: String): IntentSpec = IntentSpec(
        action = ACTION_SET_TIMER,
        extras = mapOf(
            EXTRA_ALARM_LENGTH to Extra.IntNum(seconds),
            EXTRA_ALARM_MESSAGE to Extra.Str(label),
            EXTRA_SKIP_UI to Extra.Bool(false),
        ),
        flags = FLAG_NEW_TASK,
    )

    /** Calendar insert UI. [endMillis] omitted means an open-ended event. */
    fun insertEvent(title: String, beginMillis: Long, endMillis: Long? = null): IntentSpec {
        val extras = linkedMapOf<String, Extra>(
            EXTRA_EVENT_TITLE to Extra.Str(title),
            EXTRA_EVENT_BEGIN_TIME to Extra.LongNum(beginMillis),
        )
        if (endMillis != null) extras[EXTRA_EVENT_END_TIME] = Extra.LongNum(endMillis)
        return IntentSpec(
            action = ACTION_INSERT,
            uri = URI_CALENDAR_EVENTS,
            extras = extras,
            flags = FLAG_NEW_TASK,
        )
    }

    fun lookupContact(query: String): IntentSpec = IntentSpec(
        action = ACTION_VIEW,
        uri = "$URI_CONTACTS_FILTER/${encodeUri(query)}",
        flags = FLAG_NEW_TASK,
    )

    fun insertContact(name: String, number: String): IntentSpec = IntentSpec(
        action = ACTION_INSERT,
        uri = URI_CONTACTS,
        extras = mapOf(
            EXTRA_CONTACT_NAME to Extra.Str(name),
            EXTRA_CONTACT_PHONE to Extra.Str(number),
        ),
        flags = FLAG_NEW_TASK,
    )

    /**
     * Navigation. When [lockMapsPackage] is true (the usual case), the spec
     * is `google.navigation:q=` locked to Maps. When false, a `geo:` URI with
     * no package lock — the app uses this if Maps is not installed.
     */
    fun navigate(query: String, lockMapsPackage: Boolean = true): IntentSpec {
        val q = encodeUri(query)
        return if (lockMapsPackage) {
            IntentSpec(
                action = ACTION_VIEW,
                uri = "google.navigation:q=$q",
                pkg = PKG_MAPS,
                flags = FLAG_NEW_TASK,
            )
        } else {
            IntentSpec(
                action = ACTION_VIEW,
                uri = "geo:0,0?q=$q",
                flags = FLAG_NEW_TASK,
            )
        }
    }
}
