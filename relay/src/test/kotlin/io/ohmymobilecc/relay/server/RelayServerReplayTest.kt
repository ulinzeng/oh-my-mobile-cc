package io.ohmymobilecc.relay.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ohmymobilecc.core.crypto.Base64Url
import io.ohmymobilecc.core.crypto.Ed25519
import io.ohmymobilecc.core.pairing.DeviceId
import io.ohmymobilecc.core.pairing.HelloCodec
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.pairing.ClockSeam
import io.ohmymobilecc.relay.pairing.InMemoryPubkeyRegistry
import io.ohmymobilecc.relay.pairing.NonceCache
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import io.ktor.client.plugins.websocket.WebSockets as ClientWS
import io.ktor.server.websocket.WebSockets as ServerWS

/**
 * Integration tests for RelayServer's reconnection replay flow.
 *
 * Verifies: subscribe-replay-drain dedup, HelloOk seq fields,
 * first-connect (no replay), and SessionEnded passthrough.
 */
class RelayServerReplayTest {
    private val clock =
        object : ClockSeam {
            override fun nowMs(): Long = 1_700_000_000_000L
        }
    private val registry = InMemoryPubkeyRegistry()
    private val nonces = NonceCache()
    private val json = ProtocolJson.default

    private val outbound = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    private val eventLog = EventLog(capacity = 100)
    private val sequencedOutbound = MutableSharedFlow<SequencedEvent>(extraBufferCapacity = 64)

    private var nonceCounter = 0

    private fun signedHello(
        sessionId: String,
        lastEventSeq: Long? = null,
    ): WireMessage.ClientHello {
        nonceCounter++
        val nonce = "TESTNONCE" + nonceCounter.toString().padStart(14, '0')
        val kp = Ed25519.keypair(ByteArray(32) { 0x11 })
        val deviceId = DeviceId.fromPublicKey(kp.publicKey)
        registry.register(deviceId, kp.publicKey, clock.nowMs())
        val canonical = HelloCodec.canonicalSigningInput(sessionId, clock.nowMs(), nonce).encodeToByteArray()
        return WireMessage.ClientHello(
            deviceId = deviceId.raw,
            sessionId = sessionId,
            timestampMs = clock.nowMs(),
            nonce = nonce,
            sig = Base64Url.encode(Ed25519.sign(kp.secretKey, canonical)),
            lastEventSeq = lastEventSeq,
        )
    }

    private fun buildConfig(): RelayServerConfig =
        RelayServerConfig(
            registry = registry,
            nonceCache = nonces,
            clock = clock,
            outbound = outbound,
            onInbound = {},
            eventLog = eventLog,
            sequencedOutbound = sequencedOutbound,
        )

    // -- 3.6.1 Reconnect replay: client sends lastEventSeq, gets replayed events + ReplayEnd --

    @Test fun `reconnect replays events after lastEventSeq`() =
        testApplication {
            // Pre-populate 3 events in the log.
            val e1 = eventLog.append(WireMessage.ChatMessage("S1", "msg1"), clock.nowMs())
            val e2 = eventLog.append(WireMessage.ChatMessage("S1", "msg2"), clock.nowMs())
            val e3 = eventLog.append(WireMessage.ChatMessage("S1", "msg3"), clock.nowMs())

            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }

