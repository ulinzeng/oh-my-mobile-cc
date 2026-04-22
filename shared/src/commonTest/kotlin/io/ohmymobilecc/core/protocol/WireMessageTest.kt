package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contract tests for [WireMessage] — the sealed type the mobile client and
 * the relay exchange over a WebSocket.
 */
class WireMessageTest {
    private val json = ProtocolJson.default

    @Test
    fun `decodes chat message`() {
        val line = """{"op":"chat.message","sessionId":"s1","text":"hi"}"""
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.ChatMessage>(msg)
        assertEquals("s1", msg.sessionId)
        assertEquals("hi", msg.text)
    }

    @Test
    fun `decodes approval requested`() {
        val input = """{"command":"ls"}"""
        val line =
            buildString {
                append("""{"op":"approval.requested","approvalId":"A1","sessionId":"S1",""")
                append(""""tool":"Bash","input":$input,"proposedAt":1710000000000}""")
            }
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.ApprovalRequested>(msg)
        assertEquals("A1", msg.approvalId)
        assertEquals("S1", msg.sessionId)
        assertEquals("Bash", msg.tool)
        assertEquals(1710000000000L, msg.proposedAt)
        assertEquals("ls", msg.input["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes approval responded with Allow_Once decision`() {
        val line =
            buildString {
                append("""{"op":"approval.responded","approvalId":"A1",""")
                append(""""decision":"ALLOW_ONCE"}""")
            }
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.ApprovalResponded>(msg)
        assertEquals("A1", msg.approvalId)
        assertEquals(Decision.ALLOW_ONCE, msg.decision)
    }

    @Test
    fun `decodes approval responded with Customize and custom input`() {
        val line =
            buildString {
                append("""{"op":"approval.responded","approvalId":"A2",""")
                append(""""decision":"CUSTOMIZE","customInput":{"command":"ls /tmp"}}""")
            }
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.ApprovalResponded>(msg)
        assertEquals(Decision.CUSTOMIZE, msg.decision)
        assertEquals(
            "ls /tmp",
            msg.customInput
                ?.get("command")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `decodes approval expired`() {
        val line = """{"op":"approval.expired","approvalId":"A1","reason":"timeout"}"""
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.ApprovalExpired>(msg)
        assertEquals("A1", msg.approvalId)
        assertEquals("timeout", msg.reason)
    }

    @Test
    fun `decodes unknown op as Unknown preserving raw`() {
        val line = """{"op":"future.op","payload":{"x":1}}"""
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.Unknown>(msg)
        assertEquals("future.op", msg.raw["op"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes json without op as Unknown`() {
        val line = """{"foo":"bar"}"""
        val msg = json.decodeFromString(WireMessage.serializer(), line)
        assertIs<WireMessage.Unknown>(msg)
    }

    // ---- round-trip ----

    @Test
    fun `round-trips ApprovalRequested`() {
        val original =
            WireMessage.ApprovalRequested(
                approvalId = "A1",
                sessionId = "S1",
                tool = "Bash",
                input = buildJsonObject { put("command", JsonPrimitive("ls")) },
                proposedAt = 1L,
            )
        val encoded = json.encodeToString(WireMessage.serializer(), original)
        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertIs<WireMessage.ApprovalRequested>(decoded)
        assertEquals(original.approvalId, decoded.approvalId)
        assertEquals(original.tool, decoded.tool)
        assertEquals(original.proposedAt, decoded.proposedAt)
    }

    @Test
    fun `round-trips Unknown verbatim`() {
        val raw =
            buildJsonObject {
                put("op", JsonPrimitive("mystery"))
                put("x", JsonPrimitive(7))
            }
        val original = WireMessage.Unknown(raw = raw)
        val encoded = json.encodeToString(WireMessage.serializer(), original)
        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertIs<WireMessage.Unknown>(decoded)
        assertEquals(raw, decoded.raw)
    }

    @Test
    fun `ApprovalResponded encode omits null customInput by default`() {
        val msg =
            WireMessage.ApprovalResponded(
                approvalId = "A1",
                decision = Decision.ALLOW_ONCE,
                customInput = null,
            )
        val encoded = json.encodeToString(WireMessage.serializer(), msg)
        // encodeDefaults=false + null customInput → field absent
        assertEquals(false, encoded.contains("customInput"))
    }
}
