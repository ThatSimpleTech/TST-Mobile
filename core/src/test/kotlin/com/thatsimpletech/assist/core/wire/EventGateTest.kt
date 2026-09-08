package com.thatsimpletech.assist.core.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** See [Fixtures] for where the fixture file comes from. */
class EventGateTest {

    @Test
    fun theUnionMirrorsProtocolTsExactly() {
        // DaemonEventUnion at protocol.ts:1347 lists 50 members; client.ts:26-77 gates the same 50.
        assertEquals(50, TstdEvent.UNION.size)
        assertEquals(setOf("hello_ack", "ping"), TstdEvent.OUT_OF_BAND)
        assertEquals(52, TstdEvent.KNOWN.size)
        assertTrue(TstdEvent.CONNECTION_SCOPED_REPLIES.all { it in TstdEvent.UNION })
    }

    @Test
    fun everyFixtureParsesWithoutThrowingAndOnlyClientMessagesAreUnknown() {
        val gate = EventGate()
        val unknownKinds = ArrayList<String>()
        for ((name, obj) in Fixtures.all) {
            val event = gate.parse(obj.toString())
            assertNotNull(event, "fixture $name was dropped as malformed: ${gate.lastMalformedReason}")
            if (event is TstdEvent.Unknown) {
                unknownKinds += event.kind
                // A client -> daemon message never carries seq (protocol.py ClientMessage has only `type`);
                // that is what makes these 76 the daemon's inbound side and not a hole in the union.
                assertNull(obj["seq"], "fixture $name maps to Unknown yet carries a seq: a real union gap")
            }
        }
        // 137 fixtures: 76 are client messages (73 kinds, with variants for archive_session,
        // deny, rename_session, set_session_star, user_message), 61 are daemon frames.
        assertEquals(137, Fixtures.all.size)
        assertEquals(76, unknownKinds.size, "unknown fixture kinds: ${unknownKinds.distinct().sorted()}")
        assertEquals(76, gate.unknownCount)
        assertEquals(0, gate.malformedCount, gate.lastMalformedReason)
    }

    @Test
    fun everyDaemonEventFixtureIsKnownAndZeroMapToUnknown() {
        val gate = EventGate()
        val daemonFixtures = Fixtures.all.filterValues { it["type"]!!.jsonPrimitive.content in TstdEvent.KNOWN }
        assertEquals(61, daemonFixtures.size, "51 event kinds plus hello_ack, with variants")
        val unknown = daemonFixtures.filter { (_, obj) -> gate.parse(obj.toString()) is TstdEvent.Unknown }
        assertEquals(0, unknown.size, "daemon fixtures outside the union: ${unknown.keys}")
        assertEquals(0, gate.unknownCount)
        assertEquals(0, gate.malformedCount, gate.lastMalformedReason)
        // Every kind in the union has at least one fixture, so the previous assertion is not vacuous.
        val covered = daemonFixtures.values.mapTo(HashSet()) { it["type"]!!.jsonPrimitive.content }
        assertEquals(emptySet(), TstdEvent.KNOWN - covered, "union kinds with no fixture")
    }

