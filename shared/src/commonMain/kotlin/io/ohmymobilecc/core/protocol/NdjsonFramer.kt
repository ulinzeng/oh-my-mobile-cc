package io.ohmymobilecc.core.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * NDJSON framing utilities.
 *
 * A stdout / WebSocket byte reader typically emits arbitrary chunk sizes:
 * one chunk may contain multiple complete JSON lines, one JSON line may
 * span several chunks. Before we can hand individual lines to
 * [ProtocolJson] we must stitch a raw `Flow<String>` back into one
 * `String` per logical line.
 *
 * Contract:
 *  - A line is any sequence of characters terminated by `\n` (with an
 *    optional leading `\r`, i.e. CRLF is supported).
 *  - Blank lines (`""` after stripping) are dropped silently.
 *  - A trailing partial line without a terminating `\n` is **dropped**
 *    when upstream completes. Callers that need partial-line semantics
 *    (e.g. integration tests) should append their own `\n`.
 *
 * Exceptions propagate — malformed input is the caller's problem, this
 * operator only cares about line boundaries.
 */
public fun Flow<String>.ndjsonLines(): Flow<String> =
    flow {
        val buffer = StringBuilder()
        collect { chunk ->
            buffer.append(chunk)
            var newlineIdx = buffer.indexOf('\n')
            while (newlineIdx >= 0) {
                // Strip optional \r before the \n so we cleanly handle CRLF.
                val end = if (newlineIdx > 0 && buffer[newlineIdx - 1] == '\r') newlineIdx - 1 else newlineIdx
                val line = buffer.substring(0, end)
                buffer.deleteRange(0, newlineIdx + 1)
                if (line.isNotBlank()) emit(line)
                newlineIdx = buffer.indexOf('\n')
            }
        }
        // Intentionally drop trailing partial line — caller's responsibility.
    }

/**
 * Convenience: frame the upstream `Flow<String>` into NDJSON lines and
 * decode each one into a [CCEvent] using the supplied `Json` instance
 * (defaults to [ProtocolJson.default]).
 *
 * Malformed JSON propagates as `SerializationException`; unknown types
 * fall back to `CCEvent.Unknown` per the decoder contract.
 */
public fun Flow<String>.ccEvents(json: Json = ProtocolJson.default): Flow<CCEvent> =
    flow {
        ndjsonLines().collect { line ->
            emit(json.decodeFromString(CCEvent.serializer(), line))
        }
    }
