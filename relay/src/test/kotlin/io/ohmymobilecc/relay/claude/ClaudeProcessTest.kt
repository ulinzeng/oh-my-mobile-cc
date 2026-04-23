package io.ohmymobilecc.relay.claude

import io.ohmymobilecc.core.protocol.CCEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Happy-path integration test for [ClaudeProcess].
 *
 * We don't spawn real `claude` here — instead we use a tiny bash
 * replay binary that cats a pre-baked NDJSON capture to stdout. That
 * lets us verify the end-to-end pipeline (Process → asLineFlow →
 * ndjsonLines → ccEvents) against known-good data deterministically.
 *
 * Note: this test requires POSIX `bash` on the developer machine. It
 * is skipped in hostile environments (see `assumeBash`).
 */
class ClaudeProcessTest {
    private lateinit var replayScript: File
    private lateinit var fixture: File

    @BeforeTest
    fun setUp() {
        // `src/test/resources/**` lands on the classpath at test time.
        replayScript =
            fileFromClasspath("fakebin/replay.sh")
                .also { it.setExecutable(true) }
        fixture = fileFromClasspath("real_captures/03-hook-bridge-approved.ndjson")
    }

    @Test
    fun `replays real capture as typed CCEvents`() =
        runBlocking {
            val process =
                ClaudeProcess(
                    command = listOf("bash", replayScript.absolutePath, fixture.absolutePath),
                    workingDir = replayScript.parentFile.toPath(),
                )
            val events =
                withTimeout(TEST_TIMEOUT_MS) {
                    process.events.toList()
                }
            val exitCode = process.exit.await()
            process.close()

            // Sanity on volume.
            assertTrue(events.size >= EXPECTED_MIN_EVENTS, "expected >=$EXPECTED_MIN_EVENTS events, got ${events.size}")

            // The fixture contains exactly one system.init event somewhere
            // after the SessionStart hook. Presence is what matters, not
            // ordering — the relay consumer is event-driven anyway.
            assertTrue(
                events.any { it is CCEvent.System && (it as CCEvent.System).subtype == "init" },
                "expected at least one CCEvent.System(subtype=init)",
            )

            // At least one assistant with tool_use content.
            assertTrue(
                events.any { ev ->
                    ev is CCEvent.Assistant && ev.raw.toString().contains("\"type\":\"tool_use\"")
                },
                "expected at least one Assistant carrying tool_use",
            )

            // At least one HookStarted PreToolUse.
            assertTrue(
                events.any { it is CCEvent.HookStarted && (it as CCEvent.HookStarted).hookEvent == "PreToolUse" },
                "expected at least one HookStarted(PreToolUse)",
            )

            // At least one HookResponse with exitCode == 0.
            assertTrue(
                events.any { it is CCEvent.HookResponse && (it as CCEvent.HookResponse).exitCode == 0 },
                "expected at least one HookResponse(exitCode=0)",
            )

            assertEquals(0, exitCode, "replay script should exit 0")
        }

    @Test
    fun `stderr is separate from events`() =
        runBlocking {
            val stderrMarker = "REPLAY-STDERR-WARN"
            val process =
                ClaudeProcess(
                    command = listOf("bash", replayScript.absolutePath, fixture.absolutePath, stderrMarker),
                    workingDir = replayScript.parentFile.toPath(),
                )
            val events =
                withTimeout(TEST_TIMEOUT_MS) {
                    process.events.toList()
                }
            val stderrLines =
                withTimeout(TEST_TIMEOUT_MS) {
                    process.stderr.toList()
                }
            process.close()

            // Stderr contains the marker.
            assertTrue(
                stderrLines.any { it.contains(stderrMarker) },
                "expected stderr to contain $stderrMarker, got $stderrLines",
            )
            // None of the typed events should carry the marker — it was
            // raw text, not JSON, so any leak would have thrown a
            // SerializationException already; double-check the string
            // form just to be safe.
            assertTrue(
                events.none { it.raw.toString().contains(stderrMarker) },
                "stderr must not reach the event stream",
            )
        }

    private fun fileFromClasspath(resource: String): File {
        val url =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResource(resource),
            ) { "test resource not found: $resource" }
        return File(url.toURI())
    }

    private companion object {
        const val TEST_TIMEOUT_MS: Long = 5_000
        const val EXPECTED_MIN_EVENTS: Int = 90 // fixture has 98 lines; some may be blank
    }
}
