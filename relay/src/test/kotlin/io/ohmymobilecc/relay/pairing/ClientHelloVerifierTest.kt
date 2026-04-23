package io.ohmymobilecc.relay.pairing

import io.ohmymobilecc.core.crypto.Base64Url
import io.ohmymobilecc.core.crypto.Ed25519
import io.ohmymobilecc.core.pairing.DeviceId
import io.ohmymobilecc.core.pairing.HelloCodec
import io.ohmymobilecc.core.protocol.WireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClientHelloVerifierTest {
    private val clock = FakeClock(startMs = 1_000_000_000L)
    private val registry = InMemoryPubkeyRegistry()
    private val nonces = NonceCache()
    private val verifier = ClientHelloVerifier(registry, nonces, clock)

    private fun signedHello(
        seed: ByteArray = ByteArray(32) { 0x11 },
        sessionId: String = "S1",
        tsMs: Long = clock.nowMs(),
        nonce: String = "AAAAAAAAAAAAAAAAAAAAAA",
        registerPk: Boolean = true,
    ): WireMessage.ClientHello {
        val kp = Ed25519.keypair(seed)
        val deviceId = DeviceId.fromPublicKey(kp.publicKey)
        if (registerPk) registry.register(deviceId, kp.publicKey, clock.nowMs())
        val canonical = HelloCodec.canonicalSigningInput(sessionId, tsMs, nonce).encodeToByteArray()
        val sig = Ed25519.sign(kp.secretKey, canonical)
        return WireMessage.ClientHello(
            deviceId = deviceId.raw,
            sessionId = sessionId,
            timestampMs = tsMs,
            nonce = nonce,
            sig = Base64Url.encode(sig),
        )
    }

    @Test fun `valid hello resolves to deviceId`() {
        val hello = signedHello()
        val result = verifier.verify(hello)
        val ok = assertIs<VerifyResult.Ok>(result)
        assertEquals(hello.deviceId, ok.deviceId.raw)
    }

    @Test fun `positive skew over 60s rejected`() {
        val hello = signedHello(tsMs = clock.nowMs() + 61 * 1000)
        assertIs<VerifyResult.Err>(verifier.verify(hello)).also { assertEquals("skew", it.reason) }
    }

    @Test fun `negative skew over 60s rejected`() {
        val hello = signedHello(tsMs = clock.nowMs() - 61 * 1000)
        assertIs<VerifyResult.Err>(verifier.verify(hello)).also { assertEquals("skew", it.reason) }
    }

    @Test fun `replayed nonce rejected`() {
        val hello = signedHello()
        verifier.verify(hello)
        val replayed = verifier.verify(hello)
        assertIs<VerifyResult.Err>(replayed).also { assertEquals("nonce", it.reason) }
    }

    @Test fun `unpaired deviceId rejected`() {
        val hello = signedHello(registerPk = false)
        assertIs<VerifyResult.Err>(verifier.verify(hello)).also { assertEquals("unpaired", it.reason) }
    }

    @Test fun `revoked deviceId rejected`() {
        val hello = signedHello()
        val id = DeviceId(hello.deviceId)
        registry.revoke(id, clock.nowMs())
        assertIs<VerifyResult.Err>(verifier.verify(hello)).also { assertEquals("revoked", it.reason) }
    }

    @Test fun `wrong signature rejected`() {
        val hello = signedHello().copy(sig = Base64Url.encode(ByteArray(64)))
        assertIs<VerifyResult.Err>(verifier.verify(hello)).also { assertEquals("sig", it.reason) }
    }
}
