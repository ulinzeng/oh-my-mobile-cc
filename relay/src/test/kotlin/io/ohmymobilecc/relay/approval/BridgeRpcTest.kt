package io.ohmymobilecc.relay.approval

import io.ohmymobilecc.core.protocol.ProtocolJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Round-trip tests for the internal relay↔subcommand RPC types. These
 * are intentionally NOT part of the mobile `WireMessage` hierarchy —
 * the IPC format is relay-private and can evolve independently.
 */
class BridgeRpcTest {
    private val json = ProtocolJson.default

    @Test
    fun `round-trips BridgeRequest`() {
        val original =
            BridgeRequest(
                sessionId = "S1",
                toolName = "Bash",
                toolUseId = "T1",
                toolInput = buildJsonObject { put("command", JsonPrimitive("ls")) },
                hookPayload =
                    buildJsonObject {
                        put("tool_use_id", JsonPrimitive("T1"))
                        put("session_id", JsonPrimitive("S1"))
                    },
            )
        val encoded = json.encodeToString(BridgeRequest.serializer(), original)
        val decoded = json.decodeFromString(BridgeRequest.serializer(), encoded)

        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.toolName, decoded.toolName)
        assertEquals(original.toolUseId, decoded.toolUseId)
        assertEquals("ls", decoded.toolInput["command"]?.jsonPrimitive?.content)
        assertEquals("T1", decoded.hookPayload["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips BridgeDecision with updatedInput`() {
        val original =
            BridgeDecision(
                permissionDecision = "allow",
                permissionDecisionReason = "customized by mobile user",
                updatedInput = buildJsonObject { put("command", JsonPrimitive("ls /tmp")) },
            )
        val encoded = json.encodeToString(BridgeDecision.serializer(), original)
        val decoded = json.decodeFromString(BridgeDecision.serializer(), encoded)

        assertEquals(original.permissionDecision, decoded.permissionDecision)
        assertEquals(original.permissionDecisionReason, decoded.permissionDecisionReason)
        assertEquals(
            "ls /tmp",
            decoded.updatedInput
                ?.get("command")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `BridgeDecision omits updatedInput when null`() {
        val original =
            BridgeDecision(
                permissionDecision = "deny",
                permissionDecisionReason = "timeout",
                updatedInput = null,
            )
        val encoded = json.encodeToString(BridgeDecision.serializer(), original)

        assertFalse(
            encoded.contains("updatedInput"),
            "encodeDefaults=false should drop null updatedInput, got: $encoded",
        )

        val decoded = json.decodeFromString(BridgeDecision.serializer(), encoded)
        assertNull(decoded.updatedInput)
        assertEquals("deny", decoded.permissionDecision)
    }
}
