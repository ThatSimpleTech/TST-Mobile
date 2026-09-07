package com.thatsimpletech.assist.core.wire

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A fake tstd: the daemon side of the handshake from ws.py:386-437, one frame after the ack. */
class TstdClientTest {
    private val received = LinkedBlockingQueue<String>()

    /** Answers a correct hello with hello_ack then [afterAck]; anything else with the daemon's error and close 1008. */
    private fun daemon(expectedToken: String, afterAck: List<String>) = object : WebSocketListener() {
        var handshaken = false
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (handshaken) return
            val hello = Protocol.json.parseToJsonElement(text).jsonObject
            if (hello["type"]?.jsonPrimitive?.content != "hello" || hello["token"]?.jsonPrimitive?.content != expectedToken) {
                webSocket.send("""{"type": "error", "code": "auth_failed", "message": "Invalid auth token."}""")
                webSocket.close(1008, "auth_failed")
                return
            }
            handshaken = true
            webSocket.send("""{"type": "hello_ack", "version": ${Protocol.VERSION}}""")
            for (f in afterAck) webSocket.send(f)
        }
        // Answer the client's close so MockWebServer can shut down cleanly; a real daemon does the same.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
    }

    private fun wsUrl(server: MockWebServer) = server.url("/").toString().replaceFirst("http", "ws")

    /**
     * Subscribe before connecting: the flow has no replay and the fake daemon answers at once.
     * The collector runs on its own dispatcher so a blocking poll here cannot starve it.
     */
    private fun subscribe(client: TstdClient): Pair<LinkedBlockingQueue<TstdEvent>, Job> {
        val seen = LinkedBlockingQueue<TstdEvent>()
        val scope = CoroutineScope(Dispatchers.Default)
        val started = java.util.concurrent.CountDownLatch(1)
        val job = scope.launch {
            client.events.onSubscription { started.countDown() }.collect { seen.add(it) }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "collector never subscribed")
        return seen to job
    }

    private fun next(seen: LinkedBlockingQueue<TstdEvent>): TstdEvent = assertNotNull(seen.poll(5, TimeUnit.SECONDS), "no event within 5s")

    @Test
    fun sendsHelloFirstThenReceivesOneEventThroughTheGate() {
        MockWebServer().use { server ->
            // After the ack: the session's first event, a ping, then seq 3 with seq 2 missing.
            val frames = listOf(
                """{"type": "session_state", "seq": 1, "session_id": "sess-1", "state": "running", "reason": null}""",
                """{"type": "ping"}""",
                """{"type": "assistant_delta", "seq": 3, "session_id": "sess-1", "delta": "late"}""",
            )
            server.enqueue(MockResponse().withWebSocketUpgrade(daemon("tok-1", frames)))
            server.start()
            val client = TstdClient({ OkHttpClient() })
            val (seen, collector) = subscribe(client)
            client.connect(wsUrl(server), "tok-1")

            assertEquals(TstdEvent.HelloAck(1), next(seen))
            assertEquals(TstdEvent.SessionState(1, "sess-1", "running", null), next(seen))
            assertEquals(ConnectionState.CONNECTED, client.state.value)

            // The very first frame on the socket was hello, exactly as ws.py:396-403 demands.
            val first = assertNotNull(received.poll(5, TimeUnit.SECONDS))
            assertEquals(ClientMessages.hello("tok-1").encode(), first)
            // The gapped seq 3 was dropped, the cursor stayed at 1, and the client asked for a replay from 2.
            assertEquals(ClientMessages.attach("sess-1", 2).encode(), received.poll(5, TimeUnit.SECONDS))
            assertEquals(null, seen.poll(300, TimeUnit.MILLISECONDS), "the ping and the gapped delta must not reach the sink")
            assertEquals(1, client.sequence.lastSeq("sess-1"))
            assertEquals(0, client.gate.unknownCount)
            assertEquals(0, client.gate.malformedCount, client.gate.lastMalformedReason)
            client.close()
            collector.cancel()
        }
    }

    @Test
    fun sendIsRefusedBeforeTheHandshakeAndForwardedAfter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(daemon("tok-2", emptyList())))
            server.start()
            val client = TstdClient({ OkHttpClient() })
            val (seen, collector) = subscribe(client)
            assertFalse(client.send(ClientMessages.listSessions()), "nothing may be sent with no socket")
            client.connect(wsUrl(server), "tok-2")
            assertEquals(TstdEvent.HelloAck(1), next(seen))
            assertTrue(client.send(ClientMessages.listSessions()))
            // A user_message to an unattached session attaches it first (TD-1713), on the same socket, in order.
            assertTrue(client.send(ClientMessages.userMessage("sess-9", "hi")))
            assertEquals(ClientMessages.hello("tok-2").encode(), received.poll(5, TimeUnit.SECONDS))
            assertEquals("""{"type":"list_sessions"}""", received.poll(5, TimeUnit.SECONDS))
            assertEquals(ClientMessages.attach("sess-9", 1).encode(), received.poll(5, TimeUnit.SECONDS))
            assertEquals(ClientMessages.userMessage("sess-9", "hi").encode(), received.poll(5, TimeUnit.SECONDS))
            client.close()
            collector.cancel()
        }
    }

    @Test
    fun aWrongTokenSurfacesTheDaemonsAuthFailedError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(daemon("right", emptyList())))
            server.start()
            val client = TstdClient({ OkHttpClient() })
            val (seen, collector) = subscribe(client)
            client.connect(wsUrl(server), "wrong")
            val err = assertIs<TstdEvent.Error>(next(seen))
            assertEquals("auth_failed", err.code)
            assertEquals("auth_failed: Invalid auth token.", client.lastFailure)
            assertFalse(client.send(ClientMessages.listSessions()), "never handshaken, so nothing goes out")
            client.close()
            collector.cancel()
        }
    }
}
