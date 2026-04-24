package io.ohmymobilecc.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base64UrlTest {
    @Test
    fun encode_decode_round_trip() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 127, -1, -128)
        val s = Base64Url.encode(bytes)
        assertContentEquals(bytes, Base64Url.decode(s))
    }

    @Test
    fun empty_round_trip() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertContentEquals(ByteArray(0), Base64Url.decode(""))
    }

    @Test
    fun no_padding() {
        val s = Base64Url.encode(byteArrayOf(1))
        assertEquals(false, s.contains('='), "no '=' in base64url-nopad")
    }

    @Test
    fun url_safe_alphabet() {
        // Inputs that produce `+` / `/` in standard base64.
        val s = Base64Url.encode(ByteArray(32) { (it * 31).toByte() })
        assertEquals(false, s.contains('+'), "'+' not allowed")
        assertEquals(false, s.contains('/'), "'/' not allowed")
    }

    @Test
    fun rejects_invalid_char() {
        assertFailsWith<IllegalArgumentException> { Base64Url.decode("!!!!") }
    }

    @Test
    fun round_trip_32_bytes() {
        // Ed25519 pubkey shape — round-trip confirms our signing path works
        // when we store pubkeys as base64url.
        val bytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val s = Base64Url.encode(bytes)
        assertEquals(43, s.length, "32 bytes => 43 chars base64url-no-pad")
        assertContentEquals(bytes, Base64Url.decode(s))
    }
}
