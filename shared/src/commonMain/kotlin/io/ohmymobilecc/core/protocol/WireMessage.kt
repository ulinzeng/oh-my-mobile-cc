package io.ohmymobilecc.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

    private fun encode(value: WireMessage): JsonElement =
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
            is WireMessage.Unknown -> value.raw
        }

    private fun requireString(
        obj: JsonObject,
        key: String,
    ): String =
        obj[key]?.jsonPrimitive?.content
            ?: error("field '$key' missing or non-string in ${obj["op"]?.jsonPrimitive?.content ?: "<no op>"}")

    private val JsonPrimitive.long: Long get() = content.toLong()
}
