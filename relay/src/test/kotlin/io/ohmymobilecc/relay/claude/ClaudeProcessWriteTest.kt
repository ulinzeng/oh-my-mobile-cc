package io.ohmymobilecc.relay.claude

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [ClaudeProcess.writeUserMessage] serializes a single
 * stream-json frame and appends exactly one `\n` before flushing. We
 * use `bash -c cat` as the child so whatever we write to stdin must
 * come back verbatim on stdout.
 */
class ClaudeProcessWriteTest {
    @Test
    fun `writes user message as a single ndjson line`() =
        runBlocking {
            val workingDir =
                java.nio.file.Paths
                    .get(System.getProperty("user.dir"))
            val process =
                ClaudeProcess(
                    command = listOf("bash", "-c", "cat"),
                    workingDir = workingDir,
                )

            val message = ClaudeInput.UserMessage(content = "hello from relay")
            process.writeUserMessage(message)

            // Polite shutdown: close stdin only so `cat` sees EOF,
            // drains the buffered line, and exits. A hard close() would
            // SIGTERM the child and truncate the line we just wrote.
            process.closeStdin()

            val echoed =
                withTimeout(TIMEOUT_MS) {
                    process.events.first()
                }

            val raw = echoed.raw.toString()
            assertTrue(raw.contains("\"type\":\"user\""), "missing type=user in $raw")
            assertTrue(raw.contains("\"role\":\"user\""), "missing role=user in $raw")
            assertTrue(raw.contains("\"content\":\"hello from relay\""), "missing content in $raw")

            assertEquals(0, process.exit.await(), "cat should exit 0 after stdin closes")
        }

    private companion object {
        const val TIMEOUT_MS: Long = 5_000
    }
}