    @Test
    fun typedKindsDecodeTheFixturesFieldByField() {
        val gate = EventGate()
        val approval = assertIs<TstdEvent.ApprovalRequest>(gate.parse(Fixtures.text("approval_request")))
        assertEquals(8, approval.seq)
        assertEquals("sess-1", approval.sessionId)
        assertEquals("tc-1", approval.toolCallId)
        assertEquals("fs_write", approval.toolName)
        assertEquals(JsonPrimitive("test.txt"), approval.arguments["path"])
        assertEquals(TstdEvent.DecisionClass.C, approval.decisionClass)
        assertEquals("Write to test.txt", approval.summary)
        assertEquals("decision class C requires approval", approval.reason)
        assertNull(approval.proposedAlwaysAllow)

        val allowable = assertIs<TstdEvent.ApprovalRequest>(gate.parse(Fixtures.text("approval_request_always_allow")))
        assertEquals(TstdEvent.DecisionClass.B, allowable.decisionClass)
        assertEquals(TstdEvent.PolicyRule("shell", "npm test", "auto"), allowable.proposedAlwaysAllow)

        val cost = assertIs<TstdEvent.CostUpdate>(gate.parse(Fixtures.text("cost_update")))
        assertEquals(0.05, cost.turnCost)
        assertEquals(0.5, cost.sessionCost)
        assertEquals(1.2, cost.totalCost)
        assertEquals(0.01, cost.classifierCost)
        assertEquals(mapOf("brain" to 0.4, "worker" to 0.1), cost.costByTier)

        val state = assertIs<TstdEvent.SessionState>(gate.parse(Fixtures.text("session_state_paused")))
        assertEquals("paused", state.state)
        assertEquals("spend cap exceeded: \$0.0205 >= \$0.01", state.reason)

        val list = assertIs<TstdEvent.SessionList>(gate.parse(Fixtures.text("session_list")))
        assertEquals(1, list.seq)
        assertNull(list.sessionId)
        assertEquals(listOf("sess-1", "sess-2"), list.sessions.map { it.sessionId })
        assertEquals(true, list.sessions[1].archived)
        assertNull(list.sessions[1].title)

        val delta = assertIs<TstdEvent.AssistantDelta>(gate.parse(Fixtures.text("assistant_delta")))
        assertEquals("Hello ", delta.delta)

        val done = assertIs<TstdEvent.TurnComplete>(gate.parse(Fixtures.text("turn_complete_failed")))
        assertEquals(true, done.failed)
        assertEquals("auth_failed", done.errorCode)
        assertEquals(0.4, done.duration)

        val err = assertIs<TstdEvent.Error>(gate.parse(Fixtures.text("error")))
        assertEquals(13, err.seq)
        assertNull(err.sessionId)
        assertEquals("test", err.code)
        assertEquals("sess-1", assertIs<TstdEvent.Error>(gate.parse(Fixtures.text("error_with_session"))).sessionId)

        val kill = assertIs<TstdEvent.CuKillState>(gate.parse(Fixtures.text("cu_kill_state")))
        assertEquals(true, kill.killed)
        assertEquals(1, kill.seq)

        val trimmed = assertIs<TstdEvent.LogTrimmed>(gate.parse(Fixtures.text("log_trimmed")))
        assertEquals(8, trimmed.earliestSeq)

        assertEquals(TstdEvent.HelloAck(1), gate.parse(Fixtures.text("hello_ack")))
        assertEquals(TstdEvent.Ping, gate.parse(Fixtures.text("ping")))

        val known = assertIs<TstdEvent.Known>(gate.parse(Fixtures.text("tool_call")))
        assertEquals("tool_call", known.kind)
        assertEquals(4, known.seq)
        assertEquals("sess-1", known.sessionId)
        assertEquals(JsonPrimitive("fs_read"), known.json["name"])
        assertEquals(0, gate.malformedCount, gate.lastMalformedReason)
    }

    @Test
    fun errorWithoutSeqAsBuildErrorWritesItParses() {
        // protocol.py:2380 build_error: {"type","code","message"} and no seq; the handshake refusals arrive this way.
        val err = assertIs<TstdEvent.Error>(EventGate().parse("""{"type": "error", "code": "auth_failed", "message": "Invalid auth token."}"""))
        assertNull(err.seq)
        assertEquals("auth_failed", err.code)
    }

    @Test
    fun anUnknownKindIsDroppedAndCounted() {
        val gate = EventGate()
        val e1 = gate.parse("""{"type": "from_the_future", "seq": 3, "session_id": "s", "payload": 1}""")
        assertEquals(TstdEvent.Unknown("from_the_future"), e1)
        assertEquals(1, gate.unknownCount)
        assertEquals("from_the_future", gate.lastUnknownKind)
        gate.parse("""{"type": "another_one"}""")
        assertEquals(2, gate.unknownCount)
        assertEquals("another_one", gate.lastUnknownKind)
        // A known frame neither bumps nor resets the count (client.test.ts:437-490).
        assertIs<TstdEvent.Ping>(gate.parse("""{"type": "ping"}"""))
        assertEquals(2, gate.unknownCount)
        assertEquals("another_one", gate.lastUnknownKind)
        assertEquals(0, gate.malformedCount)
    }

    @Test
    fun malformedFramesAreCountedNeverThrown() {
        val gate = EventGate()
        val cases = listOf(
            "not json at all",
            "{\"type\": \"ping\"",
            "[1, 2, 3]",
            "\"a string\"",
            "{\"seq\": 1}",
            "{\"type\": 7}",
            "",
        )
        for (c in cases) {
            assertNull(gate.parse(c), "expected '$c' to be dropped")
        }
        assertEquals(7, gate.malformedCount)
        assertEquals(0, gate.unknownCount)
        assertNotNull(gate.lastMalformedReason)
    }

    @Test
    fun aKnownKindWithABrokenPayloadIsMalformedNotAnEvent() {
        val gate = EventGate()
        // decision_class outside A/B/C and tool_call_id missing: nothing the phone may show a card for.
        assertNull(gate.parse("""{"type": "approval_request", "seq": 1, "session_id": "s", "tool_name": "x", "arguments": {}, "decision_class": "D", "summary": "", "reason": ""}"""))
        assertEquals(1, gate.malformedCount)
        assertTrue(gate.lastMalformedReason!!.startsWith("approval_request"), gate.lastMalformedReason)
        assertNull(gate.parse("""{"type": "cu_kill_state", "seq": 1}"""))
        assertEquals(2, gate.malformedCount)
    }

    @Test
    fun additiveFieldsAreTolerated() {
        // architecture.md additive-fields policy: new optional fields must not break an older client.
        val e = EventGate().parse("""{"type": "cu_kill_state", "seq": 1, "killed": false, "since": "2026-09-07T00:00:00Z"}""")
        assertEquals(TstdEvent.CuKillState(1, false), e)
    }
}
