package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.Json

/**
 * Single source of truth for wire-format JSON configuration.
 *
 * Both the relay and the mobile client share this instance so that encode
 * and decode are guaranteed symmetric — divergent `Json` configs are a
 * classic source of silent field loss in KMP codebases.
 *
 * Flags:
 *  - `ignoreUnknownKeys = true`  → CC is free to add fields in future releases.
 *  - `isLenient = false`         → reject malformed JSON loudly; no silent coercion.
 *  - `encodeDefaults = false`    → minimize wire payload; default-valued props are omitted.
 *  - `classDiscriminator = "type"` → matches CC stream-json convention; WireMessage
 *    overrides via `@JsonClassDiscriminator("op")` on its hierarchy.
 */
public object ProtocolJson {
    public val default: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            encodeDefaults = false
            classDiscriminator = "type"
            prettyPrint = false
        }
}
