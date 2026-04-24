package io.ohmymobilecc.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** User decision for a pending approval, issued from the mobile client. */
public enum class Decision {
    ALLOW_ONCE,
    ALLOW_ALWAYS,
    DENY,
    CUSTOMIZE,
}

/**
 * Sealed model of every frame exchanged between mobile and relay over the
 * project's WebSocket transport. Discriminator is `op` (not `type`, which is
 * reserved for the relay's embedded [CCEvent] wire format).
 *
 * Unknown op values decode to [Unknown] to keep new relay/client versions
 * compatible with older peers.
 */
@Serializable(with = WireMessageSerializer::class)
public sealed class WireMessage {
    // -- chat.* --

    public data class ChatMessage(
        val sessionId: String,
        val text: String,
    ) : WireMessage()

    // -- approval.* --

    public data class ApprovalRequested(
        val approvalId: String,
        val sessionId: String,
        val tool: String,
        val input: JsonObject,
        val proposedAt: Long,
    ) : WireMessage()

    public data class ApprovalResponded(
        val approvalId: String,
        val decision: Decision,
        val customInput: JsonObject? = null,
    ) : WireMessage()

    public data class ApprovalExpired(
        val approvalId: String,
        val reason: String,
    ) : WireMessage()

    // -- hello.* (W1.5 pairing handshake) --

    /**
     * First WS frame sent by the mobile client after the TCP/WSS upgrade.
     * Proves possession of the paired Ed25519 private key for [sessionId].
     *
     * See `openspec/specs/pairing/spec.md` §Ed25519 会话签名 for the
     * canonical signing input: `sessionId || "|" || timestampMs || "|" || nonce`.
     */
    public data class ClientHello(
        val deviceId: String,
        val sessionId: String,
        val timestampMs: Long,
        val nonce: String,
        val sig: String,
        val lastEventSeq: Long? = null,
    ) : WireMessage()

    /** Relay acknowledgement of a successful hello — mirrors server time for client-side skew detection. */
    public data class HelloOk(
        val serverTimeMs: Long,
        val protocolVersion: Int,
        val oldestSeq: Long = 0,
        val latestSeq: Long = 0,
    ) : WireMessage()

    /**
     * Relay rejection of a hello. [reason] is one of: `skew`, `nonce`, `sig`,
     * `revoked`, `unpaired`, `duplicate-session`, `malformed`, `expected-hello`.
     */
    public data class HelloErr(
        val reason: String,
    ) : WireMessage()

    // -- terminal.* (stubbed; full shape arrives with W3 change proposal) --

    public data class TerminalOutput(
        val sessionId: String,
        val chunkBase64: String,
    ) : WireMessage()

    // -- file.* (stubbed; full shape arrives with W4 change proposal) --

    public data class FileListRequest(
        val sessionId: String,
        val path: String,
    ) : WireMessage()

    // -- replay.* (reconnection protocol, W1.5) --

    /** Relay signals end of event replay after a reconnection. */
    public data class ReplayEnd(
        val replayedCount: Int,
        val fromSeq: Long,
        val toSeq: Long,
    ) : WireMessage()

    // -- session.* --

    /** Relay notifies that a session has ended. */
    public data class SessionEnded(
        val sessionId: String,
        val reason: String,
    ) : WireMessage()

    /** Any frame whose `op` we cannot type yet. */
    public data class Unknown(
        val raw: JsonObject,
    ) : WireMessage()
}

/**
 * Custom JSON serializer for the [WireMessage] hierarchy, keyed off the
 * `op` discriminator. Unknown ops decode to [WireMessage.Unknown].
 */
