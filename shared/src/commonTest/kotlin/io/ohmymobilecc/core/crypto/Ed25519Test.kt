package io.ohmymobilecc.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test vectors from RFC 8032 §7.1 (Ed25519).
 * https://www.rfc-editor.org/rfc/rfc8032#section-7.1
 *
 * These are the authoritative byte-for-byte vectors; passing them is a
 * strong correctness signal for a from-scratch Ed25519 implementation.
 */
class Ed25519Test {
    @Test
    fun rfc8032_vector1_empty_message() {
        val seed = hexDecode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPk = hexDecode("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val expectedSig = hexDecode(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )

        val kp = Ed25519.keypair(seed)
        assertContentEquals(expectedPk, kp.publicKey, "pk derived from seed")

        val sig = Ed25519.sign(kp.secretKey, ByteArray(0))
        assertContentEquals(expectedSig, sig, "sig over empty message")

        assertTrue(Ed25519.verify(kp.publicKey, ByteArray(0), sig))
        assertFalse(Ed25519.verify(kp.publicKey, byteArrayOf(0x01), sig), "wrong message rejected")
    }

    @Test
    fun rfc8032_vector2_one_byte_message() {
        val seed = hexDecode("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val msg = hexDecode("72")
        val expectedSig = hexDecode(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        )
        val kp = Ed25519.keypair(seed)
        assertContentEquals(expectedSig, Ed25519.sign(kp.secretKey, msg))
        assertTrue(Ed25519.verify(kp.publicKey, msg, expectedSig))
    }

    @Test
    fun rejects_sig_from_different_keypair() {
        val kpA = Ed25519.keypair(ByteArray(32) { 0x11 })
        val kpB = Ed25519.keypair(ByteArray(32) { 0x22 })
        val msg = "hello".encodeToByteArray()
        val sigA = Ed25519.sign(kpA.secretKey, msg)
        assertFalse(Ed25519.verify(kpB.publicKey, msg, sigA))
    }
}

private fun hexDecode(s: String): ByteArray {
    require(s.length % 2 == 0) { "hex string length must be even: ${s.length}" }
    return ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
