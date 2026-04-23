package io.ohmymobilecc.relay.claude

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle invariants for [ClaudeProcess]:
 *  - [ClaudeProcess.close] terminates a long-running child within ~1s
 *  - calling [ClaudeProcess.close] twice is safe (idempotent)
 *  - writing after close raises [IOException] (not a silent drop)
 */
class ClaudeProcessLifecycleTest {
    @Test
    fun `close terminates a long-running child within a second`() =
        runBlocking {
            val process =
                ClaudeProcess(
                    command = listOf("bash", "-c", "sleep 30"),
                    workingDir = Paths.get(System.getProperty("user.dir")),
                )

            // Give the child time to actually start executing sleep.
            delay(100)

            val before = System.currentTimeMillis()
            process.close()
            val exitCode =
                withTimeout(CLOSE_DEADLINE_MS) {
                    process.exit.await()
                }
            val elapsed = System.currentTimeMillis() - before

            assertTrue(elapsed < CLOSE_DEADLINE_MS, "close took ${elapsed}ms, deadline ${CLOSE_DEADLINE_MS}ms")
            // On POSIX, SIGTERM exits with 143 (128 + 15). Don't hard-code;
            // just assert "not a natural zero" because sleep 30 never
            // would have completed normally.
            assertFalse(exitCode == 0, "sleep 30 interrupted by close should not exit 0, got $exitCode")
        }

    @Test
    fun `close is idempotent`() =
        runBlocking {
            val process =
                ClaudeProcess(
                    command = listOf("bash", "-c", "sleep 30"),
                    workingDir = Paths.get(System.getProperty("user.dir")),
                )
            delay(50)
            process.close()
            process.close() // must not throw
            withTimeout(CLOSE_DEADLINE_MS) { process.exit.await() }
        }

    @Test
    fun `write after close throws IOException`() =
        runBlocking {
            val process =
                ClaudeProcess(
                    command = listOf("bash", "-c", "cat"),
                    workingDir = Paths.get(System.getProperty("user.dir")),
                )
            process.close()

            assertFailsWith<IOException> {
                process.writeUserMessage(ClaudeInput.UserMessage(content = "too late"))
            }

            withTimeout(CLOSE_DEADLINE_MS) { process.exit.await() }
        }

    private companion object {
        const val CLOSE_DEADLINE_MS: Long = 1_500
    }
}
