package io.ohmymobilecc.core.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Contract tests for [HelloCodec] and [DeviceId].
 *
 * Anchors the canonical signing-input shape from openspec/specs/pairing/spec.md
 * §Ed25519 会话签名 so any future drift would require a spec change.
 */
class HelloCodecTest {
    @Test
    fun canonical_signing_input_is_pipe_separated() {
        val canonical = HelloCodec.canonicalSigningInput(
            sessionId = "S1",
            timestampMs = 1_745_000_000_000L,
            nonce = "AAAAAAAAAAAAAAAAAAAAAA",
        )
        assertEquals("S1|1745000000000|AAAAAAAAAAAAAAAAAAAAAA", canonical)
    }

    @Test
    fun canonical_signing_input_preserves_session_id_verbatim() {
        // We deliberately do NOT escape `|` in sessionId — if a caller passes
        // one through, the canonical string becomes ambiguous. Document that
        // as a precondition: sessionIds should be pairing-flow opaque ids,
        // not free-form user strings.
        val canonical = HelloCodec.canonicalSigningInput(
            sessionId = "sess-abc_123",
            timestampMs = 0L,
            nonce = "n",
        )
        assertEquals("sess-abc_123|0|n", canonical)
    }

    @Test
    fun deviceId_from_pubkey_is_22_chars_base64url() {
        val pk = ByteArray(32) { it.toByte() }
        val id = DeviceId.fromPublicKey(pk)
        // 16 bytes base64url-no-pad == 22 chars.
        assertEquals(22, id.raw.length)
    }

    @Test
    fun deviceId_is_deterministic_per_pubkey() {
        val pk = ByteArray(32) { it.toByte() }
        assertEquals(DeviceId.fromPublicKey(pk), DeviceId.fromPublicKey(pk))
    }

    @Test
    fun deviceId_differs_across_pubkeys() {
        val a = DeviceId.fromPublicKey(ByteArray(32) { 0x11 })
        val b = DeviceId.fromPublicKey(ByteArray(32) { 0x22 })
        // Not asserting any property of the alphabet — just that the mapping
        // is injective on these two inputs, which any reasonable hash is.
        assert(a != b)
    }

    @Test
    fun deviceId_rejects_non_32_byte_pubkey() {
        assertFailsWith<IllegalArgumentException> {
            DeviceId.fromPublicKey(ByteArray(16))
        }
    }
}
