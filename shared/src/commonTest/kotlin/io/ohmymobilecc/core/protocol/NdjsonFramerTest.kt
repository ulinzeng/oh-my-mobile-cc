package io.ohmymobilecc.core.protocol

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract tests for the NDJSON framer that turns a `Flow<String>` of
 * arbitrary chunks (as produced by stdout readers on any platform) into
 * a `Flow<String>` of complete JSON lines, and the convenience chain that
 * decodes those lines straight into [CCEvent].
 */
class NdjsonFramerTest {

    @Test
    fun `splits a flow of 3 whole lines into 3 lines`() = runTest {
        val chunks = listOf(
            "{\"type\":\"a\"}\n",
            "{\"type\":\"b\"}\n",
            "{\"type\":\"c\"}\n",
        )
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("{\"type\":\"a\"}", "{\"type\":\"b\"}", "{\"type\":\"c\"}"), lines)
    }

    @Test
    fun `stitches a line that was split across two chunks`() = runTest {
        val chunks = listOf("""{"type":"sys""", """tem","subtype":"x"}""" + "\n")
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("""{"type":"system","subtype":"x"}"""), lines)
    }

    @Test
    fun `yields multiple lines from a single chunk`() = runTest {
        val chunks = listOf("{\"a\":1}\n{\"b\":2}\n{\"c\":3}\n")
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}"""), lines)
    }

    @Test
    fun `skips blank lines`() = runTest {
        val chunks = listOf("{\"a\":1}\n\n\n{\"b\":2}\n")
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines)
    }

    @Test
    fun `handles CRLF line separators`() = runTest {
        val chunks = listOf("{\"a\":1}\r\n{\"b\":2}\r\n")
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines)
    }

    @Test
    fun `trailing partial line without newline is dropped`() = runTest {
        // Stream ended mid-line — we do NOT emit an incomplete line.
        // Caller is responsible for flushing if they need partial-line semantics.
        val chunks = listOf("""{"a":1}""" + "\n" + """{"b":2""")
        val lines = flowOf(*chunks.toTypedArray()).ndjsonLines().toList()
        assertEquals(listOf("""{"a":1}"""), lines)
    }

    @Test
    fun `empty flow emits nothing`() = runTest {
        val lines = flowOf<String>().ndjsonLines().toList()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `ccEvents chains line framer and JSON decoder`() = runTest {
        val chunks = listOf(
            "{\"type\":\"system\",\"subtype\":\"init\"}\n",
            "{\"type\":\"assistant\"}\n",
            "{\"type\":\"not_yet_typed\"}\n",
        )
        val events = flowOf(*chunks.toTypedArray()).ccEvents(ProtocolJson.default).toList()
        assertEquals(3, events.size)
        assertIs<CCEvent.System>(events[0])
        assertIs<CCEvent.Assistant>(events[1])
        assertIs<CCEvent.Unknown>(events[2])
    }
}
