package io.ohmymobilecc.relay.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PairCommandTest {
    @Test
    fun `prints 6 digit code to stdout and returns it via pair flow`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pubkey = ByteArray(32) { 0x44 }
        val exit =
            runBlocking {
                // Launch the paired-client emulator: waits until stdout has a code, then redeems it.
                val state = RelayProcessState.fresh()
                scope.launch {
                    // poll stdout for 6 digits then redeem
                    var digits: String? = null
                    while (digits == null) {
                        val text = stdout.toString(Charsets.UTF_8)
                        Regex("""^(\d{6})""", RegexOption.MULTILINE).find(text)?.let { digits = it.value }
                        delay(POLL_MS)
                    }
                    state.pairingService.redeem(digits!!, pubkey)
                }
                PairCommand.run(
                    argv = emptyArray(),
                    stdout = PrintStream(stdout, true),
                    stderr = PrintStream(stderr, true),
                    state = state,
                    timeoutMs = SHORT_TIMEOUT_MS,
                )
            }
        assertEquals(0, exit)
        assertContains(stdout.toString(Charsets.UTF_8), Regex("""^\d{6}""", RegexOption.MULTILINE))
    }

    @Test
    fun `times out and exits non-zero when no client redeems`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit =
            PairCommand.run(
                argv = emptyArray(),
                stdout = PrintStream(stdout, true),
                stderr = PrintStream(stderr, true),
                state = RelayProcessState.fresh(),
                timeoutMs = VERY_SHORT_TIMEOUT_MS,
            )
        assertEquals(PairCommand.EXIT_ERROR, exit)
        assertContains(stdout.toString(Charsets.UTF_8), "pairing timeout")
    }

    private companion object {
        const val POLL_MS: Long = 15L
        const val SHORT_TIMEOUT_MS: Long = 2_000L
        const val VERY_SHORT_TIMEOUT_MS: Long = 50L

        @Suppress("unused")
        val stdinNoop: InputStream = InputStream.nullInputStream()
    }
}
