package io.ohmymobilecc.core.protocol

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM-only: drive [ndjsonLines] + [ccEvents] end-to-end with the real
 * capture, chunked at arbitrary boundaries to exercise the stitching path.
 */
class NdjsonFramerFixtureTest {

    @Test
    fun `frames real capture chunked at 37-byte boundaries and decodes everything typed`() = runTest {
        val url = this::class.java.classLoader.getResource("fixtures/real_captures/03-hook-bridge-approved.ndjson")
            ?: error("fixture not on classpath")
        val text = url.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }

        // Split into 37-byte chunks — deliberately not aligned to line boundaries.
        val chunks = text.chunked(37)
        val events = flowOf(*chunks.toTypedArray()).ccEvents().toList()

        val expectedLines = text.lineSequence().filter { it.isNotBlank() }.count()
        assertTrue(
            events.size == expectedLines,
            "expected $expectedLines events but framer produced ${events.size}",
        )
        val unknowns = events.filterIsInstance<CCEvent.Unknown>()
        assertTrue(unknowns.isEmpty(), "expected 0 Unknown but got ${unknowns.size}")
    }
}
