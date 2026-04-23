package io.ohmymobilecc.relay.claude

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sealed type of everything the relay is allowed to write to the
 * CC `claude -p --input-format stream-json` subprocess on its stdin.
 *
 * **Invariant (ADR-0004, protocol/spec.md "CC 事件编码"):**
 * permission decisions are NEVER sent through this channel — they ride
 * the `PreToolUse` hook subprocess stdout exit. To enforce this at the
 * type level, [ClaudeInput] has exactly one concrete variant right now:
 * [UserMessage]. Any contributor tempted to add a `PermissionResponse`
 * variant must first update the spec.
 */
public sealed class ClaudeInput {
    /**
     * A chat turn from the mobile user, forwarded by the relay to CC
     * so the agent can continue the conversation.
     */
    @Serializable(with = UserMessage.Companion::class)
    public data class UserMessage(
        val content: String,
        val role: String = "user",
    ) : ClaudeInput() {
        public fun toJsonObject(): JsonObject =
            buildJsonObject {
                put("type", JsonPrimitive("user"))
                put(
                    "message",
                    buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", JsonPrimitive(content))
                    },
                )
            }

        public companion object : KSerializer<UserMessage> {
            override val descriptor: SerialDescriptor =
                buildClassSerialDescriptor("io.ohmymobilecc.relay.claude.ClaudeInput.UserMessage")

            override fun serialize(
                encoder: Encoder,
                value: UserMessage,
            ) {
                val jsonEncoder =
                    encoder as? JsonEncoder
                        ?: error("UserMessage requires a JsonEncoder; got ${encoder::class.simpleName}")
                jsonEncoder.encodeJsonElement(value.toJsonObject())
            }

            override fun deserialize(decoder: Decoder): UserMessage = error("UserMessage is output-only")

            /** Explicit handle so [ClaudeProcess] can reach the serializer without reflection. */
            public fun serializerJson(): KSerializer<UserMessage> = this
        }
    }
}
