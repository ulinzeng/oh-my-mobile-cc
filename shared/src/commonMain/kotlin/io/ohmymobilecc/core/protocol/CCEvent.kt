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
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed model of `claude -p --output-format stream-json` stdout events.
 *
 * Every variant carries the full original JSON in [raw] — decode is lossless
 * even when typed fields only cover a fraction of the payload. Unknown
 * top-level `type` values decode to [Unknown]; the parser never throws on
 * an unrecognized event shape.
 *
 * See `openspec/specs/protocol/spec.md` and the real captures in
 * `shared/src/commonTest/resources/fixtures/real_captures/`.
 */
@Serializable(with = CCEventSerializer::class)
public sealed class CCEvent {
    public abstract val raw: JsonObject

    public data class System(
        val subtype: String? = null,
        val sessionId: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    public data class User(
        val message: JsonObject? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    public data class Assistant(
        val message: JsonObject? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    public data class Result(
        val subtype: String? = null,
        val isError: Boolean? = null,
        val durationMs: Long? = null,
        val numTurns: Int? = null,
        val stopReason: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    public data class StreamEvent(
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    /** `{"type":"system","subtype":"hook_started", ...}`. */
    public data class HookStarted(
        val hookId: String? = null,
        val hookName: String? = null,
        val hookEvent: String? = null,
        val sessionId: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    /** `{"type":"system","subtype":"hook_response", ...}`. */
    public data class HookResponse(
        val hookId: String? = null,
        val hookName: String? = null,
        val hookEvent: String? = null,
        val sessionId: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        val outcome: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    /** `{"type":"system","subtype":"hook_progress", ...}`. */
    public data class HookProgress(
        val hookId: String? = null,
        val hookName: String? = null,
        val hookEvent: String? = null,
        val sessionId: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent()

    /** Any line we could not route to a known variant. */
    public data class Unknown(
        override val raw: JsonObject,
    ) : CCEvent()
}

/**
 * Custom JSON serializer that routes by the `type` discriminator, and by
 * `subtype` within system events (to isolate the three hook lifecycle
 * variants). Unknown top-level types decode to [CCEvent.Unknown].
 *
 * Encoding is trivially lossless: every variant already carries the full
 * original payload in `raw`, so we just emit that.
 */
public object CCEventSerializer : KSerializer<CCEvent> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.ohmymobilecc.core.protocol.CCEvent")

    override fun deserialize(decoder: Decoder): CCEvent {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("CCEvent requires a JsonDecoder; got ${decoder::class.simpleName}")
        val element: JsonElement = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject ?: return CCEvent.Unknown(JsonObject(emptyMap()))

        val type = obj["type"]?.jsonPrimitive?.contentOrNullSafe()
        return when (type) {
            "system" -> decodeSystem(obj)
            "user" ->
                CCEvent.User(
                    message = obj["message"] as? JsonObject,
                    raw = obj,
                )
            "assistant" ->
                CCEvent.Assistant(
                    message = obj["message"] as? JsonObject,
                    raw = obj,
                )
            "result" ->
                CCEvent.Result(
                    subtype = obj["subtype"]?.jsonPrimitive?.contentOrNullSafe(),
                    isError = obj["is_error"]?.jsonPrimitive?.booleanOrNullSafe(),
                    durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNullSafe(),
                    numTurns = obj["num_turns"]?.jsonPrimitive?.intOrNullSafe(),
                    stopReason = obj["stop_reason"]?.jsonPrimitive?.contentOrNullSafe(),
                    raw = obj,
                )
            "stream_event" -> CCEvent.StreamEvent(raw = obj)
            else -> CCEvent.Unknown(raw = obj)
        }
    }

    private fun decodeSystem(obj: JsonObject): CCEvent {
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNullSafe()
        val hookId = obj["hook_id"]?.jsonPrimitive?.contentOrNullSafe()
        val hookName = obj["hook_name"]?.jsonPrimitive?.contentOrNullSafe()
        val hookEvent = obj["hook_event"]?.jsonPrimitive?.contentOrNullSafe()
        val sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNullSafe()
        return when (subtype) {
            "hook_started" -> CCEvent.HookStarted(hookId, hookName, hookEvent, sessionId, obj)
            "hook_progress" -> CCEvent.HookProgress(hookId, hookName, hookEvent, sessionId, obj)
            "hook_response" ->
                CCEvent.HookResponse(
                    hookId = hookId,
                    hookName = hookName,
                    hookEvent = hookEvent,
                    sessionId = sessionId,
                    output = obj["output"]?.jsonPrimitive?.contentOrNullSafe(),
                    exitCode = obj["exit_code"]?.jsonPrimitive?.intOrNullSafe(),
                    outcome = obj["outcome"]?.jsonPrimitive?.contentOrNullSafe(),
                    raw = obj,
                )
            else ->
                CCEvent.System(
                    subtype = subtype,
                    sessionId = sessionId,
                    raw = obj,
                )
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: CCEvent,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("CCEvent requires a JsonEncoder; got ${encoder::class.simpleName}")
        jsonEncoder.encodeJsonElement(value.raw)
    }

    // --- small null-tolerant accessors (avoid throwing on odd fixtures) ---

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (!isString && content == "null") null else content

    private fun JsonPrimitive.booleanOrNullSafe(): Boolean? = runCatching { content.toBooleanStrictOrNull() }.getOrNull()

    private fun JsonPrimitive.intOrNullSafe(): Int? = runCatching { content.toIntOrNull() }.getOrNull()

    private fun JsonPrimitive.longOrNullSafe(): Long? = runCatching { content.toLongOrNull() }.getOrNull()
}
