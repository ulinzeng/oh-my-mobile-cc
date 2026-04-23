package io.ohmymobilecc.relay.approval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Relay-internal RPC protocol between the main relay process and the
 * `relay-cli approval-bridge` subprocess spawned by Claude Code's
 * `PreToolUse` hook.
 *
 * This is **not** the `WireMessage` protocol used on the mobile
 * WebSocket — keeping the two separate lets the IPC format evolve
 * independently (auth, multiplexing, backpressure) without touching
 * the mobile app.
 */
@Serializable
public data class BridgeRequest(
    val sessionId: String,
    val toolName: String,
    val toolUseId: String,
    val toolInput: JsonObject,
    /** Full hook payload as received from CC, preserved for forensics. */
    val hookPayload: JsonObject,
)

/**
 * Response from the main relay to the waiting subprocess. Fields map
 * 1:1 to the CC hook output schema (`hookSpecificOutput`): the
 * subprocess embeds this payload under `hookSpecificOutput` and
 * prints the whole thing to stdout.
 */
@Serializable
public data class BridgeDecision(
    /** `"allow"` or `"deny"`. */
    val permissionDecision: String,
    val permissionDecisionReason: String,
    /**
     * Present only when the mobile user picked `CUSTOMIZE`; supersedes
     * the original `tool_input` CC would have used. Serialized only
     * when non-null thanks to `encodeDefaults=false` on
     * `ProtocolJson.default`.
     */
    val updatedInput: JsonObject? = null,
)