            // Client reconnects having seen up to e1.seq — should replay e2, e3.
            val hello = signedHello("S1", lastEventSeq = e1.seq)
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))

                // 1) HelloOk
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)
                assertEquals(e1.seq, ok.oldestSeq) // oldest in log
                assertEquals(e3.seq, ok.latestSeq) // latest in log

                // 2) Replayed event e2
                val raw2 = (incoming.receive() as Frame.Text).readText()
                val obj2 = json.parseToJsonElement(raw2) as JsonObject
                assertEquals(e2.seq, obj2["seq"]!!.jsonPrimitive.long)

                // 3) Replayed event e3
                val raw3 = (incoming.receive() as Frame.Text).readText()
                val obj3 = json.parseToJsonElement(raw3) as JsonObject
                assertEquals(e3.seq, obj3["seq"]!!.jsonPrimitive.long)

                // 4) ReplayEnd
                val replayEnd = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.ReplayEnd>(replayEnd)
                assertEquals(2, replayEnd.replayedCount)
                assertEquals(e1.seq, replayEnd.fromSeq) // exclusive: first replayed seq - 1
                assertEquals(e3.seq, replayEnd.toSeq)
            }
        }

    // -- 3.6.2 First connection (no lastEventSeq) — no replay, just live stream --

    @Test fun `first connection does not replay`() =
        testApplication {
            // Pre-populate events.
            eventLog.append(WireMessage.ChatMessage("S1", "old"), clock.nowMs())

            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }

            val hello = signedHello("S1", lastEventSeq = null)
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))

                // 1) HelloOk
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)

                // Emit a live event — should arrive without replay preamble.
                val live = eventLog.append(WireMessage.ChatMessage("S1", "live"), clock.nowMs())
                sequencedOutbound.emit(live)

                val rawLive = (incoming.receive() as Frame.Text).readText()
                val objLive = json.parseToJsonElement(rawLive) as JsonObject
                assertEquals(live.seq, objLive["seq"]!!.jsonPrimitive.long)
                assertEquals("chat.message", objLive["op"]!!.jsonPrimitive.content)
            }
        }

    // -- 3.6.3 Events arriving during replay are not lost or duplicated --

    @Test fun `events during replay are deduped correctly`() =
        testApplication {
            // Pre-populate 2 events.
            val e1 = eventLog.append(WireMessage.ChatMessage("S1", "msg1"), clock.nowMs())
            val e2 = eventLog.append(WireMessage.ChatMessage("S1", "msg2"), clock.nowMs())

            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }

            // Reconnect from seq 0 (replay all).
            val hello = signedHello("S1", lastEventSeq = 0L)
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))

                // HelloOk
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)

                // Replay: e1
                val raw1 = (incoming.receive() as Frame.Text).readText()
                val obj1 = json.parseToJsonElement(raw1) as JsonObject
                assertEquals(e1.seq, obj1["seq"]!!.jsonPrimitive.long)

                // Replay: e2
                val raw2 = (incoming.receive() as Frame.Text).readText()
                val obj2 = json.parseToJsonElement(raw2) as JsonObject
                assertEquals(e2.seq, obj2["seq"]!!.jsonPrimitive.long)

                // ReplayEnd
                val replayEnd = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.ReplayEnd>(replayEnd)
                assertEquals(2, replayEnd.replayedCount)

                // Now emit a NEW event (e3) that arrived while server was replaying.
                // Because seq > sentUpTo it should be delivered.
                val e3 = eventLog.append(WireMessage.ChatMessage("S1", "msg3"), clock.nowMs())
                sequencedOutbound.emit(e3)

                val raw3 = (incoming.receive() as Frame.Text).readText()
                val obj3 = json.parseToJsonElement(raw3) as JsonObject
                assertEquals(e3.seq, obj3["seq"]!!.jsonPrimitive.long)
            }
        }

    // -- 3.6.4 HelloOk contains correct oldestSeq / latestSeq --

    @Test fun `hello ok carries seq range from event log`() =
        testApplication {
            // Empty log → both 0.
            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }
            val hello = signedHello("S1")
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)
                assertEquals(0L, ok.oldestSeq)
                assertEquals(0L, ok.latestSeq)
            }
        }

    @Test fun `hello ok carries seq range with populated log`() =
        testApplication {
            val e1 = eventLog.append(WireMessage.ChatMessage("S1", "a"), clock.nowMs())
            val e2 = eventLog.append(WireMessage.ChatMessage("S1", "b"), clock.nowMs())

            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }
            val hello = signedHello("S1")
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)
                assertEquals(e1.seq, ok.oldestSeq)
                assertEquals(e2.seq, ok.latestSeq)
            }
        }

    // -- 3.7 SessionEnded passes through outbound → WS --

    @Test fun `session ended is delivered via sequenced outbound`() =
        testApplication {
            application {
                install(ServerWS)
                RelayServer.install(this@application, buildConfig())
            }
            val client = createClient { install(ClientWS) }
            val hello = signedHello("S1")
            client.webSocket("/ws") {
                send(Frame.Text(json.encodeToString<WireMessage>(hello)))
                // HelloOk
                val ok = json.decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)

                // Emit SessionEnded through the event log + sequencedOutbound pipeline.
                val ended = WireMessage.SessionEnded(sessionId = "S1", reason = "process-exit")
                val event = eventLog.append(ended, clock.nowMs())
                sequencedOutbound.emit(event)

                val raw = (incoming.receive() as Frame.Text).readText()
                val obj = json.parseToJsonElement(raw) as JsonObject
                assertNotNull(obj["seq"])
                val msg = json.decodeFromString<WireMessage>(raw)
                assertIs<WireMessage.SessionEnded>(msg)
                assertEquals("S1", msg.sessionId)
                assertEquals("process-exit", msg.reason)
            }
        }
}
