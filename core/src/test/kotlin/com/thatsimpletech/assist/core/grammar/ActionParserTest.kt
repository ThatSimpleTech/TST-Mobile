package com.thatsimpletech.assist.core.grammar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionParserTest {
    private val parser = ActionParser()

    private fun ok(raw: String, known: Set<Int>? = null): Action {
        val r = parser.parse(raw, known)
        assertIs<ParseResult.Ok>(r, "expected ok for '$raw' but got $r")
        return r.action
    }

    private fun err(raw: String, known: Set<Int>? = null): String {
        val r = parser.parse(raw, known)
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
}
