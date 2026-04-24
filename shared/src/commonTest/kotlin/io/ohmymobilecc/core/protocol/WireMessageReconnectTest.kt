package io.ohmymobilecc.core.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for reconnection-related WireMessage extensions (W1.5 Slice B):
 * - ClientHello.lastEventSeq
 * - HelloOk.oldestSeq / latestSeq
 * - ReplayEnd
 * - SessionEnded
 * - encodeWithSeq helper
 */
class WireMessageReconnectTest {
    private val json = ProtocolJson.default

    // -- ClientHello with lastEventSeq --

    @Test
    fun clientHello_with_lastEventSeq_round_trip() {
        val msg = WireMessage.ClientHello(
            deviceId = "dev1",
            sessionId = "S1",
            timestampMs = 100L,
            nonce = "n1",
            sig = "sig1",
            lastEventSeq = 42L,
        )
        val text = json.encodeToString<WireMessage>(msg)
        val decoded = json.decodeFromString<WireMessage>(text)
        assertEquals(msg, decoded)
    }

    @Test
    fun clientHello_without_lastEventSeq_omits_field_in_json() {
        val msg = WireMessage.ClientHello(
            deviceId = "dev1",
            sessionId = "S1",
            timestampMs = 100L,
            nonce = "n1",
            sig = "sig1",
        )
        val text = json.encodeToString<WireMessage>(msg)
        val obj = json.decodeFromString<JsonObject>(text)
        assertTrue("lastEventSeq" !in obj, "lastEventSeq should be absent when null")
    }

    @Test
    fun clientHello_without_lastEventSeq_defaults_to_null() {
        val raw =
            """{"op":"hello","deviceId":"d","sessionId":"s","timestampMs":1,"nonce":"n","sig":"s"}"""
        val msg = assertIs<WireMessage.ClientHello>(json.decodeFromString<WireMessage>(raw))
        assertNull(msg.lastEventSeq)
    }

    @Test
    fun clientHello_with_lastEventSeq_from_json() {
        val raw =
            """{"op":"hello","deviceId":"d","sessionId":"s","timestampMs":1,"nonce":"n","sig":"s","lastEventSeq":99}"""
        val msg = assertIs<WireMessage.ClientHello>(json.decodeFromString<WireMessage>(raw))
        assertEquals(99L, msg.lastEventSeq)
    }

    // -- HelloOk with oldestSeq / latestSeq --

    @Test
    fun helloOk_with_seq_range_round_trip() {
        val msg = WireMessage.HelloOk(
            serverTimeMs = 500L,
            protocolVersion = 1,
            oldestSeq = 10L,
            latestSeq = 50L,
        )
        val text = json.encodeToString<WireMessage>(msg)
        val decoded = json.decodeFromString<WireMessage>(text)
        assertEquals(msg, decoded)
    }

    @Test
    fun helloOk_without_seq_fields_defaults_to_zero() {
        val raw = """{"op":"hello.ok","serverTimeMs":500,"protocolVersion":1}"""
        val msg = assertIs<WireMessage.HelloOk>(json.decodeFromString<WireMessage>(raw))
        assertEquals(0L, msg.oldestSeq)
        assertEquals(0L, msg.latestSeq)
    }

    // -- ReplayEnd --

    @Test
    fun replayEnd_round_trip() {
        val msg = WireMessage.ReplayEnd(
            replayedCount = 5,
            fromSeq = 10L,
            toSeq = 14L,
        )
        val text = json.encodeToString<WireMessage>(msg)
        val decoded = json.decodeFromString<WireMessage>(text)
        assertEquals(msg, decoded)
    }

    @Test
    fun replayEnd_decode_from_json() {
        val raw = """{"op":"replay.end","replayedCount":3,"fromSeq":1,"toSeq":3}"""
        val msg = assertIs<WireMessage.ReplayEnd>(json.decodeFromString<WireMessage>(raw))
        assertEquals(3, msg.replayedCount)
        assertEquals(1L, msg.fromSeq)
        assertEquals(3L, msg.toSeq)
    }

    // -- SessionEnded --

    @Test
    fun sessionEnded_round_trip() {
        val msg = WireMessage.SessionEnded(
            sessionId = "S42",
            reason = "timeout",
        )
        val text = json.encodeToString<WireMessage>(msg)
        val decoded = json.decodeFromString<WireMessage>(text)
        assertEquals(msg, decoded)
    }

    @Test
    fun sessionEnded_decode_from_json() {
        val raw = """{"op":"session.ended","sessionId":"S1","reason":"evicted"}"""
        val msg = assertIs<WireMessage.SessionEnded>(json.decodeFromString<WireMessage>(raw))
        assertEquals("S1", msg.sessionId)
        assertEquals("evicted", msg.reason)
    }

    // -- encodeWithSeq --

    @Test
    fun encodeWithSeq_includes_seq_field() {
        val msg = WireMessage.ChatMessage(sessionId = "S1", text = "hi")
        val text = encodeWithSeq(json, msg, seq = 7L)
        val obj = json.decodeFromString<JsonObject>(text)
        assertEquals(7L, obj["seq"]?.jsonPrimitive?.long)
        assertEquals("chat.message", obj["op"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodeWithSeq_preserves_all_original_fields() {
        val msg = WireMessage.ReplayEnd(replayedCount = 2, fromSeq = 1L, toSeq = 2L)
        val text = encodeWithSeq(json, msg, seq = 100L)
        val obj = json.decodeFromString<JsonObject>(text)
        assertEquals(100L, obj["seq"]?.jsonPrimitive?.long)
        assertEquals("replay.end", obj["op"]?.jsonPrimitive?.content)
        assertEquals(2, obj["replayedCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1L, obj["fromSeq"]?.jsonPrimitive?.long)
        assertEquals(2L, obj["toSeq"]?.jsonPrimitive?.long)
    }
}