public object WireMessageSerializer : KSerializer<WireMessage> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.ohmymobilecc.core.protocol.WireMessage")

    override fun deserialize(decoder: Decoder): WireMessage {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("WireMessage requires a JsonDecoder; got ${decoder::class.simpleName}")
        val element: JsonElement = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject ?: return WireMessage.Unknown(JsonObject(emptyMap()))

        val op = obj["op"]?.jsonPrimitive?.content
        return when (op) {
            "chat.message" ->
                WireMessage.ChatMessage(
                    sessionId = requireString(obj, "sessionId"),
                    text = requireString(obj, "text"),
                )
            "approval.requested" ->
                WireMessage.ApprovalRequested(
                    approvalId = requireString(obj, "approvalId"),
                    sessionId = requireString(obj, "sessionId"),
                    tool = requireString(obj, "tool"),
                    input = (obj["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                    proposedAt =
                        obj["proposedAt"]?.jsonPrimitive?.long
                            ?: error("approval.requested missing proposedAt"),
                )
            "approval.responded" ->
                WireMessage.ApprovalResponded(
                    approvalId = requireString(obj, "approvalId"),
                    decision = Decision.valueOf(requireString(obj, "decision")),
                    customInput = obj["customInput"] as? JsonObject,
                )
            "approval.expired" ->
                WireMessage.ApprovalExpired(
                    approvalId = requireString(obj, "approvalId"),
                    reason = requireString(obj, "reason"),
                )
            "hello" ->
                WireMessage.ClientHello(
                    deviceId = requireString(obj, "deviceId"),
                    sessionId = requireString(obj, "sessionId"),
                    timestampMs =
                        obj["timestampMs"]?.jsonPrimitive?.long
                            ?: error("hello missing timestampMs"),
                    nonce = requireString(obj, "nonce"),
                    sig = requireString(obj, "sig"),
                    lastEventSeq = obj["lastEventSeq"]?.jsonPrimitive?.longOrNull,
                )
            "hello.ok" ->
                WireMessage.HelloOk(
                    serverTimeMs =
                        obj["serverTimeMs"]?.jsonPrimitive?.long
                            ?: error("hello.ok missing serverTimeMs"),
                    protocolVersion =
                        obj["protocolVersion"]?.jsonPrimitive?.int
                            ?: error("hello.ok missing protocolVersion"),
                    oldestSeq = obj["oldestSeq"]?.jsonPrimitive?.longOrNull ?: 0L,
                    latestSeq = obj["latestSeq"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            "hello.err" ->
                WireMessage.HelloErr(
                    reason = requireString(obj, "reason"),
                )
            "terminal.output" ->
                WireMessage.TerminalOutput(
                    sessionId = requireString(obj, "sessionId"),
                    chunkBase64 = requireString(obj, "chunkBase64"),
                )
            "file.list_request" ->
                WireMessage.FileListRequest(
                    sessionId = requireString(obj, "sessionId"),
                    path = requireString(obj, "path"),
                )
            "replay.end" ->
                WireMessage.ReplayEnd(
                    replayedCount =
                        obj["replayedCount"]?.jsonPrimitive?.int
                            ?: error("replay.end missing replayedCount"),
                    fromSeq =
                        obj["fromSeq"]?.jsonPrimitive?.long
                            ?: error("replay.end missing fromSeq"),
                    toSeq =
                        obj["toSeq"]?.jsonPrimitive?.long
                            ?: error("replay.end missing toSeq"),
                )
            "session.ended" ->
                WireMessage.SessionEnded(
                    sessionId = requireString(obj, "sessionId"),
                    reason = requireString(obj, "reason"),
                )
            else -> WireMessage.Unknown(raw = obj)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: WireMessage,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("WireMessage requires a JsonEncoder; got ${encoder::class.simpleName}")
        jsonEncoder.encodeJsonElement(encode(value))
    }

    internal fun encode(value: WireMessage): JsonElement =
        when (value) {
            is WireMessage.ChatMessage ->
                buildJsonObject {
                    put("op", JsonPrimitive("chat.message"))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("text", JsonPrimitive(value.text))
                }
            is WireMessage.ApprovalRequested ->
                buildJsonObject {
                    put("op", JsonPrimitive("approval.requested"))
                    put("approvalId", JsonPrimitive(value.approvalId))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("tool", JsonPrimitive(value.tool))
                    put("input", value.input)
                    put("proposedAt", JsonPrimitive(value.proposedAt))
                }
            is WireMessage.ApprovalResponded ->
                buildJsonObject {
                    put("op", JsonPrimitive("approval.responded"))
                    put("approvalId", JsonPrimitive(value.approvalId))
                    put("decision", JsonPrimitive(value.decision.name))
                    // encodeDefaults=false semantics: omit null customInput
                    value.customInput?.let { put("customInput", it) }
                }
            is WireMessage.ApprovalExpired ->
                buildJsonObject {
                    put("op", JsonPrimitive("approval.expired"))
                    put("approvalId", JsonPrimitive(value.approvalId))
                    put("reason", JsonPrimitive(value.reason))
                }
            is WireMessage.ClientHello ->
                buildJsonObject {
                    put("op", JsonPrimitive("hello"))
                    put("deviceId", JsonPrimitive(value.deviceId))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("timestampMs", JsonPrimitive(value.timestampMs))
                    put("nonce", JsonPrimitive(value.nonce))
                    put("sig", JsonPrimitive(value.sig))
                    value.lastEventSeq?.let { put("lastEventSeq", JsonPrimitive(it)) }
                }
            is WireMessage.HelloOk ->
                buildJsonObject {
                    put("op", JsonPrimitive("hello.ok"))
                    put("serverTimeMs", JsonPrimitive(value.serverTimeMs))
                    put("protocolVersion", JsonPrimitive(value.protocolVersion))
                    if (value.oldestSeq != 0L) put("oldestSeq", JsonPrimitive(value.oldestSeq))
                    if (value.latestSeq != 0L) put("latestSeq", JsonPrimitive(value.latestSeq))
                }
            is WireMessage.HelloErr ->
                buildJsonObject {
                    put("op", JsonPrimitive("hello.err"))
                    put("reason", JsonPrimitive(value.reason))
                }
            is WireMessage.TerminalOutput ->
                buildJsonObject {
                    put("op", JsonPrimitive("terminal.output"))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("chunkBase64", JsonPrimitive(value.chunkBase64))
                }
            is WireMessage.FileListRequest ->
                buildJsonObject {
                    put("op", JsonPrimitive("file.list_request"))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("path", JsonPrimitive(value.path))
                }
            is WireMessage.ReplayEnd ->
                buildJsonObject {
                    put("op", JsonPrimitive("replay.end"))
                    put("replayedCount", JsonPrimitive(value.replayedCount))
                    put("fromSeq", JsonPrimitive(value.fromSeq))
                    put("toSeq", JsonPrimitive(value.toSeq))
                }
            is WireMessage.SessionEnded ->
                buildJsonObject {
                    put("op", JsonPrimitive("session.ended"))
                    put("sessionId", JsonPrimitive(value.sessionId))
                    put("reason", JsonPrimitive(value.reason))
                }
            is WireMessage.Unknown -> value.raw
        }

    private fun requireString(
        obj: JsonObject,
        key: String,
    ): String =
        obj[key]?.jsonPrimitive?.content
            ?: error("field '$key' missing or non-string in ${obj["op"]?.jsonPrimitive?.content ?: "<no op>"}")

    private val JsonPrimitive.long: Long get() = content.toLong()
    private val JsonPrimitive.longOrNull: Long? get() = content.toLongOrNull()
    private val JsonPrimitive.int: Int get() = content.toInt()
}

/**
 * Encodes a [WireMessage] to JSON with an additional top-level `seq` field.
 *
 * This is used by the relay to stamp outbound frames with their sequence number
 * without polluting the [WireMessage] data classes themselves.
 */
public fun encodeWithSeq(
    json: Json,
    msg: WireMessage,
    seq: Long,
): String {
    val element = WireMessageSerializer.encode(msg)
    val obj = element as JsonObject
    val withSeq = JsonObject(obj + ("seq" to JsonPrimitive(seq)))
    return json.encodeToString(JsonElement.serializer(), withSeq)
}
