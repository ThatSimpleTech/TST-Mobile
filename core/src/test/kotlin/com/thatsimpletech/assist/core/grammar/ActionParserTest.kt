package com.thatsimpletech.assist.core.grammar

import com.thatsimpletech.assist.core.media.MediaCommand
import com.thatsimpletech.assist.core.media.SpotifyCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionParserTest {
    private val parser = ActionParser()

    private fun ok(raw: String, known: Set<Int>? = null, at: ((Int, Int) -> Int?)? = null): Action {
        val r = parser.parse(raw, known, at)
        assertIs<ParseResult.Ok>(r, "expected ok for '$raw' but got $r")
        return r.action
    }

    private fun err(raw: String, known: Set<Int>? = null, at: ((Int, Int) -> Int?)? = null): String {
        val r = parser.parse(raw, known, at)
        assertIs<ParseResult.Error>(r, "expected error for '$raw' but got $r")
        return r.reason
    }

    @Test
    fun everyVerbParsesAndRoundTrips() {
        val samples = listOf(
            Action.Tap(7), Action.Long(2), Action.Type(1, "Yes, see you at 7"), Action.Clear(1),
            Action.Scroll(4, Direction.DOWN), Action.Swipe(SwipeTarget.Screen, Direction.UP),
            Action.Swipe(SwipeTarget.Hint(3), Direction.LEFT), Action.Drag(2, 5),
            Action.Back, Action.Home, Action.Recents, Action.Open("Messages"),
            Action.NotifList, Action.NotifReply("n2", "On my way"), Action.NotifOpen("n3"),
            Action.ScreenAsk("what is the total?"), Action.Wait(3),
            Action.Done("Replied to Maria"), Action.Ask("Which Maria?"), Action.More,
            Action.Call("5550100"), Action.Text("5550100", "On my way"),
            Action.Alarm(7, 30, "wake"), Action.Timer(60, "eggs"),
            Action.Event("Dentist", "2026-09-08T10:00:00"),
            Action.Event("Dentist", "2026-09-08T10:00:00", "2026-09-08T11:00:00"),
            Action.ContactLookup("Maria"), Action.ContactAdd("Maria", "5550100"),
            Action.Navigate("home"), Action.Torch(true), Action.Torch(false),
            Action.Dnd(true), Action.Dnd(false), Action.Brightness(50),
            Action.Volume(VolumeChange.Up), Action.Volume(VolumeChange.Down),
            Action.Volume(VolumeChange.Percent(40)), Action.Media(MediaCommand.Play),
            Action.Media(MediaCommand.Pause), Action.Media(MediaCommand.Next), Action.Media(MediaCommand.Prev),
            Action.WhatsApp("Maria", "On my way"),
            Action.Spotify(SpotifyCommand.Play("jazz")), Action.Spotify(SpotifyCommand.Pause),
            Action.Spotify(SpotifyCommand.Next), Action.Spotify(SpotifyCommand.Prev),
            Action.Gmail("a@b.com", "Hi", "See you at 7"), Action.Qs,
        )
        for (a in samples) {
            assertEquals(a, ok(a.render()), "round trip of ${a.render()}")
        }
    }

    @Test
    fun firstNonEmptyLineOnly() {
        assertEquals(Action.Tap(3), ok("\n\n  tap 3\nscroll 4 down"))
        // Chatter before the action is not skipped: the first line is the action, or it is an error.
        val reason = err("Sure, I will tap it.\ntap 3")
        assertTrue("unknown verb" in reason, reason)
    }

    @Test
    fun inlineCodeFenceIsTolerated() {
        assertEquals(Action.Tap(3), ok("`tap 3`"))
    }

    @Test
    fun unknownVerbIsAnError() {
        assertTrue("unknown verb 'click'" in err("click 3"))
    }

    @Test
    fun hintsMustBeOnScreen() {
        assertEquals(Action.Tap(2), ok("tap 2", known = setOf(1, 2)))
        val reason = err("tap 9", known = setOf(1, 2))
        assertTrue("hint 9 is not on screen" in reason, reason)
        val drag = err("drag 1 to 9", known = setOf(1, 2))
        assertTrue("hint 9" in drag, drag)
    }

    @Test
    fun typeNeedsQuotedText() {
        assertTrue("quoted text" in err("type 1 hello"))
        assertEquals(Action.Type(1, "say \"hi\""), ok("type 1 \"say \\\"hi\\\"\""))
        assertEquals(Action.Type(1, "a\nb"), ok("type 1 \"a\\nb\""))
        assertTrue("unbalanced" in err("type 1 \"oops"))
    }

    @Test
    fun waitIsClamped() {
        assertEquals(Action.Wait(10), ok("wait 60"))
        assertEquals(Action.Wait(2), ok("wait 2"))
        assertTrue("seconds" in err("wait soon"))
        assertTrue("at least 1" in err("wait 0"))
    }

    @Test
    fun plainWordsIncludesPayloads() {
        assertEquals("Type into item 3: \"hi\"", Action.Type(3, "hi").plainWords())
        assertEquals("Reply to notification n2: \"On my way\"", Action.NotifReply("n2", "On my way").plainWords())
        assertEquals("Ask the vision model: \"what is the total?\"", Action.ScreenAsk("what is the total?").plainWords())
        assertEquals("Tap item 4", Action.Tap(4).plainWords())
        assertEquals("Place a call to \"5550100\"", Action.Call("5550100").plainWords())
        assertEquals("Send a text to \"5550100\": \"On my way\"", Action.Text("5550100", "On my way").plainWords())
        assertEquals("Email \"a@b.com\" subject \"Hi\": \"See you at 7\"", Action.Gmail("a@b.com", "Hi", "See you at 7").plainWords())
        assertTrue("on" in Action.Torch(true).plainWords())
        assertTrue("50" in Action.Brightness(50).plainWords())
    }

    @Test
    fun doneAndAskAcceptPlainWords() {
        assertEquals(Action.Done("Sent the message"), ok("done Sent the message"))
        assertEquals(Action.Ask("Which contact?"), ok("ask \"Which contact?\""))
        assertTrue("needs text" in err("done"))
    }

    @Test
    fun openTakesOnlyAQuotedLabel() {
        assertEquals(Action.Open("Spotify"), ok("open \"Spotify\""))
        assertTrue("quoted app name" in err("open spotify"))
        assertTrue("quoted app name" in err("open \"a\" \"b\""))
    }

    @Test
    fun bareVerbsRejectArguments() {
        assertTrue("no arguments" in err("back 3"))
        assertTrue("no arguments" in err("home now"))
        assertTrue("no arguments" in err("more please"))
    }

    @Test
    fun scrollAndSwipeNeedDirections() {
        assertTrue("direction" in err("scroll 4 sideways"))
        assertTrue("swipe" in err("swipe 4"))
        assertTrue("two hints" in err("drag 2 onto 5"))
    }

    @Test
    fun notifForms() {
        assertEquals(Action.NotifList, ok("notif list"))
        assertTrue("takes list" in err("notif"))
        assertTrue("takes list" in err("notif delete n1"))
        assertTrue("quoted text" in err("notif reply n1 hi"))
        assertTrue("takes an id" in err("notif open"))
    }

    @Test
    fun quotedVerbIsRefused() {
        assertTrue("start with a verb" in err("\"tap\" 3"))
    }

    @Test
    fun lettersCodec() {
        val letters = ActionParser(HintCodec.Letters)
        val r = letters.parse("tap c", known = setOf(1, 2, 3))
        assertEquals(Action.Tap(3), (r as ParseResult.Ok).action)
        assertEquals("aa", HintCodec.Letters.encode(27))
        assertEquals(27, HintCodec.Letters.decode("aa"))
        assertEquals(702, HintCodec.Letters.decode("zz"))
        for (i in 1..800) assertEquals(i, HintCodec.Letters.decode(HintCodec.Letters.encode(i)))
        val reason = (letters.parse("tap 3", known = setOf(1, 2, 3)) as ParseResult.Error).reason
        assertTrue("not a hint" in reason, reason)
    }

    @Test
    fun numericCodecRejectsZeroAndJunk() {
        assertEquals(null, HintCodec.Numeric.decode("0"))
        assertEquals(null, HintCodec.Numeric.decode("3a"))
        assertEquals(12, HintCodec.Numeric.decode("12"))
    }

    @Test
    fun atPercentsResolveToAHint() {
        val at = { x: Int, y: Int -> if (x == 50 && y == 67) 4 else null }
        val known = setOf(1, 2, 3, 4)
        assertEquals(Action.Tap(4), ok("tap @50,67", known, at))
        assertEquals(Action.Tap(4), ok("@50,67", known, at))
        assertEquals(Action.Tap(4), ok("tap @50, 67", known, at))
        assertEquals(Action.Long(4), ok("long @50,67", known, at))
        assertEquals(Action.Type(4, "hi"), ok("type @50,67 \"hi\"", known, at))
        assertTrue("no control at @1,1" in err("tap @1,1", known, at))
        assertTrue("no control" in err("tap @50,67", known, at = null))
    }

    @Test
    fun callTakesAQuotedNameOrNumber() {
        assertEquals(Action.Call("5550100"), ok("call \"5550100\""))
        assertEquals(Action.Call("Jerry"), ok("call \"Jerry\""))
        assertTrue("quoted name or number" in err("call 5550100"))
        assertTrue("needs a name or number" in err("call \"\""))
    }

    @Test
    fun textTakesNumberAndBody() {
        assertEquals(Action.Text("5550100", "On my way"), ok("text \"5550100\" \"On my way\""))
        assertEquals(Action.Text("Jerry", "On my way"), ok("text \"Jerry\" \"On my way\""))
        assertTrue("quoted name or number" in err("text 5550100 hello"))
        assertTrue("quoted name or number" in err("text \"5550100\""))
    }

    @Test
    fun alarmRejectsBadHour() {
        assertEquals(Action.Alarm(0, 0, "midnight"), ok("alarm 0 0 \"midnight\""))
        assertEquals(Action.Alarm(23, 59, "late"), ok("alarm 23 59 \"late\""))
        assertTrue("hour" in err("alarm 24 0 \"x\""))
        assertTrue("hour" in err("alarm -1 0 \"x\""))
        assertTrue("minute" in err("alarm 7 60 \"x\""))
    }

    @Test
    fun timerClampsSeconds() {
        assertEquals(Action.Timer(60, "eggs"), ok("timer 60 \"eggs\""))
        assertEquals(Action.Timer(Action.MAX_TIMER_SECONDS, "long"), ok("timer 999999 \"long\""))
        assertEquals(Action.Timer(Action.MIN_TIMER_SECONDS, "short"), ok("timer 0 \"short\""))
        assertTrue("seconds" in err("timer soon \"x\""))
    }

    @Test
    fun contactLookupAndAdd() {
        assertEquals(Action.ContactLookup("Maria"), ok("contact lookup \"Maria\""))
        assertEquals(Action.ContactAdd("Maria", "5550100"), ok("contact add \"Maria\" \"5550100\""))
        assertTrue("lookup" in err("contact"))
        assertTrue("lookup" in err("contact delete \"Maria\""))
    }

    @Test
    fun eventOptionalEnd() {
        assertEquals(Action.Event("Dentist", "2026-09-08T10:00:00"), ok("event \"Dentist\" \"2026-09-08T10:00:00\""))
        assertEquals(
            Action.Event("Dentist", "2026-09-08T10:00:00", "2026-09-08T11:00:00"),
            ok("event \"Dentist\" \"2026-09-08T10:00:00\" \"2026-09-08T11:00:00\""),
        )
        assertTrue("quoted title" in err("event Dentist 2026-09-08T10:00:00"))
    }

    @Test
    fun torchDndOnOff() {
        assertEquals(Action.Torch(true), ok("torch on"))
        assertEquals(Action.Torch(false), ok("torch off"))
        assertEquals(Action.Dnd(true), ok("dnd on"))
        assertEquals(Action.Dnd(false), ok("dnd off"))
        assertTrue("on or off" in err("torch maybe"))
        assertTrue("on or off" in err("dnd"))
    }

    @Test
    fun brightnessPercentRange() {
        assertEquals(Action.Brightness(0), ok("brightness 0"))
        assertEquals(Action.Brightness(100), ok("brightness 100"))
        assertTrue("percent" in err("brightness 101"))
        assertTrue("percent" in err("brightness -1"))
        assertTrue("percent" in err("brightness high"))
    }

    @Test
    fun volumeUpDownPercent() {
        assertEquals(Action.Volume(VolumeChange.Up), ok("volume up"))
        assertEquals(Action.Volume(VolumeChange.Down), ok("volume down"))
        assertEquals(Action.Volume(VolumeChange.Percent(40)), ok("volume 40"))
        assertEquals(Action.Volume(VolumeChange.Percent(100)), ok("volume 150"))
        assertEquals(Action.Volume(VolumeChange.Percent(0)), ok("volume 0"))
        assertTrue("up, down" in err("volume mute"))
    }

    @Test
    fun qsIsBare() {
        assertEquals(Action.Qs, ok("qs"))
        assertTrue("no arguments" in err("qs 1"))
        assertTrue("no arguments" in err("qs open"))
    }

    @Test
    fun partnerVerbsRoundTrip() {
        assertEquals(Action.WhatsApp("Maria", "On my way"), ok("whatsapp \"Maria\" \"On my way\""))
        assertEquals(Action.Gmail("a@b.com", "Hi", "See you"), ok("gmail \"a@b.com\" \"Hi\" \"See you\""))
        assertEquals(Action.Spotify(SpotifyCommand.Play("jazz")), ok("spotify play \"jazz\""))
        assertEquals(Action.Spotify(SpotifyCommand.Pause), ok("spotify pause"))
        assertEquals(Action.Spotify(SpotifyCommand.Next), ok("spotify next"))
        assertEquals(Action.Spotify(SpotifyCommand.Prev), ok("spotify prev"))
        assertEquals(Action.Media(MediaCommand.Play), ok("media play"))
        assertEquals(Action.Navigate("home"), ok("navigate \"home\""))
        for (a in listOf(
            Action.WhatsApp("Maria", "On my way"),
            Action.Gmail("a@b.com", "Hi", "See you"),
            Action.Spotify(SpotifyCommand.Play("jazz")),
            Action.Media(MediaCommand.Next),
        )) {
            assertEquals(a, ok(a.render()), a.render())
        }
    }

    @Test
    fun newVerbsRejectASecondLine() {
        assertEquals(Action.Call("555"), ok("call \"555\"\ntext \"555\" \"hi\""))
        assertEquals(Action.Qs, ok("qs\ncall \"555\""))
        assertEquals(Action.Torch(true), ok("torch on\ntorch off"))
    }

    @Test
    fun intentVerbsDoNotNeedKnownHints() {
        assertEquals(Action.Call("555"), ok("call \"555\"", known = emptySet()))
        assertEquals(Action.Text("555", "hi"), ok("text \"555\" \"hi\"", known = emptySet()))
        assertEquals(Action.Gmail("a@b.com", "Hi", "x"), ok("gmail \"a@b.com\" \"Hi\" \"x\"", known = emptySet()))
        assertEquals(Action.Qs, ok("qs", known = emptySet()))
        assertEquals(Action.Torch(false), ok("torch off", known = emptySet()))
    }
}
