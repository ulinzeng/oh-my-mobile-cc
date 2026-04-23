package io.ohmymobilecc.relay.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ServeCommandBootTest {
    @Test
    fun `prints listening line and stops gracefully`() =
        runBlocking {
            val stdout = ByteArrayOutputStream()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val state = RelayProcessState.fresh()

            // Run serve on port 0 — OS picks a free port. Launch the server
            // in a child coroutine so we can stop it after asserting stdout.
            val job =
                scope.launch {
                    ServeCommand.run(
                        argv = arrayOf("--port", "0"),
                        stdout = PrintStream(stdout, true),
                        stderr = PrintStream(ByteArrayOutputStream(), true),
                        state = state,
                    )
                }

            // Wait until startup logs the listening line.
            val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
            var printed = ""
            while (System.currentTimeMillis() < deadline) {
                printed = stdout.toString(Charsets.UTF_8)
                if (printed.contains("relay listening on :")) break
                delay(POLL_MS)
            }
            assertContains(printed, "relay listening on :")

            // Trigger graceful shutdown via state hook (stand-in for SIGINT in-process).
            state.signalShutdown()
            job.join()
            assertEquals(true, state.isShutdown)
        }

    private companion object {
        const val BOOT_TIMEOUT_MS: Long = 10_000L
        const val POLL_MS: Long = 50L
    }
}
