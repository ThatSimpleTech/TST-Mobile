package com.thatsimpletech.assist.core.wire

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import com.thatsimpletech.assist.core.net.Endpoints
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CLOSED, FAILED }

/**
 * A thin client of tstd's socket for Home mode (plan §3). Opens the WebSocket, sends `hello`
 * as the first frame, runs every inbound frame through the [EventGate] and the
 * [SequenceTracker], and publishes what survives on [events]. Nothing else: reconnect
 * backoff and the zombie check belong to the app's lifecycle owner, which knows about
 * Doze and foreground services and this package does not.
 *
 * Every socket comes from core.net.Endpoints, the one place outbound connections are made,
 * so the host is checked against config before the upgrade request exists (plan §1).
 */
class TstdClient(
    private val endpoints: Endpoints,
    val gate: EventGate = EventGate(),
    val sequence: SequenceTracker = SequenceTracker(),
) {
    private val eventFlow = MutableSharedFlow<TstdEvent>(extraBufferCapacity = 1024)
    private val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Gated, sequence-checked daemon events, in arrival order. Unknown kinds never appear here. */
    val events: SharedFlow<TstdEvent> = eventFlow
    val state: StateFlow<ConnectionState> = stateFlow

    /** Sessions this connection follows; re-attached from `lastSeq + 1` after every hello_ack past the first. */
    private val attached = LinkedHashSet<String>()
    private var socket: WebSocket? = null
    private var handshaken = false
    private var hasConnectedOnce = false

    /** Epoch ms of the last frame of any kind; 0 until the socket opens. For the owner's zombie check. */
    @Volatile var lastFrameAt: Long = 0L
        private set

    /** Errors the daemon sent before or during the handshake, for the owner to show. */
    @Volatile var lastFailure: String? = null
        private set

    /** Opens `url` (ws://host:port/) and sends `hello` with [token] as the first frame. */
    @Synchronized
    fun connect(url: String, token: String, version: Int = Protocol.VERSION) {
        check(socket == null) { "already connected" }
        val hello = ClientMessages.hello(token, version)
        stateFlow.value = ConnectionState.CONNECTING
        socket = endpoints.webSocketClient(url).newWebSocket(Request.Builder().url(url).build(), Listener(hello))
    }

    /**
     * client.ts:218-236: sends only on a handshaken socket, returns false otherwise so the
     * caller can decide about an optimistic local update. A `user_message` to a session not
     * yet attached attaches it first on the same socket; frames are processed in order.
     */
    @Synchronized
    fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        if (!handshaken) return false
        if (message.kind == "user_message") {
            val sessionId = (message.body["session_id"] as? JsonPrimitive)?.content
            if (sessionId != null && sessionId !in attached) attach(sessionId)
        }
        return ws.send(message.encode())
    }

    /** Follow a session: `attach{from_seq = lastSeq + 1}` now, and again after each reconnect. */
    @Synchronized
    fun attach(sessionId: String): Boolean {
        attached += sessionId
        return sendRaw(ClientMessages.attach(sessionId, sequence.lastSeq(sessionId) + 1))
    }

    @Synchronized
    fun detach(sessionId: String): Boolean {
        attached -= sessionId
        sequence.forget(sessionId)
        return sendRaw(ClientMessages.detach(sessionId))
    }

    @Synchronized
    fun close() {
        socket?.close(1000, "client closing")
        socket = null
        handshaken = false
        stateFlow.value = ConnectionState.CLOSED
    }

    private fun sendRaw(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        if (!handshaken) return false
        return ws.send(message.encode())
    }

    private fun onHelloAck() {
        val reattach: List<String>
        synchronized(this) {
            handshaken = true
            reattach = if (hasConnectedOnce) attached.toList() else emptyList()
            hasConnectedOnce = true
            stateFlow.value = ConnectionState.CONNECTED
            for (s in reattach) sendRaw(ClientMessages.attach(s, sequence.lastSeq(s) + 1))
        }
    }

    private fun onFrame(text: String) {
        lastFrameAt = System.currentTimeMillis()
        val event = gate.parse(text) ?: return
        when (event) {
            is TstdEvent.HelloAck -> { onHelloAck(); eventFlow.tryEmit(event) }
            is TstdEvent.Ping -> return
            is TstdEvent.Unknown -> return
            else -> when (val verdict = sequence.accept(event)) {
                SequenceTracker.Verdict.Accept -> {
                    if (event is TstdEvent.Error && !handshaken) lastFailure = "${event.code}: ${event.message}"
                    eventFlow.tryEmit(event)
                }
                is SequenceTracker.Verdict.Duplicate -> return
                // The desktop reconnects for a fresh stream; here the daemon replays on the
                // same socket and duplicates from the live stream fall out as Duplicate above.
                is SequenceTracker.Verdict.Gap -> synchronized(this) {
                    sendRaw(ClientMessages.attach(verdict.sessionId, verdict.expected))
                }
            }
        }
    }

    private inner class Listener(private val hello: ClientMessage) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastFrameAt = System.currentTimeMillis()
            webSocket.send(hello.encode())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@TstdClient) {
                if (socket === webSocket) { socket = null; handshaken = false }
                stateFlow.value = ConnectionState.CLOSED
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastFailure = lastFailure ?: (t.message ?: t.toString())
            synchronized(this@TstdClient) {
                if (socket === webSocket) { socket = null; handshaken = false }
                stateFlow.value = ConnectionState.FAILED
            }
        }
    }
}
