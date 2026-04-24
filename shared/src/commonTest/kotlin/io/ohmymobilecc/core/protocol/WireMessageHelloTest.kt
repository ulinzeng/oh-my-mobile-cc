package io.ohmymobilecc.core.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contract tests for the hello.* subtypes added in W1.5 to support the
 * pairing handshake.
 */
class WireMessageHelloTest {
    private val json = ProtocolJson.default

    @Test
    fun decode_ClientHello() {
        val raw =
            """{"op":"hello","deviceId":"dev1","sessionId":"S1","timestampMs":123,"nonce":"n","sig":"s"}"""
        val msg = json.decodeFromString<WireMessage>(raw)
        val hello = assertIs<WireMessage.ClientHello>(msg)
        assertEquals("dev1", hello.deviceId)
        assertEquals("S1", hello.sessionId)
        assertEquals(123L, hello.timestampMs)
        assertEquals("n", hello.nonce)
        assertEquals("s", hello.sig)
    }

    @Test
    fun encode_ClientHello_round_trip() {
        val msg = WireMessage.ClientHello(
            deviceId = "dev1",
            sessionId = "S1",
            timestampMs = 123L,
            nonce = "n",
            sig = "s",
        )
        val text = json.encodeToString<WireMessage>(msg)
        assertEquals(msg, json.decodeFromString<WireMessage>(text))
    }

    @Test
    fun encode_HelloOk_round_trip() {
        val msg = WireMessage.HelloOk(serverTimeMs = 456L, protocolVersion = 1)
        val text = json.encodeToString<WireMessage>(msg)
        assertEquals(msg, json.decodeFromString<WireMessage>(text))
    }

    @Test
    fun decode_HelloErr() {
        val raw = """{"op":"hello.err","reason":"skew"}"""
        val err = assertIs<WireMessage.HelloErr>(json.decodeFromString<WireMessage>(raw))
        assertEquals("skew", err.reason)
    }

    @Test
    fun encode_HelloErr_round_trip() {
        val msg = WireMessage.HelloErr(reason = "unpaired")
        val text = json.encodeToString<WireMessage>(msg)
        assertEquals(msg, json.decodeFromString<WireMessage>(text))
    }
}
