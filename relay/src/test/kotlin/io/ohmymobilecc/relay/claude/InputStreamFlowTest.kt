package io.ohmymobilecc.relay.claude

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests for [InputStream.asLineFlow] — the JVM-only bridge that
 * turns a byte stream into a cold [kotlinx.coroutines.flow.Flow] of
 * NDJSON-framed lines, suitable for piping into
 * `io.ohmymobilecc.core.protocol.ccEvents`.
 *
 * These tests exercise the invariants the shared `ndjsonLines()` operator
 * already guarantees (see `NdjsonFramerTest`), but from the JVM byte side.
 * Behavior we care about:
 *
 *  - each `\n`-terminated line is emitted exactly once
 *  - a logical line split across multiple chunks / buffer reads is
 *    re-assembled
 *  - CRLF is normalized to LF-terminated framing (the `\r` is dropped)
 *  - a trailing partial line (no terminating `\n`) is dropped on EOF
 *  - blank lines are filtered out
 *  - UTF-8 multi-byte characters split across reads are not corrupted
 */
class InputStreamFlowTest {
    @Test
    fun `emits one event per newline-terminated line`() =
        runTest {
            val input = "a\nb\nc\n".byteInputStream()
            val lines = input.asLineFlow().toList()
            assertEquals(listOf("a", "b", "c"), lines)
        }

    @Test
    fun `stitches a line split across chunk boundaries`() =
        runTest {
            val pipe = PipedOutputStream()
            val source = PipedInputStream(pipe, 1024)

            // Write the byte stream in 3-byte chunks so the reader must
            // join fragments before emitting the logical line.
            val payload = """{"type":"system","subtype":"init"}""" + "\n"
            val bytes = payload.toByteArray(Charsets.UTF_8)
            // Use a thread because PipedInputStream requires producer and
            // consumer on different threads.
            val producer =
                Thread {
                    try {
                        var i = 0
                        while (i < bytes.size) {
                            val end = (i + 3).coerceAtMost(bytes.size)
                            pipe.write(bytes, i, end - i)
                            pipe.flush()
                            i = end
                        }
                    } finally {
                        pipe.close()
                    }
                }
            producer.start()

            val lines = source.asLineFlow().toList()
            producer.join()
            assertEquals(listOf("""{"type":"system","subtype":"init"}"""), lines)
        }

    @Test
    fun `handles CRLF by stripping the carriage return`() =
        runTest {
            val input = "alpha\r\nbeta\r\n".byteInputStream()
            val lines = input.asLineFlow().toList()
            assertEquals(listOf("alpha", "beta"), lines)
        }

    @Test
    fun `drops a trailing partial line on EOF without newline`() =
        runTest {
            val input = "complete\npartial-no-newline".byteInputStream()
            val lines = input.asLineFlow().toList()
            assertEquals(listOf("complete"), lines)
        }

    @Test
    fun `drops blank lines silently`() =
        runTest {
            val input = "a\n\nb\n\n\nc\n".byteInputStream()
            val lines = input.asLineFlow().toList()
            assertEquals(listOf("a", "b", "c"), lines)
        }

    @Test
    fun `handles utf-8 multi-byte characters split across reads`() =
        runTest {
            // "中" is three bytes in UTF-8 (0xE4 0xB8 0xAD). Feed it
            // byte-by-byte to ensure the reader re-assembles the code
            // point correctly instead of emitting replacement chars.
            val pipe = PipedOutputStream()
            val source = PipedInputStream(pipe, 1024)

            val payload = "中文\n测试\n"
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val producer =
                Thread {
                    try {
                        for (b in bytes) {
                            pipe.write(b.toInt())
                            pipe.flush()
                        }
                    } finally {
                        pipe.close()
                    }
                }
            producer.start()

            val lines = source.asLineFlow().toList()
            producer.join()
            assertEquals(listOf("中文", "测试"), lines)
        }

    @Test
    fun `emits nothing for an empty stream`() =
        runTest {
            val empty: InputStream = ByteArrayInputStream(ByteArray(0))
            val lines = empty.asLineFlow().toList()
            assertEquals(emptyList(), lines)
        }
}
