package io.ohmymobilecc.core.transport

import io.ohmymobilecc.core.crypto.Ed25519
import io.ohmymobilecc.core.pairing.DeviceId
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Contract test for the JVM `KtorRelayClient` actual of `TransportPort`.
 *
 * Lives in `jvmTest` (not `commonTest`) because the fake relay depends on
 * `embeddedServer(Netty, …)` which is JVM-only. iOS / Android actuals of
 * `KtorRelayClient` — if we ever write them — will need their own engine.
 *
 * The fake relay speaks the minimal ClientHello → HelloOk / HelloErr /
 * protocol-violation handshake and intentionally does NOT reuse
 * `RelayServer`: coupling this test to the full verifier + registry +
 * nonce-cache stack would make it duplicate `RelayServerTest`.
 */
class KtorRelayClientTest {
    @Test
    fun `happy path returns TransportSession and streams pushed frames`(): Unit =
        runBlocking {
            startFakeRelay(FakeRelayMode.HAPPY).use { relay ->
                val client = newClient()
                try {
                    val result =
                        client.connect(
                            TransportEndpoint(host = "127.0.0.1", port = relay.port, sessionId = "S1"),
                            makeIdentity(),
                        )
                    val session = result.getOrElse { fail("expected success, got failure: $it") }
                    val pushed = session.incoming.first()
                    assertIs<WireMessage.ApprovalRequested>(pushed)
                    assertEquals("a1", pushed.approvalId)
                    session.close()
                } finally {
                    client.shutdown()
                }
            }
        }

    @Test
    fun `relay HelloErr revoked maps to RelayError Rejected`(): Unit =
        runBlocking {
            startFakeRelay(FakeRelayMode.REVOKED).use { relay ->
                val client = newClient()
                try {
                    val result =
                        client.connect(
                            TransportEndpoint(host = "127.0.0.1", port = relay.port, sessionId = "S2"),
                            makeIdentity(),
                        )
                    val failure = result.exceptionOrNull()
                    assertIs<RelayError.Rejected>(failure)
                    assertEquals("revoked", failure.reason)
                } finally {
                    client.shutdown()
                }
            }
        }

    @Test
    fun `relay frame before HelloOk is ProtocolViolation`(): Unit =
        runBlocking {
            startFakeRelay(FakeRelayMode.PROTOCOL_VIOLATION).use { relay ->
                val client = newClient()
                try {
                    val result =
                        client.connect(
                            TransportEndpoint(host = "127.0.0.1", port = relay.port, sessionId = "S3"),
                            makeIdentity(),
                        )
                    val failure = result.exceptionOrNull()
                    assertIs<RelayError.ProtocolViolation>(failure)
                } finally {
                    client.shutdown()
                }
            }
        }

    private fun newClient(): KtorRelayClient =
        KtorRelayClient(
            httpClient = defaultClientForTest(),
            clock = { 1_700_000_000_000L },
        )

    private fun makeIdentity(): DeviceIdentity {
        val kp = Ed25519.keypair(ByteArray(32) { 0x22 })
        return DeviceIdentity(
            deviceId = DeviceId.fromPublicKey(kp.publicKey),
            publicKey = kp.publicKey,
            secretKey = kp.secretKey,
        )
    }
}
