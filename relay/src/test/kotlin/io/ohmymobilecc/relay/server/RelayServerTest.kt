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
import io.ohmymobilecc.core.protocol.Decision
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.pairing.ClockSeam
import io.ohmymobilecc.relay.pairing.InMemoryPubkeyRegistry
import io.ohmymobilecc.relay.pairing.NonceCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import io.ktor.client.plugins.websocket.WebSockets as ClientWS
import io.ktor.server.websocket.WebSockets as ServerWS

class RelayServerTest {
    private val clock =
        object : ClockSeam {
            override fun nowMs(): Long = 1_700_000_000_000L
        }
    private val registry = InMemoryPubkeyRegistry()
    private val nonces = NonceCache()
    private val outbound = MutableSharedFlow<WireMessage>(extraBufferCapacity = 16)
    private val inbound = CompletableDeferred<WireMessage>()

    private fun signedHello(
        sessionId: String,
        nonce: String = "TESTNONCE000000000000AA",
    ): WireMessage.ClientHello {
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
        )
    }

    @Test fun `paired client receives hello_ok and pumps outbound`() =
        testApplication {
            application {
                install(ServerWS)
                RelayServer.install(
                    this@application,
                    registry = registry,
                    nonceCache = nonces,
                    clock = clock,
                    outbound = outbound,
                    onInbound = { inbound.complete(it) },
                )
            }
            val client = createClient { install(ClientWS) }
            val hello = signedHello("S1")
            client.webSocket("/ws") {
                send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(hello)))
                val ok =
                    ProtocolJson.default
                        .decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok)

                // Server pushes an ApprovalRequested via outbound → client receives it.
                outbound.emit(
                    WireMessage.ApprovalRequested(
                        approvalId = "a1",
                        sessionId = "S1",
                        tool = "Bash",
                        input = JsonObject(emptyMap()),
                        proposedAt = 0L,
                    ),
                )
                val msg =
                    ProtocolJson.default
                        .decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.ApprovalRequested>(msg)

                // Client → server: send ApprovalResponded; server should forward via onInbound.
                send(
                    Frame.Text(
                        ProtocolJson.default.encodeToString<WireMessage>(
                            WireMessage.ApprovalResponded("a1", Decision.ALLOW_ONCE),
                        ),
                    ),
                )
            }
            val forwarded = inbound.await()
            assertIs<WireMessage.ApprovalResponded>(forwarded)
            assertEquals("a1", forwarded.approvalId)
        }

    @Test fun `unpaired hello replies hello_err and closes`() =
        testApplication {
            application {
                install(ServerWS)
                RelayServer.install(
                    this@application,
                    registry = InMemoryPubkeyRegistry(), // empty — no paired keys
                    nonceCache = NonceCache(),
                    clock = clock,
                    outbound = outbound,
                    onInbound = {},
                )
            }
            val client = createClient { install(ClientWS) }
            // signedHello registers the pk in this.registry (shared), NOT the empty one above.
            val hello = signedHello("S1")
            client.webSocket("/ws") {
                send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(hello)))
                val err =
                    ProtocolJson.default
                        .decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloErr>(err)
                assertEquals("unpaired", err.reason)
            }
        }

    @Test fun `second connect same sessionId rejected`() =
        testApplication {
            application {
                install(ServerWS)
                RelayServer.install(
                    this@application,
                    registry = registry,
                    nonceCache = nonces,
                    clock = clock,
                    outbound = outbound,
                    onInbound = {},
                )
            }
            val client1 = createClient { install(ClientWS) }
            val client2 = createClient { install(ClientWS) }
            val hello1 = signedHello("S1", nonce = "TESTNONCE0000000000001AA")
            val hello2 = signedHello("S1", nonce = "TESTNONCE0000000000002BB")

            client1.webSocket("/ws") {
                send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(hello1)))
                val ok1 =
                    ProtocolJson.default
                        .decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                assertIs<WireMessage.HelloOk>(ok1)

                // While first client still holds the slot, try a second hello on same sessionId.
                client2.webSocket("/ws") {
                    send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(hello2)))
                    val err =
                        ProtocolJson.default
                            .decodeFromString<WireMessage>((incoming.receive() as Frame.Text).readText())
                    assertIs<WireMessage.HelloErr>(err)
                    assertEquals("duplicate-session", err.reason)
                }
            }
        }
}
