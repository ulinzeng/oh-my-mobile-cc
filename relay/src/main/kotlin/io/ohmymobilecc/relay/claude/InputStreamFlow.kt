package io.ohmymobilecc.relay.claude

import io.ohmymobilecc.core.protocol.ndjsonLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Bridges a blocking [InputStream] (typically `process.inputStream` /
 * `process.errorStream`) into a cold [Flow] of NDJSON-framed lines,
 * leaning on the shared [ndjsonLines] operator for the actual line
 * framing.
 *
 * The stream is read through a UTF-8 [InputStreamReader] wrapped in a
 * [BufferedReader] so multi-byte code points split across OS-level read
 * boundaries are re-assembled correctly before we inspect the bytes for
 * `\n`.
 *
 * ### Threading contract
 *
 * The emission happens on [Dispatchers.IO] via [flowOn], so callers can
 * consume the flow from any coroutine context. Cancellation of the
 * downstream flow cancels the reader coroutine; the underlying
 * [InputStream] is NOT closed by this operator — the caller that owns
 * the process is responsible for closing it (see `ClaudeProcess.close`).
 *
 * ### Framing contract
 *
 *  - each `\n`-terminated line is emitted exactly once
 *  - CRLF is normalized (the `\r` is dropped)
 *  - a trailing partial line without a `\n` at EOF is dropped
 *  - blank lines are silently filtered
 *
 * These match the invariants declared and tested on
 * `ndjsonLines()` in the `shared` module.
 */
public fun InputStream.asLineFlow(): Flow<String> = asChunkedCharFlow().ndjsonLines().flowOn(Dispatchers.IO)

/**
 * Internal building block: raw UTF-8 chunks as they come off the stream.
 *
 * Exposed as `internal` (package-private via default visibility + lib
 * coordinates) so higher-level operators like `ccEvents()` can plug the
 * shared `ndjsonLines()` operator in themselves, avoiding the
 * double-framing trap that would otherwise occur when
 * `asLineFlow().ccEvents()` is called (ccEvents also invokes
 * `ndjsonLines()` internally on its upstream).
 */
internal fun InputStream.asChunkedCharFlow(): Flow<String> {
    val source = this
    val bufferSize = DEFAULT_READ_BUFFER
    return flow {
        BufferedReader(InputStreamReader(source, StandardCharsets.UTF_8), bufferSize).use { reader ->
            val buffer = CharArray(bufferSize)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (read > 0) emit(String(buffer, 0, read))
            }
        }
    }
}

private const val DEFAULT_READ_BUFFER: Int = 8 * 1024
