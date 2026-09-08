package com.thatsimpletech.assist.core.wire

/**
 * Per-session sequence bookkeeping, client.ts:535-549 and the special cases above it.
 * Accept an event iff it advances its session's log by exactly one; anything else is the
 * no-gap/no-dup contract: `seq <= last` is a duplicate, `seq > last + 1` means we missed
 * events and must re-attach from `last + 1`.
 *
 * Events with no session, no seq, or a kind in [TstdEvent.CONNECTION_SCOPED_REPLIES] pass
 * without touching the cursor; the desktop dropped those as stale twice (TD-1204, TD-3403).
 */
class SequenceTracker {
    sealed interface Verdict {
        data object Accept : Verdict
        data class Duplicate(val sessionId: String, val seq: Int, val last: Int) : Verdict
        /** Re-attach [sessionId] from [expected]. */
        data class Gap(val sessionId: String, val expected: Int, val got: Int) : Verdict
    }

    private val last = HashMap<String, Int>()

    /** Highest accepted seq for a session, 0 if none. */
    @Synchronized
    fun lastSeq(sessionId: String): Int = last[sessionId] ?: 0

    @Synchronized
    fun forget(sessionId: String) {
        last.remove(sessionId)
    }

    @Synchronized
    fun clear() = last.clear()

    @Synchronized
    fun accept(event: TstdEvent): Verdict {
        val sessionId = event.sessionId ?: return Verdict.Accept
        val seq = event.seq ?: return Verdict.Accept
        when (event.kind) {
            // client.ts:482-488: a snapshot is seq=1 and not in the log; a live push is last+1 and is.
            "instruction_stack" -> {
                if (seq == lastSeq(sessionId) + 1) last[sessionId] = seq
                return Verdict.Accept
            }
            // client.ts:507-517: jump the cursor to just before the kept window.
            TstdEvent.LOG_TRIMMED -> {
                val earliest = (event as? TstdEvent.LogTrimmed)?.earliestSeq ?: return Verdict.Accept
                if (earliest > 1 && earliest - 1 > lastSeq(sessionId)) last[sessionId] = earliest - 1
                return Verdict.Accept
            }
        }
        if (event.kind in TstdEvent.CONNECTION_SCOPED_REPLIES) return Verdict.Accept
        val prev = lastSeq(sessionId)
        if (seq <= prev) return Verdict.Duplicate(sessionId, seq, prev)
        if (seq > prev + 1) return Verdict.Gap(sessionId, prev + 1, seq)
        last[sessionId] = seq
        return Verdict.Accept
    }
}
