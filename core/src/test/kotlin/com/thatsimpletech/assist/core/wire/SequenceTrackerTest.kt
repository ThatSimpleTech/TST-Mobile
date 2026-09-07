package com.thatsimpletech.assist.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SequenceTrackerTest {
    private val gate = EventGate()
    private fun ev(text: String) = gate.parse(text)!!
    private fun delta(seq: Int, session: String = "s1") = ev("""{"type":"assistant_delta","seq":$seq,"session_id":"$session","delta":"x"}""")

    @Test
    fun acceptsExactlyLastPlusOneAndAdvances() {
        val t = SequenceTracker()
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(delta(1)))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(delta(2)))
        assertEquals(2, t.lastSeq("s1"))
        assertEquals(0, t.lastSeq("other"))
    }

    @Test
    fun aDuplicateOrOlderSeqIsDroppedAndDoesNotMoveTheCursor() {
        val t = SequenceTracker()
        t.accept(delta(1)); t.accept(delta(2)); t.accept(delta(3))
        assertEquals(SequenceTracker.Verdict.Duplicate("s1", 2, 3), t.accept(delta(2)))
        assertEquals(SequenceTracker.Verdict.Duplicate("s1", 3, 3), t.accept(delta(3)))
        assertEquals(3, t.lastSeq("s1"))
    }

    @Test
    fun aGapNamesTheSeqToReattachFrom() {
        val t = SequenceTracker()
        t.accept(delta(1)); t.accept(delta(2))
        assertEquals(SequenceTracker.Verdict.Gap("s1", expected = 3, got = 7), t.accept(delta(7)))
        assertEquals(2, t.lastSeq("s1"), "a gapped event is not accepted")
    }

    @Test
    fun sessionsAreTrackedIndependently() {
        val t = SequenceTracker()
        t.accept(delta(1, "a")); t.accept(delta(1, "b")); t.accept(delta(2, "a"))
        assertEquals(2, t.lastSeq("a"))
        assertEquals(1, t.lastSeq("b"))
        t.forget("a")
        assertEquals(0, t.lastSeq("a"))
        assertEquals(1, t.lastSeq("b"))
    }

    @Test
    fun connectionScopedFramesBypassTheCursor() {
        val t = SequenceTracker()
        for (i in 1..5) t.accept(delta(i))
        // seq=1 replies after attach advanced the cursor: dropped as stale on the desktop before TD-1204/TD-3403.
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(ev(Fixtures.text("design_hit"))))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(ev(Fixtures.text("transcript"))))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(ev("""{"type":"session_list","seq":1,"sessions":[]}""")))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(ev("""{"type":"cu_kill_state","seq":1,"killed":true}""")))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(ev("""{"type":"error","code":"x","message":"y"}""")))
        assertEquals(5, t.lastSeq("s1"))
    }

    @Test
    fun instructionStackSnapshotPassesButALivePushAdvances() {
        val t = SequenceTracker()
        for (i in 1..5) t.accept(delta(i))
        val snapshot = ev("""{"type":"instruction_stack","seq":1,"session_id":"s1"}""")
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(snapshot))
        assertEquals(5, t.lastSeq("s1"))
        val push = ev("""{"type":"instruction_stack","seq":6,"session_id":"s1"}""")
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(push))
        assertEquals(6, t.lastSeq("s1"))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(delta(7)), "no false gap after the push")
    }

    @Test
    fun logTrimmedJumpsTheCursorToJustBeforeTheKeptWindow() {
        val t = SequenceTracker()
        t.accept(delta(1))
        val trimmed = assertIs<TstdEvent.LogTrimmed>(ev(Fixtures.text("log_trimmed"))) // sess-1, earliest_seq 8
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(trimmed))
        assertEquals(7, t.lastSeq("sess-1"))
        assertEquals(SequenceTracker.Verdict.Accept, t.accept(delta(8, "sess-1")), "replay from the kept window is not a gap")
        // Never jumps backwards.
        for (i in 9..20) t.accept(delta(i, "sess-1"))
        t.accept(trimmed)
        assertEquals(20, t.lastSeq("sess-1"))
    }
}
